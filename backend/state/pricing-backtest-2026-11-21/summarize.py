import json
import statistics
from collections import Counter
from pathlib import Path

root = Path(__file__).resolve().parent

def read(file):
    return json.loads((root / file).read_text())

def rounds(file):
    return {r["round"]: r for r in read(file)}

def quote(item):
    offer = item.get("replay_offer")
    if offer is None:
        return None
    return {
        key: offer[key] for key in ("airline", "source", "total_eur", "days", "billed_kg")
    } | {"seconds_from_rfq": item["replay_quote_seconds"]}

baseline = rounds("trace.json")
objective = rounds("objective-first/trace.json")
longer_wait = rounds("portal-wait-200/trace.json")
early_portal = rounds("trace-portal-sensitivity.json")
board = {row["round_no"]: row for row in read("../replay-2026-11-21/board-latest.json")["requests"]}
assert baseline.keys() == objective.keys() == longer_wait.keys() == early_portal.keys() == board.keys()
assert len(baseline) == 30

rows = []
for number, item in baseline.items():
    historical = (item.get("historical_offer") or {}).get("total_eur")
    replay = quote(item)
    observed = board[number]
    shipment = item.get("shipment") or {}
    comparison = {
        name: quote(case[number])
        for name, case in (
            ("customer_objective_first", objective),
            ("portal_wait_200_seconds", longer_wait),
            ("all_recorded_portal_visible_on_first_lookup", early_portal),
        )
    }
    rows.append({
        "round": number,
        "lane": f"{shipment.get('origin') or '?'}-{shipment.get('destination') or '?'}",
        "request_objective": shipment.get("preference"),
        "official_verdict_on_historical_response": observed["verdict"],
        "official_rejection_reason": observed["reason"],
        "historical_quote_eur": historical,
        "replay_status": item["replay_stage"],
        "replay_quote": replay,
        "replay_minus_historical_percent": (
            round(100 * (replay["total_eur"] - historical) / historical, 2)
            if historical and replay else None
        ),
        "recorded_portal_offers": item["historical_portal_offer_count"],
        "portal_offers_visible_before_replay_quote": max(
            (lookup["visible_offers"] for lookup in item["portal_trace"]), default=0
        ),
        "comparisons": comparison,
    })

def stats(quotes):
    present = [q for q in quotes if q]
    return {
        "quotes_sent": len(present),
        "median_seconds_from_rfq": statistics.median(q["seconds_from_rfq"] for q in present),
        "max_seconds_from_rfq": max(q["seconds_from_rfq"] for q in present),
    }

market = read("market-price-shadow.json")
matched = [row for row in market if "estimated_eur" in row]
result = {
    "worker_commit": "1333221",
    "market_memory_commit": "71b8d16",
    "historical_cohort": "5U rounds 1–30, 21 November 2026",
    "method": [
        "Replayed the Java worker with a virtual clock, archived replies, and recorded portal rates. "
        "Portal availability in the archive is inferred from expiry minus 15 minutes.",
        "Unobserved replies, missing attachments and missing portal offers limit replay coverage. "
        "Unrecorded advisory gateway checks were assumed to succeed.",
        "The 200-second and immediate-portal comparisons are offline counterfactuals. "
        "Official verdicts apply only to historical responses.",
        "The MarketMemory shadow test uses assumed weight brackets and no recorded dataset-version "
        "changes because the archive lacks portal bracket metadata and dataset versions.",
    ],
    "official_verdicts": dict(Counter(row["verdict"] for row in board.values())),
    "official_price_rejections": dict(Counter(
        row["reason"] for row in board.values() if row["reason"] in {"too_high", "too_low"}
    )),
    "replay_outcomes": dict(Counter(row["replay_status"] for row in rows)),
    "baseline": stats(row["replay_quote"] for row in rows),
    "paired_quotes": sum(row["replay_minus_historical_percent"] is not None for row in rows),
    "same_historical_price": sum(
        bool(row["replay_quote"] and row["replay_quote"]["total_eur"] == row["historical_quote_eur"])
        for row in rows
    ),
    "comparison_summary": {
        name: stats(row["comparisons"][name] for row in rows)
        | {"changed_quotes": [
            row["round"] for row in rows if row["comparisons"][name] != row["replay_quote"]
        ]}
        for name in rows[0]["comparisons"]
    },
    "market_memory": {
        "recorded_portal_offers": len(market),
        "predictable_offers": len(matched),
        "matched_rounds": sorted({row["round"] for row in matched}),
        "max_absolute_error_eur": max(abs(row["estimated_eur"] - row["actual_eur"]) for row in matched),
        "predictions": matched,
    },
    "rounds": rows,
}
(root / "backtest-results.json").write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n")
