import time
from decimal import Decimal
from threading import Lock

import httpx

from .pricing import Offer, money


class Provider:
    def __init__(self, url, key):
        self.client = httpx.Client(
            base_url=url, headers={"X-API-Key": key}, timeout=5.0
        )
        self.lanes = {}
        self.refresh_at = 0.0
        self.retry_at = 0.0
        self.lock = Lock()

    def poll(self, cursor):
        r = self.client.get(
            "/v1/mailbox/messages", params={"since_id": cursor, "limit": 200}
        )
        r.raise_for_status()
        return r.json()

    def rates(self, shipment):
        offers, notes = [], []
        with self.lock:
            try:
                if time.monotonic() >= self.refresh_at:
                    r = self.client.get("/v1/portal/ports")
                    r.raise_for_status()
                    self.lanes = {
                        (l["origin"], l["destination"]): l["airlines"]
                        for l in r.json()["lanes"]
                    }
                    self.refresh_at = time.monotonic() + 300
                for airline in self.lanes.get(
                    (shipment.origin, shipment.destination), []
                ):
                    if time.monotonic() < self.retry_at:
                        notes.append(
                            "Portal rate limit reached. Showing available offers."
                        )
                        break
                    r = self.client.get(
                        "/v1/portal/rates",
                        params={
                            "origin": shipment.origin,
                            "destination": shipment.destination,
                            "airline": airline["code"],
                            "weight_kg": str(shipment.weight_kg),
                            "volume_cbm": str(shipment.volume_cbm),
                        },
                    )
                    if r.status_code == 429:
                        self.retry_at = time.monotonic() + int(
                            r.headers.get("Retry-After", "60")
                        )
                        notes.append(
                            "Portal rate limit reached. Showing available offers."
                        )
                        break
                    if not r.is_success:
                        notes.append(
                            f"{airline['name']} portal rate unavailable (HTTP {r.status_code})."
                        )
                        continue
                    rate = r.json()
                    fx = (
                        "1" if rate["currency"] == "EUR" else rate.get("fx_eur_per_usd")
                    )
                    if fx is None:
                        notes.append(
                            f"{airline['name']} did not supply an exchange rate."
                        )
                        continue
                    offers.append(
                        Offer(
                            airline=rate["airline"],
                            days=rate["transit_days"],
                            total_eur=money(Decimal(rate["total"]) * Decimal(fx)),
                            billed_kg=rate["chargeable_weight_kg"],
                            source="portal",
                            rate_id=rate["rate_id"],
                            valid_until=rate["valid_until"],
                        )
                    )
            except (httpx.HTTPError, ValueError, KeyError, ArithmeticError) as exc:
                notes.append(
                    f"Portal lookup incomplete ({type(exc).__name__}). Local tariffs are available."
                )
        return offers, notes
