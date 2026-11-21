"""The simulated 5U agents: customers sending RFQs and reacting to quotes, carriers and truckers answering
rate requests, the rate desk, the tracking desk, and the scorer that turns each round into a board row."""
from __future__ import annotations

import asyncio
import logging
import random
import re
from datetime import timedelta

from . import texts
from .config import Settings
from .scoring import score
from .store import Booking, Mail, Rate, Round, Store, iso, new_booking_ref, new_rate_id, now
from .world import CITY, ORIGINS, World, chargeable_kg

log = logging.getLogger("sim5u")

RATEDESK = "ratedesk@hackloop.mail"
TRACKING = "tracking@hackloop.mail"
STATUS_WORDS = re.compile(r"\b(booked|confirmed|in transit|departed|scheduled|on its way|arriv|delivered|gebucht|unterwegs)", re.I)

# RFQs from the colleague's real mail history (fixtures/mail-history.json), re-issued with fresh dates.
HISTORY_RFQS = {
    263: dict(origin="CGN", destination="ORD", zip="51447", town="Bergisch Gladbach", door=True, mode="fastest", weight_kg=676, volume_cbm=5.79),
    419: dict(origin="FRA", destination="JFK", zip="61018", town="Bad Homburg", door=True, mode="fastest", weight_kg=1233, volume_cbm=10.78),
    729: dict(origin="DUS", destination="JFK", zip=None, town=None, door=False, mode="fastest", weight_kg=1540, volume_cbm=5.90),
}


class Simulator:
    def __init__(self, world: World, store: Store, settings: Settings, history: list[dict]):
        self.world, self.store, self.settings = world, store, settings
        self.history = {m["id"]: m for m in history}
        self.random = random.Random(settings.seed)
        self._tasks: set[asyncio.Task] = set()

    # ---- scheduling ------------------------------------------------------------------------------
    def later(self, delay_s: float, fn, *args) -> None:
        async def run():
            await asyncio.sleep(delay_s)
            try:
                fn(*args)
            except Exception:  # a broken agent action must never take the simulator down
                log.exception("agent action failed")
        task = asyncio.get_running_loop().create_task(run())  # all endpoints are async: we are on the loop
        self._tasks.add(task)
        task.add_done_callback(self._tasks.discard)

    async def customer_loop(self) -> None:
        await asyncio.sleep(self.settings.first_rfq_delay_s)
        while True:
            self.new_rfq()
            await asyncio.sleep(self.settings.rfq_interval_s)

    async def expiry_loop(self) -> None:
        while True:
            await asyncio.sleep(1)
            self.expire_rounds()

    # ---- customer agent --------------------------------------------------------------------------
    def new_rfq(self, use_history: bool | None = None) -> Round:
        r = self.random
        if use_history is None:
            use_history = r.random() < self.settings.history_share
        if use_history:
            mail_id = r.choice(sorted(HISTORY_RFQS))
            spec = dict(HISTORY_RFQS[mail_id], language="en")
            original = self.history[mail_id]
            ready = (now() + timedelta(days=r.randint(3, 6))).date().isoformat()
            body = re.sub(r"2026-\d\d-\d\d", ready, original["body"])
            sender, subject = original["from"], original["subject"]
            spec["ready"] = ready
        else:
            spec = self._generated_spec(r)
            company = r.choice(texts.COMPANIES)
            subject, body = texts.rfq_mail(spec, company, r)
            sender = f"{company[1]}@hackloop.mail"
        zone = spec["zip"][:2] if spec.get("door") and spec.get("zip") else None
        chargeable = chargeable_kg(spec["weight_kg"], spec["volume_cbm"])
        truth = self.world.truth(spec["origin"], spec["destination"], chargeable, spec["mode"], zone)
        self.store.customers.add(sender)
        mail = self.store.add_mail(sender, self.store.mailbox, subject, body)
        summary = (f"{spec['origin']}→{spec['destination']} {spec['weight_kg']} kg/{spec['volume_cbm']} cbm, {spec['mode']}"
                   + (f", pickup {spec['zip']}" if spec.get("door") else ""))
        rnd = Round(len(self.store.rounds) + 1, mail.id, sender, mail.received_at,
                    mail.received_at + timedelta(seconds=self.settings.rfq_lifetime_s), spec,
                    _truth_view(truth), summary, verdict="open" if truth else "voided",
                    reason=None if truth else "no_reference_price")
        self.store.rounds.append(rnd)
        log.info("round %s: %s (best %s)", rnd.no, summary, truth.total if truth else None)
        return rnd

    def _generated_spec(self, r: random.Random) -> dict:
        lanes = sorted({(o, d) for (_, o, d) in self.world.lanes})
        origin, dest = r.choice(lanes)
        door_zones = [z for z, (_, airport) in self.world.zones.items()
                      if airport == origin and self.world.truckers_for_zone(z)]
        door = bool(door_zones) and r.random() < 0.5
        zone = r.choice(door_zones) if door else None
        weight = r.randint(50, 3000)
        volume = round(weight / 167 * r.uniform(0.35, 1.7), 2)  # sometimes volume-heavy
        return dict(origin=origin, destination=dest, door=door,
                    zip=f"{zone}{r.randint(0, 999):03d}" if zone else None,
                    town=self.world.zones[zone][0] if zone else None,
                    mode=r.choice(["cheapest", "fastest"]), weight_kg=weight, volume_cbm=volume,
                    pieces=r.randint(1, 20), commodity=r.randrange(len(texts.COMMODITIES)),
                    language="de" if r.random() < 0.3 else "en",
                    ready=(now() + timedelta(days=r.randint(3, 7))).date().isoformat(),
                    dest_city=CITY.get(dest, (dest, "")))

    def expire_rounds(self) -> None:
        t = now()
        for rnd in self.store.rounds:
            if rnd.verdict == "open" and t > rnd.deadline:
                rnd.verdict = "no answer"
                rnd.reason = rnd.reason or "no_quote"

    def _on_customer_mail(self, mail: Mail) -> None:
        rnd = self.store.round_for_thread(mail.thread_root)
        if rnd is None:
            return
        if rnd.quote_mail_id is not None or rnd.verdict == "voided":
            rnd.notes.append(f"later reply #{mail.id}: {mail.subject}")
            return
        response_s = (mail.received_at - rnd.sent_at).total_seconds()
        result = score(mail.body, rnd.truth["total"] if rnd.truth else None, response_s)
        if result.reason == "no_total":  # not a quote: only the first mail with an amount counts
            rnd.reason = "no_total"
            rnd.notes.append(f"reply #{mail.id} without a total")
            return
        rnd.quote_mail_id, rnd.answered_at = mail.id, mail.received_at
        rnd.quote_total, rnd.quote_currency = result.total, result.currency
        rnd.verdict, rnd.reason, rnd.points = result.verdict, result.reason, result.points
        log.info("round %s: quoted %s %s vs best %s -> %s %s", rnd.no, result.total, result.currency,
                 rnd.truth and rnd.truth["total"], result.verdict, result.reason or "")
        self._customer_reaction(rnd, mail)

    def _customer_reaction(self, rnd: Round, quote: Mail) -> None:
        r = self.random
        if rnd.verdict == "accepted":
            roll = r.random()
            if roll < self.settings.accept_book_prob:
                rnd.customer_state, rnd.booked = "book_requested", False
                self.later(r.uniform(8, 25), self._customer_says, quote, r.choice(texts.ACCEPT))
            elif roll < self.settings.accept_book_prob + self.settings.follow_up_prob:
                rnd.customer_state = "asked"
                self.later(r.uniform(8, 25), self._customer_says, quote, r.choice(texts.FOLLOW_UP))
        elif rnd.reason == "too_high":
            rnd.customer_state = "declined"
            self.later(r.uniform(8, 20), self._customer_says, quote, r.choice(texts.REJECT))

    def _customer_says(self, quote: Mail, text: str) -> None:
        self.store.add_mail(quote.to, self.store.mailbox, _re(quote.subject), text, in_reply_to=quote.id)

    # ---- tracking desk ---------------------------------------------------------------------------
    def _ask_tracking(self, rnd: Round, booking: Booking) -> None:
        subject, body = texts.tracking_question(booking.booking_reference, booking.team_reference, self.random)
        mail = self.store.add_mail(TRACKING, self.store.mailbox, subject, body)
        rnd.tracking = {"question_mail_id": mail.id, "booking_reference": booking.booking_reference,
                        "asked_at": iso(mail.received_at), "answered": False, "correct": None}

    def ask_tracking_now(self, round_no: int | None = None) -> Round | None:
        """Sim control: ask the tracking question for a booked round right away."""
        booked = [r for r in self.store.rounds if r.booking_reference and (round_no is None or r.no == round_no)]
        if not booked:
            return None
        rnd = booked[-1]
        booking = next(b for b in self.store.bookings.values() if b.booking_reference == rnd.booking_reference)
        self._ask_tracking(rnd, booking)
        return rnd

    def _on_tracking_answer(self, mail: Mail) -> None:
        for rnd in self.store.rounds:
            t = rnd.tracking
            if t and mail.in_reply_to == t["question_mail_id"] and not t["answered"]:
                correct = t["booking_reference"] in mail.body and bool(STATUS_WORDS.search(mail.body))
                t.update(answered=True, correct=correct, answered_at=iso(mail.received_at))
                if correct:
                    rnd.points["tracking"] = 10.0
                return

    # ---- carriers, truckers, rate desk -----------------------------------------------------------
    def on_team_mail(self, mail: Mail) -> None:
        """Every mail our forwarder sends lands here."""
        to = mail.to.lower()
        if to == TRACKING:
            self._on_tracking_answer(mail)
        elif to == RATEDESK:
            self.later(self._carrier_delay(), self._ratedesk_reply, mail)
        elif self.world.carrier(to):
            self.later(self._carrier_delay(), self._carrier_reply, mail)
        elif to in self.store.customers:
            self._on_customer_mail(mail)

    def _carrier_delay(self) -> float:
        return self.random.uniform(self.settings.carrier_delay_min_s, self.settings.carrier_delay_max_s)

    def _reply(self, original: Mail, sender: str, body: str, subject: str | None = None) -> Mail:
        return self.store.add_mail(sender, self.store.mailbox, subject or _re(original.subject), body,
                                   in_reply_to=original.id)

    def _carrier_reply(self, mail: Mail) -> None:
        carrier = self.world.carrier(mail.to)
        req = texts.parse_request(mail.subject, mail.body, set(ORIGINS), self.world.destinations())
        valid = (now() + timedelta(minutes=15)).strftime("%d %b %Y %H:%M UTC")
        if req.booking and (rate := self._rate_in_thread(mail, req, carrier.name)):
            team_ref = (re.search(r"\bAEI-[A-Z0-9]{5}\b", mail.body) or re.search(r"\bAEI\w*", mail.body))
            booking = self.book(rate, team_ref.group(0) if team_ref else f"mail-{mail.id}", confirm=False)
            self._reply(mail, carrier.email, texts.booking_confirmed(carrier.name, booking.booking_reference,
                        rate.origin, rate.destination, rate.rate_id, rate.total, booking.team_reference),
                        subject=f"Booking {booking.booking_reference} confirmed")
            return
        if req.counteroffer and (rate := self._rate_in_thread(mail, req, carrier.name)):
            self._reply(mail, carrier.email, texts.counter_refusal(carrier.name, rate.total, rate.transit_days, valid,
                                                                    rate.rate_id))
            return
        if carrier.kind == "trucker":
            self._trucker_reply(mail, carrier, req, valid)
        else:
            self._airline_reply(mail, carrier, req, valid)

    def _airline_reply(self, mail: Mail, carrier, req: texts.Request, valid: str) -> None:
        if not (req.origin and req.destination and (req.weight_kg or req.volume_cbm)):
            self._reply(mail, carrier.email, texts.ask_details(carrier.name))
            return
        lane = self.world.lanes.get((carrier.name, req.origin, req.destination))
        if lane is None:
            lanes = [l.lane for l in self.world.lanes_of(carrier.name)]
            self._reply(mail, carrier.email, texts.airline_decline(carrier.name, carrier.email, req.origin,
                                                                   req.destination, lanes))
            return
        chargeable = chargeable_kg(req.weight_kg or 0, req.volume_cbm or 0)
        total, _ = lane.price(chargeable, higher_breaks=False)  # carriers price in the shipment's own bracket
        rate = self.store.add_rate(Rate(new_rate_id(), carrier.name, carrier.code, req.origin, req.destination,
                                        req.weight_kg or chargeable, chargeable, total, "EUR", lane.transit_days,
                                        self.store.valid_until(), "bid"))
        self._reply(mail, carrier.email, texts.airline_offer(carrier.name, req.origin, req.destination, total,
                                                             lane.transit_days, valid, rate.rate_id, self.random,
                                                             self.settings.alt_phrasing))

    def _trucker_reply(self, mail: Mail, carrier, req: texts.Request, valid: str) -> None:
        zone = req.zip[:2] if req.zip else (m.group(1) if (m := re.search(r"zone\s+(\d{2})", mail.subject + mail.body, re.I)) else None)
        if zone is None:
            self._reply(mail, carrier.email, texts.ask_details(carrier.name))
            return
        if zone not in carrier.serves:
            self._reply(mail, carrier.email, texts.trucker_decline(carrier.name, carrier.email, zone, carrier.serves))
            return
        area, airport = self.world.zones.get(zone, (zone, req.origin or "the airport"))
        chargeable = chargeable_kg(req.weight_kg or 0, req.volume_cbm or 0)
        total, hours = self.world.truck_offer(carrier.name, zone, chargeable)
        ref = f"TRK-{self.random.randint(100, 999)}"
        self._reply(mail, carrier.email, texts.trucker_offer(carrier.name, area, airport, total, hours, valid, ref,
                                                             self.random, self.settings.alt_phrasing))

    def _ratedesk_reply(self, mail: Mail) -> None:
        req = texts.parse_request(mail.subject, mail.body, set(ORIGINS), self.world.destinations())
        if not (req.origin and req.destination and (req.weight_kg or req.volume_cbm)):
            self._reply(mail, RATEDESK, texts.ask_details("Rate Desk"))
            return
        chargeable = chargeable_kg(req.weight_kg or 0, req.volume_cbm or 0)
        offers = [(lane.price(chargeable, higher_breaks=False)[0], lane) for lane in
                  self.world.on_lane(req.origin, req.destination) if self.world.kind[lane.airline] != "portal"]
        if not offers:
            self._reply(mail, RATEDESK, texts.ratedesk_none(req.origin, req.destination))
            return
        total, lane = min(offers, key=lambda o: o[0])
        airline = self.world.airline(lane.airline)
        rate = self.store.add_rate(Rate(new_rate_id(), lane.airline, airline.code if airline else "XX", req.origin,
                                        req.destination, req.weight_kg or chargeable, chargeable, total, "EUR",
                                        lane.transit_days, self.store.valid_until(), "ratedesk"))
        valid = rate.valid_until.strftime("%d %b %Y %H:%M UTC")
        self._reply(mail, RATEDESK, texts.ratedesk_offer(lane.airline, req.origin, req.destination, total,
                                                         lane.transit_days, valid, rate.rate_id))

    def _rate_in_thread(self, mail: Mail, req: texts.Request, airline: str) -> Rate | None:
        ids = list(req.rate_ids)
        for mid in reversed(mail.references):
            m = self.store.mails.get(mid)
            if m:
                ids += re.findall(r"\bR-[A-Za-z0-9_-]{8,40}\b", m.body)
        return next((self.store.rates[i] for i in ids if i in self.store.rates and self.store.rates[i].airline == airline), None)

    # ---- bookings --------------------------------------------------------------------------------
    def book(self, rate: Rate, team_reference: str, confirm: bool = True) -> Booking:
        booking = Booking(new_booking_ref(), team_reference, rate.rate_id, rate.airline, rate.airline_code,
                          rate.origin, rate.destination, rate.total, rate.currency, now())
        self.store.bookings[rate.rate_id] = booking
        rnd = next((r for r in self.store.rounds if r.customer_state == "book_requested" and not r.booked
                    and r.spec["origin"] == rate.origin and r.spec["destination"] == rate.destination), None)
        if rnd:
            rnd.booked, rnd.booking_reference, booking.round_no = True, booking.booking_reference, rnd.no
            if self.random.random() < self.settings.tracking_prob:
                self.later(self.random.uniform(30, 90), self._ask_tracking, rnd, booking)
        if confirm:
            airline = self.world.airline(rate.airline)
            sender = airline.email if airline else f"{rate.airline.lower().replace(' ', '-')}@hackloop.mail"
            self.later(self.random.uniform(3, 8), lambda: self.store.add_mail(
                sender, self.store.mailbox, f"Booking {booking.booking_reference} confirmed",
                texts.booking_confirmed(rate.airline, booking.booking_reference, rate.origin, rate.destination,
                                        rate.rate_id, rate.total, team_reference)))
        return booking

    # ---- chaos -----------------------------------------------------------------------------------
    def send_tariff_update(self, airline: str, pct: float) -> Mail:
        """A new monthly tariff with changed rates, as a txt attachment in the start-kit text layout."""
        carrier = self.world.airline(airline)
        if carrier is None or self.world.kind.get(carrier.name) != "sheet":
            raise ValueError(f"{airline} is not a tariff-sheet airline")
        lanes = self.world.scale_sheet(carrier.name, 1 + pct / 100)
        sheet = tariff_text(carrier.name, lanes, now())
        slug = carrier.name.lower().replace(" ", "-")
        return self.store.add_mail(carrier.email, self.store.mailbox, f"{carrier.name} air export tariff update",
                                   f"Dear partner,\n\nplease find attached our updated air export tariff, valid from today.\n\n"
                                   f"Kind regards,\n{carrier.name}",
                                   attachments=[(f"{slug}-tariff-update.txt", "text/plain", sheet.encode())])


def tariff_text(airline: str, lanes, valid_from) -> str:
    breaks = lanes[0].breaks
    labels = [f"-{breaks[1][0]}" if i == 0 else f"+{start}" for i, (start, _) in enumerate(breaks)]
    lines = [f"{airline} | Air export tariff", "Currency    EUR", f"Valid from  {valid_from:%d.%m.%Y}",
             "Valid to    31.12.2026", "All rates are per kg of chargeable weight, in EUR.",
             "Chargeable weight is the higher of actual weight and volume weight (1 cbm = 167 kg).",
             "Fuel and security surcharges are charged per kg of the weight the freight is billed on.",
             "The minimum charge applies to the freight before surcharges.", "",
             "Lane        Transit     Minimum     " + "".join(f"{l:<10}" for l in labels).rstrip()]
    for lane in lanes:
        rates = "".join(f"{'EUR %.2f' % rate:<10}" for _, rate in lane.breaks).rstrip()
        lines.append(f"{lane.lane:<12}{lane.transit_days:>9}  {'EUR %.2f' % lane.minimum:<12}{rates}")
    lines += ["", "Surcharges per kg of billed weight"]
    lines += [f"{l.lane}: fuel EUR {l.fuel:.2f} per kg, security EUR {l.security:.2f} per kg" for l in lanes]
    return "\n".join(lines) + "\n"


def _re(subject: str) -> str:
    return subject if subject.lower().startswith("re:") else f"Re: {subject}"


def _truth_view(truth) -> dict | None:
    if truth is None:
        return None
    return {"total": truth.total, "airline": truth.airline, "transit_days": truth.transit_days, "truck": truth.truck,
            "trucker": truth.trucker,
            "options": [{"airline": o.airline, "kind": o.kind, "total": o.total, "transit_days": o.transit_days}
                        for o in truth.options[:6]]}
