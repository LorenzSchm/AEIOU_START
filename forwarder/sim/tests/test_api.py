"""API shapes follow forwarder/docs/5u-openapi.json and the payloads recorded from the live 5U API."""
import pytest
from fastapi.testclient import TestClient

from app.main import app, sim, store

H = {"X-API-Key": "sim-key"}


@pytest.fixture()
def client():
    with TestClient(app) as c:
        yield c


def test_requires_a_key(client):
    assert client.get("/v1/me").status_code == 401
    assert client.get("/v1/me", headers=H).json() == {"team": "aeiou", "display_name": "AEIOU", "mailbox": "aeiou@hackloop.mail"}


def test_poll_returns_rfq_in_live_shape(client):
    rnd = sim.new_rfq(use_history=False)
    data = client.get("/v1/mailbox/messages", params={"since_id": rnd.rfq_mail_id - 1}, headers=H).json()
    assert set(data) == {"messages", "next_since_id"}
    item = data["messages"][0]
    assert set(item) == {"id", "from", "to", "subject", "body", "received_at", "in_reply_to", "references", "attachments"}
    assert data["next_since_id"] == item["id"] and item["to"] == "aeiou@hackloop.mail"
    assert client.get("/v1/mailbox/messages", params={"since_id": 10**12}, headers=H).json()["next_since_id"] == 10**12


def test_send_validates_recipient_and_thread(client):
    assert client.post("/v1/mailbox/messages", json={"to": "other-team@hackloop.mail"}, headers=H).status_code == 422
    assert client.post("/v1/mailbox/messages", json={"to": "ratedesk@hackloop.mail", "in_reply_to": 999999}, headers=H).status_code == 404
    sent = client.post("/v1/mailbox/messages", json={"to": "ratedesk@hackloop.mail", "subject": "x", "body": "y"}, headers=H)
    assert sent.status_code == 201 and sent.json()["from"] == "aeiou@hackloop.mail"


def test_portal_rate_and_limit_headers(client):
    store.rate_window.count = 0
    lane = client.get("/v1/portal/ports", headers=H).json()["lanes"][0]
    code = lane["airlines"][0]["code"]
    brackets = client.get("/v1/portal/brackets", params={"airline": code}, headers=H).json()
    assert brackets["breaks_kg"][0] == 0 and brackets["airline_code"] == code
    r = client.get("/v1/portal/rates", headers=H, params={"origin": lane["origin"], "destination": lane["destination"],
                                                          "airline": code, "weight_kg": 230, "volume_cbm": 1.2})
    assert r.status_code == 200 and r.headers["x-ratelimit-limit"] == "10"
    body = r.json()
    assert body["rate_id"].startswith("R-") and body["chargeable_weight_kg"] == "230" and body["currency"] == "EUR"
    assert set(body) == {"rate_id", "airline", "airline_code", "origin", "destination", "weight_kg", "chargeable_weight_kg",
                         "total", "currency", "transit_days", "valid_until", "dataset_version"}
    for _ in range(9):
        client.get("/v1/portal/rates", headers=H, params={"origin": lane["origin"], "destination": lane["destination"],
                                                          "airline": code, "weight_kg": 100})
    limited = client.get("/v1/portal/rates", headers=H, params={"origin": lane["origin"], "destination": lane["destination"],
                                                                "airline": code, "weight_kg": 100})
    assert limited.status_code == 429 and int(limited.headers["retry-after"]) >= 1
    store.rate_window.count = 0


def test_booking_status_codes(client):
    store.rate_window.count = 0
    lane = client.get("/v1/portal/ports", headers=H).json()["lanes"][1]
    rate = client.get("/v1/portal/rates", headers=H, params={"origin": lane["origin"], "destination": lane["destination"],
                                                             "airline": lane["airlines"][0]["code"], "weight_kg": 500}).json()
    first = client.post("/v1/portal/bookings", json={"rate_id": rate["rate_id"], "team_reference": "AEI-TEST1"}, headers=H)
    assert first.status_code == 201 and first.json()["booking_reference"].startswith("BK-")
    assert client.post("/v1/portal/bookings", json={"rate_id": rate["rate_id"], "team_reference": "AEI-TEST1"}, headers=H).status_code == 200
    assert client.post("/v1/portal/bookings", json={"rate_id": rate["rate_id"], "team_reference": "OTHER"}, headers=H).status_code == 409
    assert client.post("/v1/portal/bookings", json={"rate_id": "R-doesnotexist00", "team_reference": "x"}, headers=H).status_code == 404
    from datetime import timedelta
    from app.store import now
    rate2 = client.get("/v1/portal/rates", headers=H, params={"origin": lane["origin"], "destination": lane["destination"],
                                                              "airline": lane["airlines"][0]["code"], "weight_kg": 600}).json()
    store.rates[rate2["rate_id"]].valid_until = now() - timedelta(seconds=1)
    assert client.post("/v1/portal/bookings", json={"rate_id": rate2["rate_id"], "team_reference": "x"}, headers=H).status_code == 410


def test_board_me_live_shape(client):
    data = client.get("/v1/board/me", headers=H).json()
    assert set(data) == {"team", "rank", "total", "frozen_at", "scores_revealed", "requests", "bookings"}
    if data["requests"]:
        assert set(data["requests"][0]) == {"round_no", "sent_at", "answered_at", "verdict", "reason", "booked"}


def test_validation_error_shape(client):
    r = client.get("/v1/portal/brackets", params={"airline": ""}, headers=H)
    assert r.status_code == 422 and r.json()["type"] == "/errors/validation-error" and r.json()["errors"]
