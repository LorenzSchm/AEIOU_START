// 5U challenge scoreboard, derived from message.board_result (backend BoardWatcher). This is the
// challenge's evaluation of our quotes, kept apart from the product views on purpose.

import { boardTotal } from "../components/case/BoardScore";

/** Scored criteria with their maximum points per accepted quote (5U challenge brief). */
export const CRITERIA = [
  { key: "accuracy", label: "Price accuracy", max: 45, hint: "Full at the best price" },
  { key: "cost", label: "LLM cost", max: 40, hint: "Full at no gateway spend" },
  { key: "time", label: "Response time", max: 15, hint: "Full within 2 minutes" },
];

/** Extra points beyond the three criteria. */
export const BONUSES = [
  { key: "tracking", label: "Tracking answers", each: 10 },
  { key: "humanity", label: "Most human email", each: 10 },
];

const REASONS = {
  too_low: "Price too low",
  too_high: "Price too high",
  no_pdf_offer: "No PDF offer",
  wrong_language: "Wrong language",
  excluded_airline: "Excluded airline",
  dg_docs_missing: "DG documents missing",
};
export const reasonLabel = (reason) => {
  if (!reason) return "No reason given";
  if (REASONS[reason]) return REASONS[reason];
  const words = String(reason).replaceAll("_", " ");
  return words[0].toUpperCase() + words.slice(1);
};

const mean = (values) => (values.length ? values.reduce((a, b) => a + b, 0) / values.length : null);

export function scoreModel(messages) {
  const rounds = messages
    .filter((m) => m.board_result)
    .map((m) => ({ id: m.id, received: m.received, result: m.board_result, total: boardTotal(m.board_result) }))
    .sort((a, b) => (a.result.round_no ?? 0) - (b.result.round_no ?? 0) || (Date.parse(a.received) || 0) - (Date.parse(b.received) || 0));

  const verdict = (r) => String(r.result.verdict || "").toLowerCase();
  const accepted = rounds.filter((r) => verdict(r) === "accepted");
  const rejected = rounds.filter((r) => verdict(r) === "rejected");
  const unanswered = rounds.filter((r) => verdict(r) === "no answer");
  const points = (r) => r.result.points || {};

  const criteria = CRITERIA.map((c) => {
    const values = accepted.map((r) => Number(points(r)[c.key])).filter(Number.isFinite);
    return { ...c, avg: mean(values), full: values.filter((v) => v >= c.max - 0.005).length, n: values.length };
  });

  const bonuses = BONUSES.map((b) => {
    const values = rounds.map((r) => Number(points(r)[b.key])).filter(Number.isFinite);
    return { ...b, total: values.reduce((a, v) => a + v, 0), earned: values.filter((v) => v > 0).length, n: values.length };
  });

  const adjustments = rounds.map((r) => Number(points(r).adjustment)).filter(Number.isFinite);

  const reasons = new Map();
  for (const r of rejected) {
    const label = reasonLabel(r.result.reason);
    reasons.set(label, [...(reasons.get(label) || []), r]);
  }
  if (unanswered.length) reasons.set("No answer from the board", unanswered);
  const losses = [...reasons.entries()]
    .map(([label, list]) => ({ label, rounds: list, n: list.length, unanswered: list === unanswered }))
    .sort((a, b) => b.n - a.n);

  const total = rounds.reduce((a, r) => a + (r.total ?? 0), 0);
  return {
    rounds,
    total,
    accepted: accepted.length,
    rejected: rejected.length,
    unanswered: unanswered.length,
    perAccepted: accepted.length ? accepted.reduce((a, r) => a + (r.total ?? 0), 0) / accepted.length : null,
    criteria,
    bonuses,
    adjustment: { total: adjustments.reduce((a, v) => a + v, 0), n: adjustments.filter((v) => v !== 0).length },
    losses,
    pending: rounds.filter((r) => r.result.cost_pending).length,
    maxTotal: Math.max(100, ...rounds.map((r) => r.total ?? 0)),
  };
}
