"""Exercise the first quote, persisted cursor and uncertain-delivery paths offline."""

import tempfile
import threading
import time
import unittest
from datetime import UTC, date, datetime, timedelta
from decimal import Decimal
from pathlib import Path
from unittest.mock import patch

import httpx
from fastapi.testclient import TestClient

from .main import create_app
from .pricing import TARIFFS, Shipment, extract, local_offers, rank
from .provider import Provider
from .store import Store, now


def sample(mid=7, **changes):
    return {
        "id": mid,
        "from": "customer@hackloop.mail",
        "subject": "RFQ: FRA to LAX",
        "body": "Please quote the cheapest air option for 100 kg and 0.2 cbm.",
        "sent_at": now().isoformat(),
        "references": [],
        "attachments": [],
        **changes,
    }


class WaveTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.path = Path(self.tmp.name) / "state.db"
        self.sent = []
        self.fail_send = False
        self.portal_limited = False
        self.provider = Provider("https://fixture.invalid", "dummy")
        self.provider.client.close()
        self.provider.client = httpx.Client(
            base_url="https://fixture.invalid",
            transport=httpx.MockTransport(self.upstream),
        )
        self.app = create_app(
            db_path=self.path, provider=self.provider, run_workers=False
        )
        self.store = self.app.state.store
        self.client = TestClient(self.app)
        self.payload = {
            "shipment": {
                "origin": "FRA",
                "destination": "LAX",
                "weight_kg": "100",
                "volume_cbm": "0.2",
                "preference": "cheapest",
            }
        }

    def upstream(self, req):
        import json

        path = req.url.path
        if path.endswith("/ports"):
            data = {
                "lanes": [
                    {
                        "origin": "FRA",
                        "destination": "LAX",
                        "airlines": [{"code": "PL", "name": "Polaris Air Logistics"}],
                    }
                ]
            }
        elif path.endswith("/rates"):
            if self.portal_limited:
                return httpx.Response(429, headers={"Retry-After": "10"})
            data = {
                "airline": "Polaris Air Logistics",
                "transit_days": 2,
                "currency": "USD",
                "fx_eur_per_usd": "0.90",
                "total": "100.00",
                "rate_id": "R-fixture001",
                "chargeable_weight_kg": "100",
                "valid_until": (now() + timedelta(minutes=15)).isoformat(),
            }
        elif path.endswith("/bookings"):
            data = {"booking_reference": "BK-fixture", "status": "booked"}
        elif path.endswith("/messages") and req.method == "GET":
            messages = [sample()] if req.url.params["since_id"] == "0" else []
            data = {"messages": messages, "next_since_id": 7}
        elif path.endswith("/messages") and req.method == "POST":
            self.sent.append(json.loads(req.content))
            if self.fail_send:
                raise httpx.ReadTimeout("upstream may have accepted")
            return httpx.Response(201, json={"id": 100})
        else:
            raise AssertionError(path)
        return httpx.Response(200, json=data)

    def ingest(self, *items):
        self.store.ingest(
            {"messages": list(items), "next_since_id": max(m["id"] for m in items)}
        )

    def test_pipeline_and_replay(self):
        m = sample()
        self.ingest(m)
        self.ingest(m)
        self.assertEqual(Store(self.path).cursor(), 7)
        self.assertEqual(len(self.client.get("/api/v1/messages").json()), 1)
        endpoint = "/api/v1/messages/7"
        p = self.client.post(endpoint + "/price", json=self.payload).json()
        self.assertEqual(p["offer"]["total_eur"], "90.00")
        self.assertIn("EUR 90.00", p["body"])
        self.assertEqual(
            self.client.post(endpoint + "/quote", json={"revision": "old"}).status_code,
            409,
        )
        for expected in (200, 409):
            self.assertEqual(
                self.client.post(
                    endpoint + "/quote", json={"revision": p["revision"]}
                ).status_code,
                expected,
            )
        self.assertEqual(len(self.sent), 1)
        self.assertEqual(self.sent[0]["in_reply_to"], m["id"])
        self.assertEqual(self.sent[0]["to"], m["from"])
        self.assertEqual(
            self.client.post(endpoint + "/book").json()["booking_reference"],
            "BK-fixture",
        )
        self.assertEqual(
            self.client.get("/api/v1/messages").json()[0]["delivery"], "sent"
        )

    def test_uncertain_delivery_cannot_replay(self):
        self.ingest(sample())
        self.fail_send = True
        p = self.client.post("/api/v1/messages/7/price", json=self.payload).json()
        self.assertEqual(
            self.client.post(
                "/api/v1/messages/7/quote", json={"revision": p["revision"]}
            ).status_code,
            502,
        )
        self.assertEqual(Store(self.path).messages()[0]["delivery"], "uncertain")
        self.assertFalse(Store(self.path).reserve(7, "quote:7", p["revision"]))

    @patch("backend.main.now", return_value=datetime(2026, 11, 21, 15, tzinfo=UTC))
    def test_fallback_pickup_and_deadline(self, clock):
        self.ingest(
            sample(sent_at=clock().isoformat()),
            sample(8, sent_at=(clock() - timedelta(minutes=7)).isoformat()),
        )
        self.portal_limited = True
        payload = self.payload
        payload["shipment"].update(pickup_zone="60")
        self.assertIsNone(
            self.client.post("/api/v1/messages/7/price", json=payload).json()["offer"]
        )
        payload["shipment"]["pickup_eur"] = "45"
        payload["shipment"]["pickup_days"] = 1
        local = local_offers(Shipment(**payload["shipment"]), date(2026, 11, 1))
        p = self.client.post("/api/v1/messages/7/price", json=payload).json()
        self.assertEqual(
            Decimal(p["offer"]["total_eur"]), min(o.total_eur for o in local) + 45
        )
        self.assertTrue(p["notes"])
        self.assertEqual(
            self.client.post("/api/v1/messages/8/price", json=payload).status_code, 409
        )

    def test_tariff_math_and_extraction(self):
        self.assertEqual(len(TARIFFS), 158)
        self.assertEqual(len({r["airline"] for r in TARIFFS}), 10)
        s = Shipment(
            origin="FRA",
            destination="ORD",
            weight_kg=99,
            volume_cbm=0,
            preference="fastest",
        )
        offers = local_offers(s, date(2026, 11, 15))
        yellow = next(o for o in offers if o.airline == "Yellowtail Air Freight")
        self.assertEqual((yellow.billed_kg, yellow.total_eur), (100, Decimal("319.00")))
        self.assertEqual(rank(s, offers)[0].days, min(o.days for o in offers))
        tiny = s.model_copy(update={"weight_kg": Decimal(1)})
        yellow = next(
            o
            for o in local_offers(tiny, date(2026, 11, 15))
            if o.airline == "Yellowtail Air Freight"
        )
        self.assertEqual(yellow.total_eur, Decimal("110.77"))
        self.assertEqual(
            extract("pickup zone 60 to LAX, 20 kg, 1,2 cbm, fastest")["origin"], "FRA"
        )
        self.assertEqual(
            extract("3 pallets, each 120 x 80 x 50 cm")["volume_cbm"], "1.44"
        )
        self.assertIsNone(extract("1,200 kg")["weight_kg"])
        self.assertIsNone(extract("400 kg or 800 kg")["weight_kg"])
        self.assertEqual(local_offers(s, date(2026, 12, 1)), [])

    def test_autonomous_flow_through_workers(self):
        app = create_app(
            db_path=self.path, provider=self.provider, run_workers=True, auto_send=True
        )
        with TestClient(app) as client:
            end = time.monotonic() + 6
            while time.monotonic() < end:
                items = client.get("/api/v1/messages").json()
                if items and items[0]["delivery"] == "sent":
                    break
                threading.Event().wait(0.03)
            self.assertEqual(len(self.sent), 1)
            self.assertEqual(self.sent[0]["in_reply_to"], 7)
            self.assertEqual(client.get("/api/v1/status").json()["cursor"], 7)
