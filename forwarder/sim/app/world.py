"""The simulated freight world: carriers, lanes, rate tables, trucker prices and the ground-truth best price.

Three kinds of airlines, as in the real event:
- sheet airlines: priced from the start kit's tariff sheets (fixtures/tariffs.json, exported from our parser),
  reachable by email only;
- portal airlines (AU, BX, ...): priced by the rate portal, with generated deterministic rate tables;
- email-only airlines (e.g. Zephyr Steppe Cargo for ASU): generated tables, reachable by email only.

All rates follow one model (ARCHITECTURE.md §5): per-kg rate by weight break, a minimum on the freight,
fuel + security per billed kg. Carriers and the portal price a shipment in its own bracket; only a
forwarder that tries the higher breaks finds the cheaper "billed at the next break" price.
"""
from __future__ import annotations

import csv
import hashlib
import json
import random
from dataclasses import dataclass, field
from pathlib import Path

ORIGINS = ["CGN", "DUS", "FRA", "HAM", "MUC", "STR"]
KG_PER_CBM = 167

PORTAL_AIRLINES = {
    "AU": "Aurelia Air Cargo", "BX": "Borealis Freight Airways", "FQ": "Fjordline Air Cargo",
    "GN": "Gannet Airways", "IB": "Ibisair Cargo", "JP": "Juniper Cargo Lines", "LM": "Lumen Air Cargo",
    "MD": "Meridian Freightways", "NV": "Nova Atlas Cargo", "PL": "Polaris Air Logistics",
    "RV": "Riverbend Airlines Cargo", "SL": "Solace Air Cargo", "TM": "Tamarind Airways",
    "UV": "Ultraviolet Air Freight", "XC": "Xenon Cargo Airlines",
}

BREAK_SETS = [
    [0, 45, 100, 300, 500, 1000],
    [0, 45, 100, 250, 500, 1000, 2000],
    [0, 45, 100, 300, 500, 1000, 2000],
]
BREAK_FACTORS = [1.0, 0.82, 0.68, 0.58, 0.5, 0.42, 0.36]

# Lanes the history RFQs need (re-issued by the customer agent), so their ground truth exists.
FORCED_LANES = {"Ibisair Cargo": [("CGN", "ORD")], "Solace Air Cargo": [("FRA", "JFK")]}

CITY = {
    "ASU": ("Asunción", "PY"), "ATL": ("Atlanta", "US"), "BKK": ("Bangkok", "TH"), "BOG": ("Bogotá", "CO"),
    "BOM": ("Mumbai", "IN"), "CAI": ("Cairo", "EG"), "DEL": ("Delhi", "IN"), "DFW": ("Dallas", "US"),
    "DOH": ("Doha", "QA"), "DXB": ("Dubai", "AE"), "EZE": ("Buenos Aires", "AR"), "GRU": ("São Paulo", "BR"),
    "HKG": ("Hong Kong", "HK"), "ICN": ("Seoul", "KR"), "JFK": ("New York", "US"), "JNB": ("Johannesburg", "ZA"),
    "KTM": ("Kathmandu", "NP"), "KUL": ("Kuala Lumpur", "MY"), "LAX": ("Los Angeles", "US"), "LIM": ("Lima", "PE"),
    "LOS": ("Lagos", "NG"), "MEL": ("Melbourne", "AU"), "MEX": ("Mexico City", "MX"), "MIA": ("Miami", "US"),
    "MNL": ("Manila", "PH"), "NBO": ("Nairobi", "KE"), "NRT": ("Tokyo", "JP"), "ORD": ("Chicago", "US"),
    "PEK": ("Beijing", "CN"), "PPT": ("Papeete", "PF"), "PVG": ("Shanghai", "CN"), "RUH": ("Riyadh", "SA"),
    "SCL": ("Santiago", "CL"), "SGN": ("Ho Chi Minh City", "VN"), "SIN": ("Singapore", "SG"), "SYD": ("Sydney", "AU"),
    "TLV": ("Tel Aviv", "IL"), "TNR": ("Antananarivo", "MG"), "TPE": ("Taipei", "TW"), "ULN": ("Ulaanbaatar", "MN"),
    "YYZ": ("Toronto", "CA"), "CGN": ("Cologne", "DE"), "DUS": ("Düsseldorf", "DE"), "FRA": ("Frankfurt", "DE"),
    "HAM": ("Hamburg", "DE"), "MUC": ("Munich", "DE"), "STR": ("Stuttgart", "DE"),
}


def rng(*parts: object) -> random.Random:
    """A random generator that depends only on its key: the world is the same on every start."""
    digest = hashlib.sha256("|".join(map(str, parts)).encode()).hexdigest()
    return random.Random(int(digest[:16], 16))


def chargeable_kg(weight_kg: float, volume_cbm: float) -> float:
    return max(weight_kg, (volume_cbm or 0) * KG_PER_CBM)


@dataclass
class LaneRate:
    airline: str
    origin: str
    destination: str
    transit_days: int
    minimum: float
    breaks: list[tuple[int, float]]
    fuel: float
    security: float
    currency: str = "EUR"

    @property
    def lane(self) -> str:
        return f"{self.origin}-{self.destination}"

    def price(self, chargeable: float, *, higher_breaks: bool) -> tuple[float, float]:
        """(all-in total, billed kg). Own bracket only, or also every higher break when higher_breaks."""
        idx = max(i for i, (start, _) in enumerate(self.breaks) if start <= chargeable)
        best: tuple[float, float] | None = None
        for start, rate in self.breaks[idx:] if higher_breaks else [self.breaks[idx]]:
            billed = max(chargeable, start)
            freight = max(rate * billed, self.minimum)
            total = round(freight + (self.fuel + self.security) * billed, 2)
            if best is None or total < best[0]:
                best = (total, billed)
        assert best is not None
        return best


@dataclass
class Carrier:
    name: str
    kind: str  # airline | trucker
    email: str
    serves: list[str]
    code: str


@dataclass
class Option:
    airline: str
    kind: str  # sheet | portal | email
    total: float
    transit_days: int


@dataclass
class Truth:
    """The best price the simulator accepts as reference for one RFQ."""
    total: float
    airline: str
    transit_days: int
    truck: float
    trucker: str | None
    options: list[Option] = field(default_factory=list)


class World:
    def __init__(self, start_kit: Path, fixtures: Path, seed: int = 5):
        self.seed = seed
        self.carriers: dict[str, Carrier] = {}  # by lower-case email
        self.zones: dict[str, tuple[str, str]] = {}  # zone -> (area, airport)
        self.lanes: dict[tuple[str, str, str], LaneRate] = {}  # (airline, origin, dest)
        self.kind: dict[str, str] = {}  # airline name -> sheet | portal | email
        self._load_carriers(start_kit / "carrier-list.csv")
        self._load_zones(start_kit / "pickup_zones.csv")
        self._load_sheets(fixtures / "tariffs.json")
        self._generate_portal_and_email_lanes()

    # ---- loading ---------------------------------------------------------------------------------
    def _load_carriers(self, path: Path) -> None:
        with path.open(newline="") as f:
            for row in csv.DictReader(f):
                name = row["name"].strip()
                self._add_carrier(name, row["kind"].strip(), row["email"].strip().lower(), row["serves"].split())
        for code, name in PORTAL_AIRLINES.items():
            if self.airline(name) is None:  # TM, UV, XC are on the portal but not in the carrier list
                slug = name.lower().replace(" ", "-")
                destinations = sorted(rng("portal-only", name).sample(sorted(CITY.keys() - set(ORIGINS)), 8))
                self._add_carrier(name, "airline", f"{slug}@hackloop.mail", destinations, code)

    def _add_carrier(self, name: str, kind: str, email: str, serves: list[str], code: str | None = None) -> None:
        if code is None:
            code = next((c for c, n in PORTAL_AIRLINES.items() if n == name), None)
        if code is None:
            words = [w for w in name.split() if w[0].isalpha()]
            code = (words[0][0] + (words[1][0] if len(words) > 1 else words[0][1])).upper()
        self.carriers[email] = Carrier(name, kind, email, serves, code)

    def _load_zones(self, path: Path) -> None:
        with path.open(newline="") as f:
            for row in csv.DictReader(f):
                self.zones[row["zone"].strip()] = (row["area"].strip(), row["airport"].strip())

    def _load_sheets(self, path: Path) -> None:
        for tariff in json.loads(path.read_text()):
            name = tariff["airlineName"]
            self.kind[name] = "sheet"
            for lane in tariff["lanes"]:
                self.lanes[(name, lane["origin"], lane["destination"])] = LaneRate(
                    name, lane["origin"], lane["destination"], int(lane["transitDays"] or 5), float(lane["minimum"]),
                    [(int(b["fromKg"]), float(b["rate"])) for b in lane["breaks"]],
                    float(lane["fuelPerKg"]), float(lane["securityPerKg"]), tariff.get("currency", "EUR"))

    def _generate_portal_and_email_lanes(self) -> None:
        for carrier in self.airlines():
            if self.kind.get(carrier.name) == "sheet":
                continue
            self.kind[carrier.name] = "portal" if carrier.name in PORTAL_AIRLINES.values() else "email"
            for dest in carrier.serves:
                r = rng("origins", carrier.name, dest)
                origins = r.sample(ORIGINS, 2 if r.random() < 0.3 else 1)
                for origin in origins:
                    self.lanes[(carrier.name, origin, dest)] = self._generated_rate(carrier.name, origin, dest)
        for name, lanes in FORCED_LANES.items():
            for origin, dest in lanes:
                self.lanes.setdefault((name, origin, dest), self._generated_rate(name, origin, dest))

    def _generated_rate(self, airline: str, origin: str, dest: str) -> LaneRate:
        r = rng("rate", self.seed, airline, origin, dest)
        breaks_kg = BREAK_SETS[r.randrange(len(BREAK_SETS))]
        base = r.uniform(2.6, 6.5)
        rates: list[tuple[int, float]] = []
        for i, start in enumerate(breaks_kg):
            rate = round(base * BREAK_FACTORS[i], 2)
            if rates and r.random() < 0.15:  # a flat break: billing at the next break becomes cheaper
                rate = rates[-1][1]
            rates.append((start, rate))
        return LaneRate(airline, origin, dest, r.randint(2, 8), round(r.uniform(60, 140), 2), rates,
                        round(r.uniform(0.55, 1.15), 2), round(r.uniform(0.1, 0.3), 2))

    # ---- queries ---------------------------------------------------------------------------------
    def airlines(self) -> list[Carrier]:
        return [c for c in self.carriers.values() if c.kind == "airline"]

    def truckers(self) -> list[Carrier]:
        return [c for c in self.carriers.values() if c.kind == "trucker"]

    def carrier(self, email: str) -> Carrier | None:
        return self.carriers.get(email.strip().lower())

    def airline(self, name_or_code: str) -> Carrier | None:
        key = name_or_code.strip().lower()
        for c in self.airlines():
            if c.name.lower() == key or c.code.lower() == key:
                return c
        return None

    def lanes_of(self, airline: str) -> list[LaneRate]:
        return sorted((l for (a, _, _), l in self.lanes.items() if a == airline), key=lambda l: l.lane)

    def on_lane(self, origin: str, dest: str) -> list[LaneRate]:
        return [l for (a, o, d), l in self.lanes.items() if o == origin and d == dest]

    def portal_lanes(self) -> list[tuple[str, str, list[Carrier]]]:
        grouped: dict[tuple[str, str], list[Carrier]] = {}
        for (airline, o, d) in sorted(self.lanes):
            if self.kind.get(airline) == "portal":
                grouped.setdefault((o, d), []).append(self.airline(airline))
        return [(o, d, cs) for (o, d), cs in grouped.items()]

    def destinations(self) -> set[str]:
        return {d for (_, _, d) in self.lanes}

    def truckers_for_zone(self, zone: str) -> list[Carrier]:
        return [t for t in self.truckers() if zone in t.serves]

    def truck_offer(self, trucker: str, zone: str, chargeable: float) -> tuple[float, int]:
        """(EUR all-in, hours) for collecting in a postcode zone and delivering to that zone's airport."""
        r = rng("truck", self.seed, trucker, zone)
        return round(r.uniform(60, 180) + 0.04 * chargeable, 2), r.randint(3, 30)

    def truth(self, origin: str, dest: str, chargeable: float, mode: str, zone: str | None) -> Truth | None:
        """Best price for an RFQ. Sheet and portal airlines count with the higher-break rule (a forwarder can
        find it), email-only airlines with their own-bracket bid (that is all they ever offer). Door pickup adds
        the cheapest trucker. cheapest: lowest total. fastest: fewest transit days, then lowest total."""
        options = []
        for lane in self.on_lane(origin, dest):
            kind = self.kind[lane.airline]
            total, _ = lane.price(chargeable, higher_breaks=kind != "email")
            options.append(Option(lane.airline, kind, total, lane.transit_days))
        if not options:
            return None
        truck, trucker = 0.0, None
        if zone:
            offers = [(self.truck_offer(t.name, zone, chargeable)[0], t.name) for t in self.truckers_for_zone(zone)]
            if not offers:
                return None
            truck, trucker = min(offers)
        key = (lambda o: (o.transit_days, o.total)) if mode == "fastest" else (lambda o: (o.total, o.transit_days))
        best = min(options, key=key)
        options.sort(key=key)
        return Truth(round(best.total + truck, 2), best.airline, best.transit_days, truck, trucker, options)

    def scale_sheet(self, airline: str, factor: float) -> list[LaneRate]:
        """Chaos: a new monthly tariff with changed rates. The truth follows immediately."""
        lanes = self.lanes_of(airline)
        for lane in lanes:
            lane.breaks = [(start, round(rate * factor, 2)) for start, rate in lane.breaks]
            lane.minimum = round(lane.minimum * factor, 2)
        return lanes
