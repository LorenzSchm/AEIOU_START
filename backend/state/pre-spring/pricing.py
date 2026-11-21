"""First-wave air export pricing. Only the final customer total is rounded."""

import csv
import re
from datetime import date
from decimal import ROUND_HALF_UP, Decimal
from pathlib import Path
from typing import Literal

from pydantic import BaseModel, Field, model_validator

ROOT = Path(__file__).resolve().parents[1]
KIT = ROOT / "start-kit"
D = Decimal


def read_csv(path):
    with path.open(encoding="utf-8-sig", newline="") as file:
        return list(csv.DictReader(file))


TARIFFS = read_csv(Path(__file__).parent / "data/tariffs-2026-11.csv")
CARRIERS = read_csv(KIT / "carrier-list.csv")
ZONES = {r["zone"]: r["airport"] for r in read_csv(KIT / "pickup_zones.csv")}
ORIGINS = set(ZONES.values())
DESTINATIONS = {
    code for c in CARRIERS if c["kind"] == "airline" for code in c["serves"].split()
}
CENT = D("0.01")


class Shipment(BaseModel):
    origin: str = Field(pattern=r"^[A-Z]{3}$")
    destination: str = Field(pattern=r"^[A-Z]{3}$")
    weight_kg: Decimal = Field(gt=0, le=100000, allow_inf_nan=False)
    volume_cbm: Decimal = Field(ge=0, le=1000, allow_inf_nan=False)
    preference: Literal["cheapest", "fastest"]
    pickup_zone: str | None = Field(default=None, pattern=r"^[0-9]{2}$")
    pickup_eur: Decimal | None = Field(default=None, ge=0, allow_inf_nan=False)
    pickup_days: int = Field(default=0, ge=0, le=90)

    @model_validator(mode="after")
    def check_lane(self):
        if self.origin not in ORIGINS or self.destination not in DESTINATIONS:
            raise ValueError("Check the airport codes against the carrier directory")
        if self.pickup_zone and ZONES.get(self.pickup_zone) != self.origin:
            raise ValueError(
                "Pickup zone and origin airport must match the pickup directory"
            )
        if not self.pickup_zone and (self.pickup_eur is not None or self.pickup_days):
            raise ValueError("Enter a pickup zone when adding a truck leg")
        return self


class Offer(BaseModel):
    airline: str
    days: int
    total_eur: Decimal
    billed_kg: Decimal
    source: Literal["sheet", "portal", "carrier bid"]
    rate_id: str | None = None
    valid_until: str | None = None


def money(value):
    return value.quantize(CENT, rounding=ROUND_HALF_UP)


def local_offers(s: Shipment, on_date: date):
    if (on_date.year, on_date.month) != (2026, 11):
        return []
    weight = max(s.weight_kg, s.volume_cbm * 167)
    offers = []
    for row in TARIFFS:
        if (row["origin"], row["destination"]) != (s.origin, s.destination):
            continue
        rates = {int(k): D(v) for k, v in row.items() if k.isdigit() and v}
        current = max(k for k in rates if k <= weight)
        # A higher break may give a lower all-in charge even after buying extra kg.
        candidates = [(weight, rates[current])] + [
            (D(k), v) for k, v in rates.items() if k > weight
        ]
        surcharge = D(row["fuel"]) + D(row["security"])
        price, billed = min(
            (max(D(row["minimum"]), kg * rate) + kg * surcharge, kg)
            for kg, rate in candidates
        )
        offers.append(
            Offer(
                airline=row["airline"],
                days=int(row["days"]),
                total_eur=money(price),
                billed_kg=billed,
                source="sheet",
            )
        )
    return offers


def contacts(s: Shipment):
    return [
        c
        for c in CARRIERS
        if (s.destination if c["kind"] == "airline" else s.pickup_zone)
        in c["serves"].split()
    ]


def rank(s: Shipment, offers):
    if s.pickup_zone and s.pickup_eur is None:
        return []
    for offer in offers:
        offer.total_eur = money(offer.total_eur + (s.pickup_eur or 0))
        offer.days += s.pickup_days

    def priority(o):
        cost_time = (o.total_eur, o.days)
        return (
            cost_time if s.preference == "cheapest" else cost_time[::-1],
            o.source != "portal",
        )

    return sorted(offers, key=priority)


def piece_volume(text):
    """Use a single unambiguous piece count and one set of metric dimensions."""
    dimension = r"(\d+(?:[.,]\d+)?)\s*[x×]\s*(\d+(?:[.,]\d+)?)\s*[x×]\s*(\d+(?:[.,]\d+)?)\s*(cm|mm|m)\b"
    matches = re.findall(dimension, text, re.IGNORECASE)
    counts = re.findall(
        r"\b(\d+)\s*(?:pallets?|plt|pieces?|pcs|crates?|cartons?|boxes|box|colli|paletten|kisten)\b",
        text,
        re.IGNORECASE,
    )
    if len(matches) != 1 or len(counts) != 1:
        return None
    length, width, height, unit = matches[0]
    factor = {"m": D(1), "cm": D("0.01"), "mm": D("0.001")}[unit.lower()]
    total = D(counts[0]) * factor**3
    for number in (length, width, height):
        total *= D(number.replace(",", "."))
    return format(total.normalize(), "f")


def extract(text):
    """Suggest editable fields. Unknown or ambiguous values stay blank."""
    tokens = set(re.findall(r"\b[A-Z]{3}\b", text.upper()))
    zones = set(
        re.findall(
            r"\b(?:PLZ|postal code|postcode|zip(?: code)?|zone)\s*[:#-]?\s*(\d{2})(?:\d{3})?\b",
            text,
            re.IGNORECASE,
        )
    )
    zones.update(
        re.findall(r"\b(\d{2})\d{3}\b", text)
        if re.search(r"pick.?up|abholung|door", text, re.IGNORECASE)
        else []
    )
    zone = next(iter(zones)) if len(zones) == 1 else None
    origins = tokens & ORIGINS
    dests = tokens & DESTINATIONS

    def quantity(units):
        values = re.findall(
            r"(?<![\w.,])([0-9]+(?:[.,][0-9]+)?)\s*(?:" + units + r")\b",
            text,
            re.IGNORECASE,
        )
        if len(values) != 1:
            return None
        value = values[0]
        # A single separator before three digits can mean decimals or thousands.
        if re.fullmatch(r"[1-9]\d*[,.]\d{3}", value):
            return None
        return value.replace(",", ".")

    fast = re.search(r"fastest|quickest|shortest|schnellst", text, re.IGNORECASE)
    cheap = re.search(
        r"cheapest|lowest|economical|günstig|preiswert|best price", text, re.IGNORECASE
    )
    return {
        "origin": next(iter(origins)) if len(origins) == 1 else ZONES.get(zone),
        "destination": next(iter(dests)) if len(dests) == 1 else None,
        "weight_kg": quantity(r"kg|kgs|kilograms?"),
        "volume_cbm": quantity(r"cbm|m³|m3|cubic met(?:er|re)s?") or piece_volume(text),
        "preference": ("fastest" if fast else "cheapest")
        if bool(fast) != bool(cheap)
        else None,
        "pickup_zone": zone,
        "pickup_eur": None,
        "pickup_days": 0,
    }
