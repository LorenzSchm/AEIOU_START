"""Scores a quote like the brief: accepted within 5% of the best price, 45 price / 15 time / 40 cost points.

- price: full 45 at or below the best price, linear down to 0 at +5%; above +5% (or below -5%) rejected.
- time: full 15 within 2 minutes of the RFQ, linear down to 0 at 6 minutes; after 6 minutes rejected (late).
- cost: the simulator cannot see LLM spend, so it awards the full 40 and reports it as "n/a".
- only the first reply in the customer's thread that states an amount counts; it must state exactly one
  total, in EUR.
"""
from __future__ import annotations

from dataclasses import dataclass

from .texts import money_amounts

TOLERANCE = 0.05
FULL_TIME_S, ZERO_TIME_S = 120.0, 360.0


@dataclass
class Score:
    verdict: str
    reason: str | None
    total: float | None
    currency: str | None
    points: dict


def quoted_total(body: str) -> tuple[float | None, str | None, str | None]:
    """(total, currency, problem) where problem is no_total / multiple_totals / wrong_currency."""
    amounts = money_amounts(body)
    if not amounts:
        return None, None, "no_total"
    distinct = {(round(v, 2), c) for v, c in amounts}
    if len(distinct) > 1:
        return amounts[0][0], amounts[0][1], "multiple_totals"
    value, currency = amounts[0]
    if currency != "EUR":
        return value, currency, "wrong_currency"
    return value, currency, None


def score(body: str, best: float | None, response_s: float) -> Score:
    total, currency, problem = quoted_total(body)
    if problem:
        return Score("rejected", problem, total, currency, {})
    if response_s > ZERO_TIME_S:
        return Score("rejected", "late", total, currency, {})
    if best is None:
        return Score("voided", "no_reference_price", total, currency, {})
    delta = total / best - 1
    if delta > TOLERANCE:
        return Score("rejected", "too_high", total, currency, {})
    if delta < -TOLERANCE:
        return Score("rejected", "too_low", total, currency, {})
    price = 45.0 if delta <= 0 else 45.0 * (1 - delta / TOLERANCE)
    if response_s <= FULL_TIME_S:
        time_points = 15.0
    else:
        time_points = 15.0 * (1 - (response_s - FULL_TIME_S) / (ZERO_TIME_S - FULL_TIME_S))
    return Score("accepted", None, total, currency,
                 {"price": round(price, 2), "time": round(time_points, 2), "cost": 40.0})
