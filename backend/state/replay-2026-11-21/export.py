"""Export the official round inputs and causal message links from the read-only records."""
import json
import sqlite3
from datetime import datetime, timedelta
from pathlib import Path

BASE = Path(__file__).resolve().parent
db = sqlite3.connect(f"file:{BASE.parent / 'dispatch.sqlite3'}?mode=ro", uri=True)
db.row_factory = sqlite3.Row

def read(name):
    return json.loads((BASE / name).read_text())

def stored(value, fallback=None):
    return json.loads(value) if value else fallback

board = read("board.json")
mailbox = {m["id"]: m for m in read("mailbox.json")}
extra_outbound = {m["id"]: m for m in read("extra-outbound.json")}
history = [
    mailbox[row["id"]]
    for row in db.execute("SELECT id FROM mail WHERE kind='carrier' AND body LIKE '%We fly %' ORDER BY id")
    if row["id"] <= max(mailbox)
]
case_rows = {
    datetime.fromisoformat(row["received"]): row
    for row in db.execute("""
      SELECT m.*, w.offers,w.queried,w.reason,w.operator,p.result AS gateway,
             p.state AS gateway_state,p.model AS gateway_model
      FROM mail m JOIN wave_case w ON m.id=w.id LEFT JOIN rfq_parse p ON p.request_id=m.id
      WHERE m.kind='request'
    """)
}
rounds = []
for official in sorted(board["requests"], key=lambda row: row["round_no"]):
    if not 1 <= official["round_no"] <= 30:
        continue
    row = case_rows[datetime.fromisoformat(official["sent_at"])]
    rfq = row["id"]
    original = mailbox[rfq]
    deliveries = {}
    for outgoing in db.execute("""
      SELECT action,attempted_at,response,payload FROM deliveries
      WHERE state='sent' AND (action LIKE ? OR action LIKE ?)
      ORDER BY attempted_at
    """, (f"%:{rfq}", f"%:{rfq}:%")):
        receipt = stored(outgoing["response"], {})
        payload = stored(outgoing["payload"], {})
        if receipt.get("id") and payload:
            deliveries[receipt["id"]] = {
                **payload,
                "id": receipt["id"],
                "action": outgoing["action"],
                "attempted_at": outgoing["attempted_at"],
                "replies": [],
            }
    # Historic operator actions used the immediate carrier reply ID in their action key.
    # These operator messages have known case ownership and explicit postcode or transit terms.
    related_manual_sends = {
        263: {"reply:345": "corrected-postcode", "reply:346": "corrected-postcode"},
        4637: {"reply:4885": "truck-transit", "reply:4886": "truck-transit"},
    }.get(rfq, {})
    for action, treatment in related_manual_sends.items():
        item = db.execute("SELECT attempted_at,response,payload FROM deliveries WHERE action=?", (action,)).fetchone()
        if item:
            sent = stored(item["response"], {})["id"]
            payload = stored(item["payload"], {})
            normalized_action = f"bid:{rfq}:{payload['to']}"
            if treatment == "truck-transit":
                normalized_action += ":transit"
            deliveries[sent] = {**payload, "id": sent, "action": normalized_action,
                                "attempted_at": item["attempted_at"], "replies": []}
            if treatment == "corrected-postcode":
                assert "51447" in payload["body"] and "CGN" in payload["body"]
                deliveries[sent]["lane_override"] = "pickup from 51447 to CGN"
    for result in db.execute("""
      SELECT id,kind FROM mail WHERE root=? AND kind IN ('carrier','follow-up') ORDER BY id
    """, (rfq,)):
        reply = mailbox[result["id"]]
        parent = reply["in_reply_to"]
        if parent in extra_outbound and parent not in deliveries:
            original_send = extra_outbound[parent]
            action = f"pickup-postcode:{rfq}" if result["kind"] == "follow-up" else f"bid:{rfq}:{original_send['to']}"
            deliveries[parent] = {
                **original_send,
                "action": action,
                "attempted_at": original_send["received_at"],
                "replies": [],
            }
        if parent in deliveries:
            deliveries[parent]["replies"].append(reply)

    original_draft = stored(row["proposal"], {})
    actual_offer = original_draft.get("offer")
    if not any(send["action"] == f"quote:{rfq}" for send in deliveries.values()):
        actual_offer = None
    offers = stored(row["offers"], [])
    if actual_offer and actual_offer["source"] == "portal" and not any(
        offer["rate_id"] == actual_offer["rate_id"] for offer in offers
    ):
        assert actual_offer["rate_id"]
        offers.append({
            **actual_offer,
            "total_eur": round(actual_offer["total_eur"] - (original_draft.get("pickup_eur") or 0), 2),
            "days": actual_offer["days"] - (original_draft.get("pickup_days") or 0),
        })
    portal = [{"offer": offer, "available_at": (datetime.fromisoformat(offer["valid_until"]) -
                timedelta(minutes=15)).isoformat()} for offer in offers]
    for entry in portal:
        assert entry["offer"]["source"] == "portal" and entry["offer"]["valid_until"]
    rounds.append({
        "official": official,
        "rfq": original,
        "as_of_route_history": [message["id"] for message in history
            if message["id"] < rfq and datetime.fromisoformat(message["received_at"]) <= datetime.fromisoformat(original["received_at"])],
        "recorded_sends": list(deliveries.values()),
        "historical_portal": portal,
        "portal_complete": row["queried"] == -1,
        "historical_shipment": stored(row["fields"]),
        "historical_offer": actual_offer,
        "historical_stage": row["stage"],
        "historical_reason": row["reason"],
        "historical_operator": bool(row["operator"]),
        "historical_gateway": stored(row["gateway"]),
        "historical_gateway_state": row["gateway_state"],
        "historical_gateway_model": row["gateway_model"],
    })
assert len(rounds) == 30
(BASE / "input.json").write_text(json.dumps({"history": history, "rounds": rounds}, ensure_ascii=False))
print("Round inputs", len(rounds), "historical route-list messages", len(history),
      "recorded sends", sum(len(row["recorded_sends"]) for row in rounds),
      "portal offers", sum(len(row["historical_portal"]) for row in rounds))
