"""sim5u: a local stand-in for the 5U hackathon API (mailbox, rate portal, gateway key, board), driven by
simulated customers, carriers and desks. Paths and payloads follow forwarder/docs/5u-openapi.json and the
shapes recorded from the live API, so the backend runs against it unchanged; only FIVEU_BASE_URL differs."""
from __future__ import annotations

import asyncio
import base64
import json
import logging
import random
import re
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import Depends, FastAPI, Header, Query, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import FileResponse, JSONResponse, Response
from pydantic import BaseModel, Field

from .agents import RATEDESK, TRACKING, Simulator
from .config import settings
from .store import Store, iso, new_rate_id, now
from .store import Rate as RateRecord
from .world import World, chargeable_kg

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")

ALLOWED_ATTACHMENT = re.compile(r"\.(pdf|xlsx|xls|csv|txt|png|jpg|docx)$", re.I)

world = World(settings.start_kit, settings.fixtures, settings.seed)
store = Store(settings.mailbox, settings.rate_limit_per_min, settings.mail_limit_per_min)
history = json.loads((settings.fixtures / "mail-history.json").read_text())["messages"]
sim = Simulator(world, store, settings, history)
chaos_random = random.Random(settings.seed + 1)


@asynccontextmanager
async def lifespan(_: FastAPI):
    tasks = []
    if settings.agents:
        tasks = [asyncio.create_task(sim.customer_loop()), asyncio.create_task(sim.expiry_loop())]
    yield
    for t in tasks:
        t.cancel()


app = FastAPI(title="sim5u", description="Simulated 5U hackathon API", lifespan=lifespan)


def error(status: int, title: str, detail: str, path: str, headers: dict | None = None, errors: list | None = None):
    body = {"type": f"/errors/{title.lower().replace(' ', '-')}", "title": title, "status": status,
            "detail": detail, "instance": path}
    if errors is not None:
        body["errors"] = errors
    return JSONResponse(body, status_code=status, headers=headers)


@app.exception_handler(RequestValidationError)
async def validation_error(request: Request, exc: RequestValidationError):
    errors = [{"field": ".".join(str(p) for p in e["loc"]), "message": e["msg"], "type": e["type"]} for e in exc.errors()]
    return error(422, "Validation Error", "One or more validation errors occurred", request.url.path, errors=errors)


def team_key(x_api_key: str | None = Header(default=None), authorization: str | None = Header(default=None)) -> str:
    key = x_api_key or (authorization[7:] if authorization and authorization.lower().startswith("bearer ") else None)
    if not key:
        raise AuthMissing()
    return key


class AuthMissing(Exception):
    pass


@app.exception_handler(AuthMissing)
async def auth_missing(request: Request, _: AuthMissing):
    return error(401, "Unauthorized", "Send your team key as X-API-Key", request.url.path)


@app.get("/health")
async def health():
    return {"status": "ok", "simulator": True}


@app.get("/v1/me")
async def me(_: str = Depends(team_key)):
    return {"team": settings.team, "display_name": settings.team.upper(), "mailbox": settings.mailbox}


# ---- mailbox -------------------------------------------------------------------------------------------

@app.get("/v1/mailbox/messages")
async def poll(since_id: int = Query(0, ge=0), limit: int = Query(100, ge=1, le=200), _: str = Depends(team_key)):
    mails = store.inbox(since_id, limit)
    return {"messages": [m.item() for m in mails], "next_since_id": mails[-1].id if mails else since_id}


@app.get("/v1/mailbox/messages/{message_id}")
async def read_one(message_id: int, request: Request, _: str = Depends(team_key)):
    mail = store.mails.get(message_id)
    if mail is None:
        return error(404, "Not Found", "Message not found", request.url.path)
    return mail.item()


@app.get("/v1/mailbox/messages/{message_id}/attachments/{attachment_id}")
async def attachment(message_id: int, attachment_id: int, request: Request, _: str = Depends(team_key)):
    mail = store.mails.get(message_id)
    att = next((a for a in mail.attachments if a.id == attachment_id), None) if mail else None
    if att is None:
        return error(404, "Not Found", "Attachment not found", request.url.path)
    return Response(att.content, media_type=att.content_type,
                    headers={"Content-Disposition": f'attachment; filename="{att.filename}"'})


class AttachmentIn(BaseModel):
    filename: str = Field(min_length=1, max_length=200)
    content_type: str = Field("application/octet-stream", max_length=200)
    content_base64: str = Field(max_length=14_000_000)


class MessageIn(BaseModel):
    to: str
    subject: str = Field("", max_length=1000)
    body: str = Field("", max_length=200_000)
    in_reply_to: int | None = None
    attachments: list[AttachmentIn] = Field(default_factory=list, max_length=5)


def allowed_recipient(to: str) -> bool:
    to = to.strip().lower()
    return to in (RATEDESK, TRACKING) or to in store.customers or world.carrier(to) is not None


@app.post("/v1/mailbox/messages", status_code=201)
async def send(message: MessageIn, request: Request, _: str = Depends(team_key)):
    path = request.url.path
    if not allowed_recipient(message.to):
        return error(422, "Validation Error", f"You cannot write to {message.to}", path,
                     errors=[{"field": "body.to", "message": "not a customer, carrier or desk address", "type": "value_error"}])
    if message.in_reply_to is not None and message.in_reply_to not in store.mails:
        return error(404, "Not Found", "in_reply_to is not a mail you sent or received", path)
    files = []
    for a in message.attachments:
        if not ALLOWED_ATTACHMENT.search(a.filename):
            return error(422, "Validation Error", f"File type not allowed: {a.filename}", path, errors=[])
        files.append((a.filename, a.content_type, base64.b64decode(a.content_base64)))
    ok, _, reset = store.mail_window.take()
    if not ok:
        return error(429, "Too Many Requests", "60 mails per minute per team key", path, headers={"Retry-After": str(reset)})
    mail = store.add_mail(settings.mailbox, message.to.strip().lower(), message.subject, message.body,
                          message.in_reply_to, files)
    sim.on_team_mail(mail)
    return mail.item()


# ---- rate portal ---------------------------------------------------------------------------------------

@app.get("/v1/portal/ports")
async def ports(_: str = Depends(team_key)):
    return {"lanes": [{"origin": o, "destination": d, "airlines": [{"code": c.code, "name": c.name} for c in cs]}
                      for o, d, cs in world.portal_lanes()]}


@app.get("/v1/portal/brackets")
async def brackets(request: Request, airline: str = Query(min_length=1, max_length=80), _: str = Depends(team_key)):
    carrier = world.airline(airline)
    if carrier is None or world.kind.get(carrier.name) != "portal":
        email = carrier.email if carrier else "the airline (see the carrier list)"
        return error(404, "Not Found", f"{airline} is not on the portal. Email {email} instead.", request.url.path)
    breaks = world.lanes_of(carrier.name)[0].breaks
    return {"airline": carrier.name, "airline_code": carrier.code, "breaks_kg": [b for b, _ in breaks]}


@app.get("/v1/portal/rates")
async def rates(request: Request, origin: str = Query(pattern=r"^[A-Za-z]{3}$"), destination: str = Query(pattern=r"^[A-Za-z]{3}$"),
          airline: str = Query(min_length=1, max_length=80), weight_kg: float = Query(gt=0, le=100000),
          volume_cbm: float = Query(0, ge=0, le=1000), _: str = Depends(team_key)):
    path = request.url.path
    ok, remaining, reset = store.rate_window.take()
    headers = {"x-ratelimit-limit": str(store.rate_window.limit), "x-ratelimit-remaining": str(remaining)}
    if not ok:
        return error(429, "Too Many Requests", "Rate read limit reached", path, headers={**headers, "Retry-After": str(reset)})
    if chaos_random.random() < settings.portal_503_share:
        return error(503, "Service Unavailable",
                     f"The rate portal is unavailable. Email {RATEDESK} or the airline for a rate.", path, headers=headers)
    origin, destination = origin.upper(), destination.upper()
    carrier = world.airline(airline)
    lane = world.lanes.get((carrier.name, origin, destination)) if carrier and world.kind.get(carrier.name) == "portal" else None
    if lane is None:
        return error(404, "Not Found", f"No portal rate for {airline} on {origin}-{destination}. "
                     "Destinations missing on the portal are priced by email.", path, headers=headers)
    chargeable = chargeable_kg(weight_kg, volume_cbm)
    total, _ = lane.price(chargeable, higher_breaks=False)  # the portal prices the weight you ask for
    rate = store.add_rate(RateRecord(new_rate_id(), carrier.name, carrier.code, origin, destination, weight_kg,
                                     chargeable, total, "EUR", lane.transit_days, store.valid_until(), "portal"))
    body = {"rate_id": rate.rate_id, "airline": carrier.name, "airline_code": carrier.code, "origin": origin,
            "destination": destination, "weight_kg": _num(weight_kg), "chargeable_weight_kg": _num(chargeable),
            "total": f"{total:.2f}", "currency": "EUR", "transit_days": lane.transit_days,
            "valid_until": iso(rate.valid_until), "dataset_version": "sim-v1"}
    return JSONResponse(body, headers=headers)


class BookingIn(BaseModel):
    rate_id: str = Field(pattern=r"^R-[A-Za-z0-9_-]{8,40}$")
    team_reference: str = Field(min_length=1, max_length=128)


@app.post("/v1/portal/bookings", status_code=201)
async def create_booking(payload: BookingIn, request: Request, _: str = Depends(team_key)):
    path = request.url.path
    rate = store.rates.get(payload.rate_id)
    if rate is None:
        return error(404, "Not Found", "unknown rate_id", path)
    existing = store.bookings.get(rate.rate_id)
    if existing:
        if existing.team_reference == payload.team_reference:
            return JSONResponse(_booking_out(existing), status_code=200)
        return error(409, "Conflict", "rate_id already booked under another team_reference", path)
    if now() > rate.valid_until:
        return error(410, "Gone", "rate expired, fetch a fresh rate", path)
    if chaos_random.random() < settings.booking_503_share:
        return error(503, "Service Unavailable", "The booking system is unavailable. Book by email with the airline.", path)
    return _booking_out(sim.book(rate, payload.team_reference))


def _booking_out(b) -> dict:
    return {"booking_reference": b.booking_reference, "team_reference": b.team_reference, "rate_id": b.rate_id,
            "airline": b.airline, "airline_code": b.airline_code, "price": f"{b.price:.2f}", "currency": b.currency,
            "status": "booked"}


def _num(value: float) -> str:
    return f"{value:.2f}".rstrip("0").rstrip(".")


# ---- gateway key, board ----------------------------------------------------------------------------------

@app.get("/v1/gateway/openai-key")
async def get_key(_: str = Depends(team_key)):
    return {"set": True, "last4": store.openai_key_last4, "checked_at": iso(now())}


class KeyIn(BaseModel):
    api_key: str


@app.post("/v1/gateway/openai-key")
async def post_key(payload: KeyIn, _: str = Depends(team_key)):
    store.openai_key_last4 = payload.api_key[-4:]  # simulated; the key itself is dropped
    return {"set": True, "last4": store.openai_key_last4, "checked_at": iso(now())}


@app.get("/v1/board/me")
async def board_me(_: str = Depends(team_key)):
    rows = sorted(store.rounds, key=lambda r: r.no, reverse=True)
    return {"team": settings.team, "rank": 1, "total": round(sum(r.total_points() for r in store.rounds), 2),
            "frozen_at": None, "scores_revealed": True, "requests": [r.board_row() for r in rows],
            "bookings": [{"booking_reference": b.booking_reference, "team_reference": b.team_reference,
                          "airline": b.airline, "origin": b.origin, "destination": b.destination,
                          "booked_at": iso(b.booked_at)} for b in store.bookings.values()]}


@app.get("/v1/board")
async def board():
    return {"frozen_at": None, "teams": [{"rank": 1, "team": settings.team,
                                          "total": round(sum(r.total_points() for r in store.rounds), 2),
                                          "accepted": sum(r.verdict == "accepted" for r in store.rounds),
                                          "answered": sum(r.answered_at is not None for r in store.rounds)}],
            "bookings": [{"team": settings.team, "airline": b.airline, "lane": f"{b.origin}-{b.destination}"}
                         for b in store.bookings.values()],
            "rounds": [r.board_row() for r in store.rounds[-10:]]}


# ---- simulator controls and UI -----------------------------------------------------------------------------

@app.get("/")
async def ui():
    return FileResponse(Path(__file__).parent / "static" / "index.html")


@app.get("/sim/state")
async def state():
    rounds = [r.view() for r in sorted(store.rounds, key=lambda r: r.no, reverse=True)]
    decided = [r for r in store.rounds if r.verdict not in ("open", "voided")]
    mails = sorted(store.mails.values(), key=lambda m: m.id, reverse=True)[:60]
    return {
        "totals": {"rounds": len(store.rounds), "accepted": sum(r.verdict == "accepted" for r in store.rounds),
                   "rejected": sum(r.verdict == "rejected" for r in store.rounds),
                   "no_answer": sum(r.verdict == "no answer" for r in store.rounds),
                   "points": round(sum(r.total_points() for r in store.rounds), 2),
                   "bookings": len(store.bookings),
                   "avg_response_s": round(sum(r.response_s for r in decided if r.response_s) /
                                           max(1, sum(1 for r in decided if r.response_s)), 1)},
        "chaos": {"portal_503_share": settings.portal_503_share, "booking_503_share": settings.booking_503_share,
                  "rate_limit": store.rate_window.limit, "alt_phrasing": settings.alt_phrasing,
                  "carrier_delay_s": [settings.carrier_delay_min_s, settings.carrier_delay_max_s],
                  "rfq_interval_s": settings.rfq_interval_s},
        "rounds": rounds,
        "mails": [{"id": m.id, "from": m.sender, "to": m.to, "subject": m.subject, "at": iso(m.received_at),
                   "in_reply_to": m.in_reply_to, "attachments": len(m.attachments)} for m in mails],
    }


@app.get("/sim/mails/{message_id}")
async def sim_mail(message_id: int):
    mail = store.mails.get(message_id)
    return mail.item() if mail else JSONResponse({"detail": "not found"}, status_code=404)


@app.get("/sim/dump")
async def dump():
    return {"state": await state(), "all_mails": [m.item() for m in store.mails.values()],
            "rates": [{**r.__dict__, "valid_until": iso(r.valid_until)} for r in store.rates.values()]}


class RfqIn(BaseModel):
    history: bool | None = None


@app.post("/sim/rfq")
async def trigger_rfq(payload: RfqIn | None = None):
    return sim.new_rfq(payload.history if payload else None).view()


class TrackingIn(BaseModel):
    round_no: int | None = None


@app.post("/sim/tracking")
async def trigger_tracking(payload: TrackingIn | None = None, request: Request = None):
    rnd = sim.ask_tracking_now(payload.round_no if payload else None)
    if rnd is None:
        return error(404, "Not Found", "No booked round to ask about", request.url.path)
    return rnd.view()


class ChaosIn(BaseModel):
    portal_503_share: float | None = Field(None, ge=0, le=1)
    booking_503_share: float | None = Field(None, ge=0, le=1)
    rate_limit: int | None = Field(None, ge=1)
    alt_phrasing: bool | None = None
    carrier_delay_min_s: float | None = Field(None, ge=0)
    carrier_delay_max_s: float | None = Field(None, ge=0)
    rfq_interval_s: float | None = Field(None, ge=5)


@app.post("/sim/chaos")
async def set_chaos(payload: ChaosIn):
    for key, value in payload.model_dump(exclude_none=True).items():
        if key == "rate_limit":
            store.rate_window.limit = value
        else:
            setattr(settings, key, value)
    return (await state())["chaos"]


class TariffIn(BaseModel):
    airline: str = "Yellowtail Air Freight"
    pct: float = 8.0


@app.post("/sim/chaos/tariff")
async def chaos_tariff(payload: TariffIn, request: Request):
    try:
        mail = sim.send_tariff_update(payload.airline, payload.pct)
    except ValueError as e:
        return error(422, "Validation Error", str(e), request.url.path, errors=[])
    return mail.item()
