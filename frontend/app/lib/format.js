export const WINDOW_MS = 360_000;

export function transitLabel(days) {
  if (days == null) return "—";
  const hours = Math.round(days * 24);
  const whole = Math.floor(hours / 24);
  const rest = hours % 24;
  const parts = [];
  if (whole || !rest) parts.push(whole + (whole === 1 ? " day" : " days"));
  if (rest) parts.push(rest + "h");
  return parts.join(" ");
}

export const clock = (value) =>
  value ? new Date(value).toLocaleTimeString([], { hour12: false }) : "—";

export const eur = (n, digits = 0) =>
  n == null || n === ""
    ? "—"
    : new Intl.NumberFormat(undefined, { style: "currency", currency: "EUR", maximumFractionDigits: digits }).format(Number(n));

export const secondsLeft = (m, now = Date.now()) =>
  m.received ? Math.ceil((Date.parse(m.received) + WINDOW_MS - now) / 1000) : null;

export const ATTENTION_STAGES = ["review", "waiting for pickup", "waiting for air price", "operator review"];

// Answered without a quote: nothing is left to do for the operator.
export const ANSWERED_STAGES = ["declined", "waiting for documents"];
export const answered = (m) => m.kind === "request" && ANSWERED_STAGES.includes(m.stage);

// Only while the quote window is open can a person still change the outcome.
const windowOpen = (m, now) => {
  const left = secondsLeft(m, now);
  return left === null || left > 0;
};

export const needsAttention = (m, now = Date.now()) =>
  m.kind === "request" &&
  (ATTENTION_STAGES.includes(m.stage) || ["uncertain", "pending"].includes(m.delivery)) &&
  m.delivery !== "sent" &&
  !answered(m) &&
  windowOpen(m, now);

export function statusOf(m, now = Date.now()) {
  if (m.booking) return { label: "Booked", tone: "ok" };
  if (m.delivery === "sent") return { label: "Quoted", tone: "info" };
  if (m.delivery === "uncertain" || m.delivery === "pending") return { label: "Unconfirmed", tone: "warn" };
  if (m.stage === "declined") return { label: "Declined · done", tone: "ok" };
  if (m.stage === "waiting for documents") return { label: "Docs requested · done", tone: "ok" };
  if (needsAttention(m, now)) return { label: m.stage === "review" ? "Review" : "Waiting", tone: "warn" };
  if (m.kind === "request") {
    const left = secondsLeft(m, now);
    if (left !== null && left <= 0) return { label: "Expired", tone: "bad" };
    return { label: "Pricing", tone: "accent" };
  }
  if (m.kind === "tracking") return { label: "Tracking", tone: "muted" };
  if (m.kind === "spam") return { label: "Ignored", tone: "muted" };
  return { label: m.stage || m.kind, tone: "muted" };
}

export const kindLabel = { request: "Request", tracking: "Tracking", ratedesk: "Rate desk", "follow-up": "Follow-up", carrier: "Carrier", spam: "Spam" };
