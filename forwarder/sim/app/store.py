"""In-memory state of the simulator: mail, portal rates, bookings, rounds and rate limits."""
from __future__ import annotations

import secrets
import string
import time
from dataclasses import asdict, dataclass, field
from datetime import datetime, timedelta, timezone

ALNUM = string.ascii_letters + string.digits


def now() -> datetime:
    return datetime.now(timezone.utc)


def iso(ts: datetime) -> str:
    return ts.isoformat()


def new_rate_id() -> str:
    return "R-" + "".join(secrets.choice(ALNUM + "_-") for _ in range(16))


def new_booking_ref() -> str:
    return "BK-" + "".join(secrets.choice(string.ascii_uppercase + string.digits) for _ in range(8))


@dataclass
class Attachment:
    id: int
    filename: str
    content_type: str
    content: bytes

    def listing(self) -> dict:
        return {"id": self.id, "filename": self.filename, "content_type": self.content_type, "size": len(self.content)}


@dataclass
class Mail:
    id: int
    sender: str
    to: str
    subject: str
    body: str
    received_at: datetime
    in_reply_to: int | None = None
    references: list[int] = field(default_factory=list)
    attachments: list[Attachment] = field(default_factory=list)

    def item(self) -> dict:
        """Shape of a 5U poll item."""
        return {"id": self.id, "from": self.sender, "to": self.to, "subject": self.subject, "body": self.body,
                "received_at": iso(self.received_at), "in_reply_to": self.in_reply_to,
                "references": list(self.references), "attachments": [a.listing() for a in self.attachments]}

    @property
    def thread_root(self) -> int:
        return self.references[0] if self.references else self.id


@dataclass
class Rate:
    rate_id: str
    airline: str
    airline_code: str
    origin: str
    destination: str
    weight_kg: float
    chargeable_kg: float
    total: float
    currency: str
    transit_days: int
    valid_until: datetime
    source: str  # portal | bid | ratedesk


@dataclass
class Booking:
    booking_reference: str
    team_reference: str
    rate_id: str
    airline: str
    airline_code: str
    origin: str
    destination: str
    price: float
    currency: str
    booked_at: datetime
    round_no: int | None = None


@dataclass
class Round:
    no: int
    rfq_mail_id: int
    customer: str
    sent_at: datetime
    deadline: datetime
    spec: dict
    truth: dict | None
    summary: str
    verdict: str = "open"  # open | accepted | rejected | no answer | voided
    reason: str | None = None
    answered_at: datetime | None = None
    quote_mail_id: int | None = None
    quote_total: float | None = None
    quote_currency: str | None = None
    points: dict = field(default_factory=dict)
    booked: bool | None = None
    booking_reference: str | None = None
    customer_state: str = "waiting"  # waiting | book_requested | declined | asked
    tracking: dict | None = None
    notes: list[str] = field(default_factory=list)

    @property
    def response_s(self) -> float | None:
        return (self.answered_at - self.sent_at).total_seconds() if self.answered_at else None

    def total_points(self) -> float:
        return round(sum(self.points.values()), 2)

    def board_row(self) -> dict:
        return {"round_no": self.no, "sent_at": iso(self.sent_at),
                "answered_at": iso(self.answered_at) if self.answered_at else None,
                "verdict": self.verdict, "reason": self.reason, "booked": self.booked}

    def view(self) -> dict:
        d = asdict(self)
        for k in ("sent_at", "deadline", "answered_at"):
            d[k] = iso(d[k]) if d[k] else None
        best = self.truth["total"] if self.truth else None
        d["best"] = best
        d["delta_pct"] = round((self.quote_total / best - 1) * 100, 2) if best and self.quote_total else None
        d["response_s"] = self.response_s
        d["total_points"] = self.total_points()
        return d


class FixedWindow:
    """N calls per fixed one-minute window, like the 5U portal."""

    def __init__(self, limit: int, window_s: float = 60.0):
        self.limit, self.window_s = limit, window_s
        self.start, self.count = time.monotonic(), 0

    def take(self) -> tuple[bool, int, int]:
        """(allowed, remaining, seconds until the window resets)."""
        t = time.monotonic()
        if t - self.start >= self.window_s:
            self.start, self.count = t, 0
        reset = max(1, int(self.window_s - (t - self.start)) + 1)
        if self.count >= self.limit:
            return False, 0, reset
        self.count += 1
        return True, self.limit - self.count, reset


class Store:
    def __init__(self, mailbox: str, rate_limit: int, mail_limit: int):
        self.mailbox = mailbox
        self.mails: dict[int, Mail] = {}
        # Ids keep growing across simulator restarts (time based), like the real mailbox: a backend that kept its
        # since_id from an earlier run must still see every new mail.
        self._next_mail = max(1, (int(time.time()) - 1_790_000_000) * 100)
        self._next_attachment = 1
        self.rates: dict[str, Rate] = {}
        self.bookings: dict[str, Booking] = {}  # by rate_id
        self.rounds: list[Round] = []
        self.customers: set[str] = set()
        self.rate_window = FixedWindow(rate_limit)
        self.mail_window = FixedWindow(mail_limit)
        self.openai_key_last4 = "sim0"

    def add_mail(self, sender: str, to: str, subject: str, body: str, in_reply_to: int | None = None,
                 attachments: list[tuple[str, str, bytes]] | None = None) -> Mail:
        parent = self.mails.get(in_reply_to) if in_reply_to else None
        refs = (parent.references + [parent.id]) if parent else []
        atts = []
        for filename, ctype, content in attachments or []:
            atts.append(Attachment(self._next_attachment, filename, ctype, content))
            self._next_attachment += 1
        mail = Mail(self._next_mail, sender, to, subject, body, now(), in_reply_to, refs, atts)
        self.mails[mail.id] = mail
        self._next_mail += 1
        return mail

    def inbox(self, since_id: int, limit: int) -> list[Mail]:
        items = [m for m in self.mails.values() if m.to == self.mailbox and m.id > since_id]
        return sorted(items, key=lambda m: m.id)[:limit]

    def round_for_thread(self, root_id: int) -> Round | None:
        return next((r for r in self.rounds if r.rfq_mail_id == root_id), None)

    def add_rate(self, rate: Rate) -> Rate:
        self.rates[rate.rate_id] = rate
        return rate

    def valid_until(self, minutes: int = 15) -> datetime:
        return now() + timedelta(minutes=minutes)
