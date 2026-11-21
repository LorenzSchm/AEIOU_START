import json
import logging
import os
import re
import threading
from contextlib import asynccontextmanager
from datetime import timedelta
from decimal import Decimal
from uuid import uuid4
from zoneinfo import ZoneInfo

import httpx
from dotenv import load_dotenv
from fastapi import FastAPI, HTTPException, Response
from pydantic import BaseModel, Field, ValidationError

from .pricing import (
    CARRIERS,
    ROOT,
    TARIFFS,
    ZONES,
    Offer,
    Shipment,
    contacts,
    local_offers,
    rank,
)
from .provider import Provider
from .store import Store, now, timestamp

log = logging.getLogger("uvicorn.error")


class CarrierBid(BaseModel):
    airline: str = Field(min_length=1, max_length=100)
    air_total_eur: Decimal = Field(gt=0, allow_inf_nan=False)
    transit_days: int = Field(ge=1, le=90)


class PriceInput(BaseModel):
    shipment: Shipment
    carrier_bid: CarrierBid | None = None


class Proposal(BaseModel):
    revision: str
    offer: Offer | None
    alternatives: list[Offer]
    notes: list[str]
    body: str


class DraftFields(BaseModel):
    origin: str | None
    destination: str | None
    weight_kg: str | None
    volume_cbm: str | None
    preference: str | None
    pickup_zone: str | None
    pickup_eur: str | None
    pickup_days: int


class MailItem(BaseModel):
    id: int
    root: int
    sender: str
    subject: str
    body: str
    received: str | None
    kind: str
    fields: DraftFields
    attachments: list[dict]
    stage: str
    proposal: Proposal | None
    booking: dict | None
    delivery: str | None
    reply_delivery: str | None
    bid_requests: dict[str, str]


class Status(BaseModel):
    last_poll: str | None
    error: str | None
    auto_send: bool
    cursor: int
    tariffs: int


class QuoteSend(BaseModel):
    revision: str


class BidRequest(BaseModel):
    shipment: Shipment
    email: str


class Reply(BaseModel):
    body: str = Field(min_length=1, max_length=20000)


def create_app(*, db_path=None, provider=None, run_workers=True, auto_send=None):
    load_dotenv(ROOT / ".env")
    db = Store(
        db_path or os.getenv("DISPATCH_DB") or ROOT / "backend/state/dispatch.sqlite3"
    )
    api = provider or Provider(
        os.getenv("FIVEU_BASE_URL", "https://api.start-hack.5u.ai"),
        os.getenv("TEAM_API_KEY", ""),
    )
    automatic = os.getenv("AUTO_SEND", "0") == "1" if auto_send is None else auto_send
    status = {"last_poll": None, "error": None, "auto_send": automatic}
    stop = threading.Event()
    pricing_lock = threading.Lock()

    def message(mid):
        try:
            return db.message(mid)
        except KeyError:
            raise HTTPException(404, "Message not found")

    def expired(m):
        return m["received"] and now() >= timestamp(m["received"]) + timedelta(
            minutes=6
        )

    def quote_target(mid):
        m = message(mid)
        if m["kind"] != "request" or m["root"] != mid:
            raise HTTPException(409, "Select the original customer request to quote")
        if expired(m):
            raise HTTPException(409, "The six-minute quote window has closed")
        return m

    def calculate(mid, payload):
        m = quote_target(mid)
        s = payload.shipment
        instant = timestamp(m["received"]) if m["received"] else now()
        offers = local_offers(s, instant.astimezone(ZoneInfo("Europe/Berlin")).date())
        online, notes = api.rates(s)
        offers += online
        if payload.carrier_bid:
            bid = payload.carrier_bid
            offers.append(
                Offer(
                    airline=bid.airline,
                    days=bid.transit_days,
                    total_eur=bid.air_total_eur,
                    billed_kg=max(s.weight_kg, s.volume_cbm * 167),
                    source="carrier bid",
                )
            )
        ranked = rank(s, offers)
        best = ranked[0] if ranked else None
        body = ""
        if best:
            route = f"{s.origin} to {s.destination}"
            if s.pickup_zone:
                route = f"pickup in zone {s.pickup_zone} via {route}"
            body = (
                f"Hello,\n\nFor {route}, our {s.preference} available option is {best.airline}. "
                f"The all-in total is EUR {best.total_eur:.2f}, with an estimated transit of "
                f"{best.days} {'day' if best.days == 1 else 'days'}.\n\n"
                "Please let us know if you would like us to book this shipment.\n\nBest regards,\nTeam AEIOU"
            )
        elif s.pickup_zone and s.pickup_eur is None:
            notes.append("Enter a confirmed truck price to quote a door-to-port total.")
        else:
            notes.append("No priced air service on this lane. Request carrier bids.")
        proposal = Proposal(
            revision=uuid4().hex,
            offer=best,
            alternatives=ranked,
            notes=notes,
            body=body,
        )
        if not db.persist_proposal(
            mid, s.model_dump(mode="json"), proposal.model_dump(mode="json")
        ):
            raise HTTPException(409, "A quote is already claimed for this thread")
        return proposal

    def deliver(mid, action, payload, revision=None):
        if not db.reserve(mid, action, revision):
            raise HTTPException(
                409,
                "Already sent, awaiting confirmation, or draft changed. Refresh the thread.",
            )
        try:
            r = api.client.post("/v1/mailbox/messages", json=payload)
            if 400 <= r.status_code < 500 and r.status_code != 408:
                db.finish(action, "rejected")
                raise HTTPException(
                    502,
                    f"Mailbox rejected the send (HTTP {r.status_code}). Refresh before retrying.",
                )
            r.raise_for_status()
            receipt = r.json()
            db.finish(action, "sent", receipt)
            return {"state": "sent", "id": receipt.get("id")}
        except (httpx.HTTPError, ValueError):
            db.finish(action, "uncertain")
            raise HTTPException(
                502,
                "Delivery unconfirmed. Check the team mailbox before any manual resend.",
            )

    def send_quote(mid, revision):
        m = quote_target(mid)
        p = json.loads(m["proposal"] or "null")
        if not p or not p["offer"] or p["revision"] != revision:
            raise HTTPException(409, "Calculate a current quote first")
        if p["offer"]["valid_until"] and timestamp(p["offer"]["valid_until"]) <= now():
            raise HTTPException(409, "Portal rate expired. Recalculate the quote.")
        if not m["sender"]:
            raise HTTPException(409, "Missing customer address")
        return deliver(
            mid,
            f"quote:{mid}",
            {
                "to": m["sender"],
                "in_reply_to": mid,
                "subject": "Re: " + m["subject"],
                "body": p["body"],
            },
            revision,
        )

    def poll_worker():
        failures = 0
        while not stop.is_set():
            try:
                page = api.poll(db.cursor())
                db.ingest(page)
                status.update(last_poll=now().isoformat(), error=None)
                failures = 0
                delay = 0 if len(page["messages"]) == 200 else 2
            except Exception as exc:  # noqa: BLE001 - keep the poller alive and expose failure in /status
                failures += 1
                detail = (
                    f"HTTP {exc.response.status_code}"
                    if isinstance(exc, httpx.HTTPStatusError)
                    else type(exc).__name__
                )
                status["error"] = f"Mailbox poll failed ({detail})"
                if failures == 1:
                    log.warning(status["error"])
                delay = min(30, 2 ** min(failures, 5))
            stop.wait(delay)

    def draft_worker():
        while not stop.is_set():
            with db.connect() as conn:
                row = conn.execute(
                    "SELECT id FROM mail WHERE stage='new' ORDER BY id LIMIT 1"
                ).fetchone()
            if row is None:
                stop.wait(0.5)
                continue
            mid = row["id"]
            try:
                with pricing_lock:
                    m = message(mid)
                    if m["stage"] != "new":
                        continue
                    text = m["subject"] + " " + m["body"]
                    s = Shipment(**json.loads(m["fields"]))
                    p = calculate(mid, PriceInput(shipment=s))
                    special = re.search(
                        r"pick.?up|door|abhol|dangerous|hazard|temperat|ADR|lithium|\bdims?\b|\d\s*[x×]\s*\d",
                        text,
                        re.IGNORECASE,
                    )
                    asks_for_rate = re.search(
                        r"\b(?:rfq|quot(?:e|ation)|price|rate|angebot|frachtpreis)\b",
                        text,
                        re.IGNORECASE,
                    )
                    if (
                        automatic
                        and asks_for_rate
                        and p.offer
                        and not p.notes
                        and m["received"]
                        and not special
                        and not s.pickup_zone
                        and not json.loads(m["attachments"])
                    ):
                        send_quote(mid, p.revision)
            except (HTTPException, ValidationError):
                pass
            except Exception:
                log.exception("Unable to prepare request %s", mid)
            finally:
                with db.connect() as conn:
                    conn.execute(
                        "UPDATE mail SET stage='review' WHERE id=? AND stage='new'",
                        (mid,),
                    )

    @asynccontextmanager
    async def lifespan(app):
        threads = (
            [
                threading.Thread(target=fn, daemon=True)
                for fn in (poll_worker, draft_worker)
            ]
            if run_workers
            else []
        )
        for thread in threads:
            thread.start()
        yield
        stop.set()
        for thread in threads:
            thread.join(timeout=8)
        api.client.close()

    app = FastAPI(title="AEIOU Dispatch", version="1.0.0", lifespan=lifespan)
    app.state.store = db

    @app.get("/api/v1/status", response_model=Status)
    def health():
        return {**status, "cursor": db.cursor(), "tariffs": len(TARIFFS)}

    @app.get("/api/v1/messages", response_model=list[MailItem])
    def inbox():
        return db.messages()

    @app.get("/api/v1/directory")
    def directory():
        return {"carriers": CARRIERS, "zones": ZONES}

    @app.post("/api/v1/messages/{mid}/price", response_model=Proposal)
    def price(mid: int, payload: PriceInput):
        with pricing_lock:
            return calculate(mid, payload)

    @app.post("/api/v1/messages/{mid}/quote")
    def submit(mid: int, payload: QuoteSend):
        return send_quote(mid, payload.revision)

    @app.post("/api/v1/messages/{mid}/bids")
    def request_bid(mid: int, payload: BidRequest):
        quote_target(mid)
        s = payload.shipment
        carrier = next((c for c in contacts(s) if c["email"] == payload.email), None)
        if carrier is None:
            raise HTTPException(
                422, "Carrier does not serve this destination or pickup zone"
            )
        leg = (
            f"pickup in zone {s.pickup_zone} to {s.origin}"
            if carrier["kind"] == "trucker"
            else f"{s.origin} to {s.destination}"
        )
        return deliver(
            mid,
            f"bid:{mid}:{carrier['email']}",
            {
                "to": carrier["email"],
                "subject": f"Rate request AEIOU-{mid} | {leg}",
                "body": f"Hello, please quote {leg} for {s.weight_kg} kg and {s.volume_cbm} cbm. "
                "Please send your all-in total, currency and transit time. Thank you, Team AEIOU",
            },
        )

    @app.post("/api/v1/messages/{mid}/reply")
    def reply(mid: int, payload: Reply):
        m = message(mid)
        if m["kind"] == "request" or not m["sender"]:
            raise HTTPException(409, "Use the quote workflow for an original RFQ")
        return deliver(
            mid,
            f"reply:{mid}",
            {
                "to": m["sender"],
                "in_reply_to": mid,
                "subject": "Re: " + m["subject"],
                "body": payload.body,
            },
        )

    @app.post("/api/v1/messages/{mid}/book")
    def book(mid: int):
        m = message(mid)
        with db.connect() as conn:
            sent = conn.execute(
                "SELECT state FROM deliveries WHERE action=?", (f"quote:{mid}",)
            ).fetchone()
        if not sent or sent[0] != "sent":
            raise HTTPException(
                409, "Send the quote and confirm customer acceptance before booking"
            )
        if m["booking"]:
            return json.loads(m["booking"])
        offer = json.loads(m["proposal"])["offer"]
        if not offer["rate_id"]:
            raise HTTPException(
                409, "Sheet and bid offers must be booked with the carrier"
            )
        try:
            r = api.client.post(
                "/v1/portal/bookings",
                json={"rate_id": offer["rate_id"], "team_reference": f"AEIOU-{mid}"},
            )
            r.raise_for_status()
            result = r.json()
        except httpx.HTTPStatusError as exc:
            raise HTTPException(
                502,
                f"Booking returned HTTP {exc.response.status_code}. Check rate validity and carrier availability.",
            )
        except (httpx.HTTPError, ValueError):
            raise HTTPException(
                502, "Booking unconfirmed. Retry with the same rate and team reference."
            )
        with db.connect() as conn:
            conn.execute(
                "UPDATE mail SET booking=? WHERE id=?", (json.dumps(result), mid)
            )
        return result

    @app.get("/api/v1/messages/{mid}/attachments/{aid}")
    def attachment(mid: int, aid: int):
        m = message(mid)
        if not any(str(a.get("id")) == str(aid) for a in json.loads(m["attachments"])):
            raise HTTPException(404, "Attachment not found")
        try:
            r = api.client.get(f"/v1/mailbox/messages/{mid}/attachments/{aid}")
            r.raise_for_status()
        except httpx.HTTPError:
            raise HTTPException(502, "Attachment download failed")
        return Response(
            r.content,
            media_type="application/octet-stream",
            headers={"Content-Disposition": "attachment"},
        )

    return app
