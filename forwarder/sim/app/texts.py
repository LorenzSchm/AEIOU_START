"""Mail texts of the simulated customers, carriers and desks (phrased like the real 5U agents in
fixtures/mail-history.json), and tolerant parsing of the mails our forwarder sends them."""
from __future__ import annotations

import random
import re
from dataclasses import dataclass

COMMODITIES = [
    ("spare parts for packaging machines", "Ersatzteile für Verpackungsmaschinen"),
    ("electronic control units", "elektronische Steuergeräte"),
    ("printed marketing material", "Werbedrucksachen"),
    ("precision instruments", "Präzisionsinstrumente"),
    ("furniture samples", "Möbelmuster"),
    ("medical devices", "Medizinprodukte"),
    ("roasted coffee samples", "Röstkaffee-Muster"),
    ("automotive sensors", "Fahrzeugsensoren"),
    ("laboratory glassware", "Laborglas"),
    ("industrial valves", "Industrieventile"),
]

COMPANIES = [
    ("Kessler Feinmechanik GmbH", "kessler-feinmechanik", "Jonas Kessler", "Logistics Coordinator", "Ulm"),
    ("Rheintal Elektronik GmbH", "rheintal-elektronik", "Deniz Aydin", "Export Manager", "Mannheim"),
    ("Lindqvist Medtech", "lindqvist-medtech", "Sara Lindqvist", "Operations", "Bergisch Gladbach"),
    ("Sonnberg Kaffeeroesterei GmbH", "sonnberg-roesterei", "Anna Sonnberg", "Head of Purchasing and Logistics", "Bad Homburg"),
    ("Auer Maschinenbau GmbH", "auer-maschinenbau", "Tobias Auer", "Versand / Shipping", "Regensburg"),
    ("Hallig Optik AG", "hallig-optik", "Merle Petersen", "Supply Chain", "Hamburg"),
    ("Brenner Ventiltechnik", "brenner-ventiltechnik", "Lukas Brenner", "Logistik", "Stuttgart"),
    ("Vogt Laborbedarf", "vogt-laborbedarf", "Clara Vogt", "Einkauf und Versand", "Darmstadt"),
    ("Moselblick Weinkontor", "moselblick-weinkontor", "Paul Wagner", "Export", "Bonn"),
    ("Isarlicht Sensorik GmbH", "isarlicht-sensorik", "Ayla Demir", "Logistics Lead", "Munich"),
]

ACCEPT = [
    "Thank you for your offer. That works for us, please go ahead and book it.",
    "Thanks, that looks good. Please go ahead and book it.",
    "Vielen Dank für Ihr Angebot. Bitte buchen Sie die Sendung so.",
]
REJECT = [
    "Thank you for your offer, but your price is too high for us, so we will not go ahead with it.",
    "Thanks for the quick reply. Unfortunately the price is above our budget, so we will pass this time.",
]
FOLLOW_UP = [
    "Thanks for the offer. Before we decide: when would the shipment arrive at the destination airport?",
    "Thank you. Does the price include all surcharges, and how long is it valid?",
]


def fmt_eur(value: float) -> str:
    return f"{value:.2f}"


def mode_word(mode: str, lang: str, r: random.Random) -> str:
    if lang == "de":
        return "günstigste" if mode == "cheapest" else "schnellste"
    return r.choice(["cheapest", "most economical", "lowest-cost"]) if mode == "cheapest" else r.choice(
        ["fastest", "quickest", "earliest-arriving"])


def rfq_mail(spec: dict, company: tuple, r: random.Random) -> tuple[str, str]:
    """(subject, body) of a new customer RFQ in English or German."""
    name, _, person, title, _ = company
    d, lang = spec["destination"], spec["language"]
    city, cc = spec["dest_city"]
    commodity = COMMODITIES[spec["commodity"]][1 if lang == "de" else 0]
    mw = mode_word(spec["mode"], lang, r)
    if lang == "de":
        cbm = f"{spec['volume_cbm']:.2f}".replace(".", ",")
        where = (f"Bitte holen Sie die Sendung bei uns in {spec['zip']} {spec['town']} ab und bringen Sie sie zum Abflughafen."
                 if spec["door"] else f"Wir liefern die Ware selbst am Flughafen {spec['origin']} an.")
        route = f"nach {d} ({city}, {cc})" if spec["door"] else f"von {spec['origin']} nach {d} ({city}, {cc})"
        body = (f"Guten Tag,\n\nbitte bieten Sie uns die {mw} Luftfracht {route} an. {where} Die Ware ist ab dem "
                f"{spec['ready']} versandbereit.\n\n{spec['pieces']} Colli {commodity}, {spec['weight_kg']} kg brutto, "
                f"{cbm} cbm.\n\nBitte nennen Sie uns einen All-in-Preis in EUR.\n\nMit freundlichen Grüßen\n{person}\n{title}\n{name}")
        subject = f"Anfrage Luftfracht {spec['origin'] if not spec['door'] else spec['town']} - {d}"
        return subject, body
    cbm = f"{spec['volume_cbm']:.2f}"
    if spec["door"]:
        ask = (f"Could you please quote the {mw} air freight option to {d} ({city}, {cc})? Please arrange trucking from "
               f"our site in {spec['zip']} {spec['town']}, Germany, to the departure airport. The shipment is ready for "
               f"pickup on {spec['ready']}.")
    else:
        ask = (f"Could you please quote the {mw} air freight option from {spec['origin']} to {d} ({city}, {cc})? "
               f"We will deliver the shipment to {spec['origin']} ourselves. It will be ready on {spec['ready']}.")
    body = (f"{r.choice(['Hello,', 'Dear Air Freight Team,', 'Hi,'])}\n\n{ask}\n\nThe shipment consists of {spec['pieces']} "
            f"pieces of {commodity}, with a gross weight of {spec['weight_kg']} kg and a total volume of {cbm} cbm.\n\n"
            f"Please provide an all-in price in EUR.\n\nKind regards\n{person}\n{title}\n{name}")
    subject = r.choice([f"Air freight quote request to {d}", f"Air freight quote: {spec['origin']} to {d}",
                        f"{mw.capitalize()} air freight to {d}"])
    return subject, body


# ---- carrier replies -------------------------------------------------------------------------------

def airline_offer(airline: str, origin: str, dest: str, total: float, transit: int, valid: str, rate_id: str,
                  r: random.Random, alt: bool) -> str:
    if alt:
        return (f"Offer {origin}>{dest}: all-in EUR {total:,.2f} | transit {transit}d | valid {valid} | "
                f"booking ref {rate_id}\n-- {airline} pricing desk")
    return r.choice([
        f"Dear Team AEIOU,\n\nThank you for your inquiry. Our all-in airport-to-airport rate from {origin} to {dest} is "
        f"{fmt_eur(total)} EUR, with an estimated transit time of {transit} days. This rate is valid until {valid}. "
        f"Should you wish to book, please quote reference {rate_id}.\n\nKind regards,\n{airline}",
        f"Hi Team AEIOU,\n\nOur all-in air freight rate from {origin} to {dest} is {fmt_eur(total)} EUR, valid until "
        f"{valid}. Estimated transit time: {transit} days. Please quote {rate_id} when booking.\n\nBest regards,\n{airline}",
        f"All-in air freight from {origin} to {dest}: {fmt_eur(total)} EUR. Estimated transit time: {transit} days. "
        f"Quote valid until {valid}. Please quote {rate_id} if booking.\n{airline}",
    ])


def airline_decline(airline: str, email: str, origin: str, dest: str, lanes: list[str]) -> str:
    return (f"Hello, thank you for your request. We do not serve this lane/zone: {origin}-{dest}. We fly "
            f"{', '.join(lanes)}. Best regards {airline} {email}")


def trucker_offer(trucker: str, area: str, airport: str, total: float, hours: int, valid: str, ref: str,
                  r: random.Random, alt: bool) -> str:
    if alt:
        return f"Pickup {area} -> {airport}: EUR {total:,.2f} all-in, {hours}h, valid {valid}, ref {ref}. {trucker}"
    return r.choice([
        f"Hi Team AEIOU,\n\nOur all-in rate for pickup in {area} and delivery to {airport} is {fmt_eur(total)} EUR. The "
        f"estimated pickup and transit time is {hours} hours. The rate is valid until {valid}. Our offer reference is "
        f"{ref}. No booking is required for pickup.\n\nBest regards,\n{trucker}",
        f"Dear Team AEIOU,\n\nOur fixed all-in rate for collection and delivery to {airport} is {fmt_eur(total)} EUR, "
        f"valid until {valid}. Estimated time to pickup: {hours} hours. Offer reference: {ref}. No booking is required "
        f"for pickup.\n\nKind regards,\n{trucker}",
    ])


def trucker_decline(trucker: str, email: str, zone: str, zones: list[str]) -> str:
    return (f"Hello, thank you for your request. We do not serve this lane/zone: pickup postcode {zone}. We collect in "
            f"postcode zones {', '.join(zones)} (first two digits). Best regards {trucker} {email}")


def counter_refusal(company: str, total: float, transit: int | None, valid: str, rate_id: str | None) -> str:
    tail = f" Please quote {rate_id} when booking." if rate_id else ""
    transit_txt = f" Transit: {transit} days." if transit else ""
    return (f"Dear Team AEIOU,\n\nThank you for your counteroffer. We cannot revise our quotation. Our best and only "
            f"all-in rate is {fmt_eur(total)} EUR.{transit_txt} This rate is valid until {valid}.{tail}\n\nKind regards,\n{company}")


def ask_details(company: str) -> str:
    return (f"Hello Team AEIOU,\n\nthank you for your request. To quote we need the origin airport, the destination "
            f"airport and the chargeable weight (or gross weight and volume). Could you send these?\n\nBest regards,\n{company}")


def booking_confirmed(airline: str, ref: str, origin: str, dest: str, rate_id: str, price: float, team_ref: str) -> str:
    return (f"Dear Team AEIOU,\n\nwe confirm booking {ref} for your shipment {origin}-{dest} at {fmt_eur(price)} EUR "
            f"(rate {rate_id}). Your reference: {team_ref}. The shipment is booked and ready to ship.\n\nKind regards,\n{airline}")


def ratedesk_offer(airline: str, origin: str, dest: str, total: float, transit: int, valid: str, rate_id: str) -> str:
    return (f"Hello Team AEIOU,\n\nfor {origin}-{dest} the rate desk can confirm {airline}: {fmt_eur(total)} EUR all-in, "
            f"transit {transit} days, valid until {valid}. Please quote {rate_id} when booking.\n\nRate Desk")


def ratedesk_none(origin: str, dest: str) -> str:
    return f"Hello Team AEIOU,\n\nwe have no rate on file for {origin}-{dest}. Please contact the airlines directly.\n\nRate Desk"


def tracking_question(booking_ref: str, team_ref: str | None, r: random.Random) -> tuple[str, str]:
    ref = f" (your reference {team_ref})" if team_ref and r.random() < 0.5 else ""
    return (f"Status of shipment {booking_ref}",
            r.choice([f"Hello,\n\ncould you tell me the current status of shipment {booking_ref}{ref}?\n\nThanks,\nTracking Desk",
                      f"Hi,\n\nwhere is shipment {booking_ref}{ref} at the moment, and when is it expected to arrive?\n\nRegards,\nTracking Desk"]))


# ---- parsing our forwarder's mails ------------------------------------------------------------------

@dataclass
class Request:
    origin: str | None
    destination: str | None
    weight_kg: float | None
    volume_cbm: float | None
    zip: str | None
    rate_ids: list[str]
    counteroffer: bool
    booking: bool


NUM = r"(?:\d{1,3}(?:[.,\u00a0\u202f]\d{3})+|\d+)(?:[.,]\d+)?(?!\d)"


def parse_number(text: str) -> float | None:
    s = text.strip().replace(" ", "").rstrip(".,")
    if not s:
        return None
    if "," in s and "." in s:
        s = s.replace(".", "").replace(",", ".") if s.rfind(",") > s.rfind(".") else s.replace(",", "")
    elif re.fullmatch(r"\d{1,3}(?:\.\d{3})+", s):  # German thousands: 1.549 kg
        s = s.replace(".", "")
    elif "," in s:
        head, _, tail = s.rpartition(",")
        s = f"{head.replace(',', '')}.{tail}" if len(tail) != 3 else s.replace(",", "")
    try:
        return float(s)
    except ValueError:
        return None


def parse_request(subject: str, body: str, origins: set[str], destinations: set[str]) -> Request:
    text = f"{subject}\n{body}"
    codes = re.findall(r"\b([A-Z]{3})\b", text)
    origin = next((c for c in codes if c in origins), None)
    destination = None
    m = re.search(r"\bfrom\s+([A-Z]{3})\s+to\s+([A-Z]{3})\b", text) or re.search(r"\b([A-Z]{3})\s*(?:-|–|>|→|to)\s*([A-Z]{3})\b", text)
    if m and m.group(1) in origins:
        origin, destination = m.group(1), m.group(2)
    if destination is None:
        destination = next((c for c in codes if c in destinations and c != origin), None)
    weight = _first_number_before(text, r"(?:kg|kilo)")
    volume = _first_number_before(text, r"(?:cbm|m3|m³|cubic)")
    zip_match = re.search(r"\b(\d{5})\b", text)
    lowered = text.lower()
    return Request(origin, destination, weight, volume, zip_match.group(1) if zip_match else None,
                   re.findall(r"\bR-[A-Za-z0-9_-]{8,40}\b", text),
                   bool(re.search(r"counter.?offer|discount|lower (?:price|rate)|better (?:price|rate)|reduce", lowered)),
                   bool(re.search(r"\bbook(?:ing)?\b|\bbuchen\b", lowered)) and "quote" not in subject.lower())


def _first_number_before(text: str, unit: str) -> float | None:
    for m in re.finditer(rf"({NUM})\s*{unit}\b", text, flags=re.IGNORECASE):
        value = parse_number(m.group(1))
        if value and value > 0:
            return value
    return None


CURRENCY = r"(EUR|€|USD|US\$|\$|euros?|GBP|CHF)"


def money_amounts(text: str) -> list[tuple[float, str]]:
    """Every amount tagged with a currency, e.g. 'EUR 2,780.00', '556.00 EUR', '€1.387,35'."""
    found: list[tuple[int, float, str]] = []
    for m in re.finditer(rf"{CURRENCY}\s?({NUM})|({NUM})\s?{CURRENCY}", text, flags=re.IGNORECASE):
        cur, num = (m.group(1), m.group(2)) if m.group(1) else (m.group(4), m.group(3))
        value = parse_number(num)
        if value is not None:
            found.append((m.start(), value, _currency(cur)))
    return [(v, c) for _, v, c in sorted(found)]


def _currency(token: str) -> str:
    t = token.lower()
    if t in ("eur", "€", "euro", "euros"):
        return "EUR"
    if t in ("usd", "$", "us$"):
        return "USD"
    return token.upper()
