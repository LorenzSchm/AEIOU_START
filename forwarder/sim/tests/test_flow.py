"""A quote sent through the API is scored against the truth, and carriers answer rate requests."""
import time

from fastapi.testclient import TestClient

from app.main import app, settings, sim, store

H = {"X-API-Key": "sim-key"}


def test_quote_in_thread_is_scored_and_carrier_answers():
    settings.carrier_delay_min_s = settings.carrier_delay_max_s = 0.05
    with TestClient(app) as client:
        rnd = sim.new_rfq(use_history=False)
        while rnd.truth is None:
            rnd = sim.new_rfq(use_history=False)
        best = rnd.truth["total"]
        quote = client.post("/v1/mailbox/messages", headers=H, json={
            "to": rnd.customer, "subject": "Re: quote", "in_reply_to": rnd.rfq_mail_id,
            "body": f"Hi,\n\nwe can offer EUR {best:,.2f} all-in.\n\nBest regards,\nLena"})
        assert quote.status_code == 201
        assert rnd.verdict == "accepted" and rnd.points["price"] == 45.0

        airline = rnd.truth["airline"]
        email = next(c.email for c in sim.world.airlines() if c.name == airline)
        spec = rnd.spec
        ask = client.post("/v1/mailbox/messages", headers=H, json={
            "to": email, "subject": f"Spot rate request {spec['origin']} to {spec['destination']}",
            "body": f"Could you quote from {spec['origin']} to {spec['destination']}, {spec['weight_kg']} kg, "
                    f"{spec['volume_cbm']} cbm?"}).json()
        deadline = time.time() + 3
        reply = None
        while time.time() < deadline and reply is None:
            client.get("/health")  # let the loop run the scheduled reply
            reply = next((m for m in store.mails.values() if m.in_reply_to == ask["id"]), None)
            time.sleep(0.05)
        assert reply is not None and "EUR" in reply.body and "R-" in reply.body
