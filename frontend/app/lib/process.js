// Pure derivation of the dispatch process for one customer request (no React).
// Input shapes follow backend Ledger.inbox() (see lib/mock.js for a fixture).

export const WINDOW_MS = 360_000;

const ATTENTION_STAGES = ["review", "operator review", "waiting for pickup", "waiting for air price"];
const CLARIFY_STAGES = ["waiting for customer", "waiting for documents"];
const ATTENTION_DELIVERY = ["uncertain", "pending", "rejected"];
const BID_REPLY_KINDS = ["carrier", "ratedesk"];

const ms = (value) => {
  const t = Date.parse(value);
  return Number.isFinite(t) ? t : null;
};
const clip = (text, max = 60) => {
  if (text == null) return undefined;
  const s = String(text).replace(/\s+/g, " ").trim();
  return s.length > max ? s.slice(0, max - 1) + "…" : s;
};
const plural = (n, one, many = one + "s") => n + " " + (n === 1 ? one : many);
const eur = (n) => "EUR " + Math.round(Number(n)).toLocaleString("en-US");
const threadOf = (m) => m?.thread_id ?? (m?.root != null && m.root !== m.id ? m.root : m?.id);
const shortCarrier = (name) =>
  name ? String(name).replace(/\s+(air\s*cargo|freight\s*airways|cargo|airways|airlines|skylift|wings)$/i, "").trim() || name : "";
const durationLabel = (sec) =>
  sec < 60 ? Math.max(0, Math.round(sec)) + " s" : Math.floor(sec / 60) + " m " + String(Math.round(sec % 60)).padStart(2, "0") + " s";
const earliest = (times) => times.map(ms).filter((t) => t != null).sort((a, b) => a - b)[0] ?? null;
const latest = (times) => times.map(ms).filter((t) => t != null).sort((a, b) => b - a)[0] ?? null;
const isoOf = (t) => (t == null ? undefined : new Date(t).toISOString());
// Separately priced shipments booked on their own leg (request.booking stays the single booking).
const bookedShipments = (request) => (request?.shipment_bookings || []).filter((l) => l.booking_reference);

function isTrucker(email, directory) {
  const known = directory?.carriers?.find((c) => c.email?.toLowerCase() === String(email).toLowerCase());
  if (known) return known.kind === "trucker";
  return /truck/i.test(String(email));
}

function quoteOf(request) {
  const quotes = (request.outgoing || []).filter((o) => o.kind === "quote");
  return quotes.at(-1) || null;
}

/** Quote delivery state: request.delivery wins, then the outgoing quote row. */
function quoteState(request) {
  return request.delivery || quoteOf(request)?.state || null;
}

/**
 * Overall outcome of a request.
 * Precedence: declined > booked > quoted > needs attention > expired > in progress.
 */
export function outcomeOf(request, now = Date.now()) {
  if (!request) return "in progress";
  if (request.stage === "declined") return "declined";
  if (request.booking?.booking_reference || request.stage === "booked" || bookedShipments(request).length) return "booked";
  const delivery = quoteState(request);
  if (delivery === "sent") return "quoted";
  if (ATTENTION_DELIVERY.includes(delivery) || ATTENTION_STAGES.includes(request.stage)) return "needs attention";
  const received = ms(request.received);
  if (received != null && now - received > WINDOW_MS) return "expired";
  return "in progress";
}

/** State used by the globe arcs. */
export function globeState(request, now = Date.now()) {
  const o = outcomeOf(request, now);
  return o === "booked" ? "booked"
    : o === "quoted" ? "quoted"
    : o === "needs attention" ? "attention"
    : o === "in progress" ? "active"
    : "closed";
}

/**
 * deriveProcess(request, allMessages, { now?, directory? })
 * → { steps, current, outcome, elapsedMs }
 * steps[i] = { key, label, status: "done"|"active"|"pending"|"skipped"|"failed", time?, detail?, count? }
 * current = key of the active/failed step, or null when the process is finished.
 * elapsedMs = received → quote (if sent) else received → now.
 */
export function deriveProcess(request, allMessages = [], options = {}) {
  const now = options.now ?? Date.now();
  const directory = options.directory;
  const thread = threadOf(request);
  const received = ms(request.received);
  const fields = request.fields || null;
  const outgoing = request.outgoing || [];
  const inThread = (allMessages || []).filter((m) => m.id !== request.id && threadOf(m) === thread);
  const outcome = outcomeOf(request, now);
  const steps = [];

  // 1. received
  steps.push({ key: "received", label: "Request received", status: received != null ? "done" : "pending", time: request.received || undefined, detail: clip(request.sender) });

  // 2. understood
  const understood = Boolean(fields?.origin && fields?.destination && Number(fields?.weight_kg) > 0);
  const firstOut = earliest(outgoing.map((o) => o.time));
  {
    const parts = [];
    if (fields?.origin || fields?.destination) parts.push((fields.origin || "?") + " → " + (fields.destination || "?"));
    if (fields?.weight_kg) parts.push(Math.round(Number(fields.weight_kg) * 10) / 10 + " kg");
    if (fields?.preference) parts.push(fields.preference);
    const noFields = !fields || !understood;
    steps.push({
      key: "understood", label: "Shipment understood",
      status: understood ? "done" : noFields && request.stage === "review" ? "failed" : "pending",
      time: understood ? isoOf(firstOut) : undefined,
      detail: clip(parts.join(" · ")) || (request.stage === "review" && noFields ? clip(request.review_reason || "Fields could not be extracted") : undefined),
    });
  }

  // 3. clarification (customer follow-ups / waiting stages)
  const followUps = inThread.filter((m) => m.kind === "follow-up");
  const clarifying = CLARIFY_STAGES.includes(request.stage);
  steps.push({
    key: "clarification", label: "Clarification",
    status: clarifying ? "active" : followUps.length ? "done" : "skipped",
    time: followUps.length ? isoOf(latest(followUps.map((m) => m.received))) : undefined,
    detail: clarifying
      ? clip(request.stage === "waiting for documents" ? "Waiting for documents" : request.review_reason || "Waiting for customer reply")
      : followUps.length ? plural(followUps.length, "customer reply", "customer replies") : "not needed",
    count: followUps.length || undefined,
  });

  // 4. bids requested
  const bidRows = outgoing.filter((o) => o.kind === "bid");
  const bidEmails = new Set([
    ...bidRows.map((o) => String(o.recipient || o.action?.split(":")[2] || "").toLowerCase()).filter(Boolean),
    ...Object.keys(request.bid_requests || {}).map((e) => e.toLowerCase()),
  ]);
  const requested = Math.max(bidEmails.size, bidRows.length);
  const truckers = [...bidEmails].filter((e) => isTrucker(e, directory)).length;
  const airlines = bidEmails.size - truckers;
  const failedBids = Object.values(request.bid_requests || {}).filter((s) => s === "rejected" || s === "uncertain").length;
  {
    const parts = [];
    if (airlines) parts.push(plural(airlines, "airline"));
    if (truckers) parts.push(plural(truckers, "trucker"));
    if (failedBids) parts.push(failedBids + " not delivered");
    const portalDirect = !requested && request.proposal?.offer?.source && request.proposal.offer.source !== "email bid";
    steps.push({
      key: "bids-requested", label: "Bids requested",
      status: requested ? "done" : portalDirect || quoteState(request) ? "skipped" : "pending",
      time: isoOf(earliest(bidRows.map((o) => o.time))),
      detail: requested ? clip(parts.join(" · ")) : portalDirect ? "not needed · " + request.proposal.offer.source : undefined,
      count: requested || undefined,
    });
  }

  // 5. bids received
  const replies = inThread.filter((m) => BID_REPLY_KINDS.includes(m.kind));
  const answered = Object.values(request.bid_requests || {}).filter((s) => s === "priced" || s === "unreadable").length;
  const unreadable = Object.values(request.bid_requests || {}).filter((s) => s === "unreadable").length
    + replies.filter((m) => m.stage === "bid unreadable").length;
  const got = Math.max(replies.length, answered);
  {
    const bidsSkipped = steps[3].status === "skipped";
    let detail;
    if (got) detail = got + (requested ? " of " + requested : "") + " answered" + (unreadable ? " · " + unreadable + " unreadable" : "");
    else if (requested) detail = "0 of " + requested + " answered";
    steps.push({
      key: "bids-received", label: "Bids received",
      status: got ? "done" : bidsSkipped ? "skipped" : "pending",
      time: isoOf(latest(replies.map((m) => m.received))),
      detail: bidsSkipped && !got ? "not needed" : clip(detail),
      count: got || undefined,
    });
  }

  // 6. priced
  const offer = request.proposal?.offer;
  {
    const parts = [];
    if (offer?.total_eur != null) parts.push(eur(offer.total_eur));
    if (offer?.airline) parts.push(shortCarrier(offer.airline));
    if (offer?.source) parts.push(offer.source);
    steps.push({
      key: "priced", label: "Priced",
      status: offer ? "done" : "pending",
      detail: clip(parts.join(" · ")) || undefined,
    });
  }

  // 7. quoted
  const quote = quoteOf(request);
  const qState = quoteState(request);
  const qTime = ms(quote?.time);
  const quoteSec = qTime != null && received != null ? (qTime - received) / 1000 : null;
  {
    let status = "pending";
    let detail;
    if (qState === "sent") {
      status = "done";
      detail = quoteSec != null ? "in " + durationLabel(quoteSec) + (quoteSec <= WINDOW_MS / 1000 ? "" : " · late") : "sent";
    } else if (qState === "uncertain" || qState === "rejected") {
      status = "failed";
      detail = qState === "uncertain" ? "Delivery unconfirmed" : "Delivery rejected";
    } else if (qState === "pending") {
      detail = "Sending…";
    }
    steps.push({ key: "quoted", label: "Quote sent", status, time: quote?.time || undefined, detail: clip(detail) });
  }

  // 8. booked
  const rateId = request.booking?.rate_id || offer?.rate_id;
  const legs = bookedShipments(request);
  const legCount = request.shipment_bookings?.length || 0;
  steps.push({
    key: "booked", label: "Booked",
    status: request.booking?.booking_reference || legs.length ? "done" : rateId ? "pending" : "skipped",
    time: request.booking?.booked_at || request.booking?.time || undefined,
    detail: request.booking?.booking_reference ? clip(request.booking.booking_reference)
      : legs.length ? clip((legs.length < legCount ? legs.length + " of " + legCount + " · " : "")
        + legs.map((l) => l.booking_reference).join(", "))
      : rateId ? "on customer instruction" : "not bookable (no rate ID)",
  });

  // Active / failed marker
  const finished = ["booked", "quoted", "declined"].includes(outcome);
  const firstOpen = steps.find((s) => s.status === "failed") || steps.find((s) => s.status === "active") || steps.find((s) => s.status === "pending");
  let current = null;
  if (outcome === "declined") {
    if (firstOpen) {
      firstOpen.status = "failed";
      firstOpen.detail = clip(request.review_reason || "Declined");
      current = firstOpen.key;
    }
  } else if (!finished && firstOpen) {
    current = firstOpen.key;
    if (outcome === "needs attention") {
      firstOpen.status = "failed";
      if (request.review_reason) firstOpen.detail = clip(request.review_reason);
      else if (!firstOpen.detail) firstOpen.detail = clip(request.stage);
    } else if (outcome === "expired") {
      firstOpen.status = "failed";
      firstOpen.detail = "Window closed";
    } else if (firstOpen.status === "pending") {
      firstOpen.status = "active";
    }
  }
  // A single active step at most; any other "active" (e.g. clarification while something failed) becomes pending.
  for (const s of steps) if (s.status === "active" && s.key !== current) s.status = "pending";

  const elapsedMs = received == null ? 0 : Math.max(0, (qState === "sent" && qTime != null ? qTime : now) - received);
  return { steps, current, outcome, elapsedMs };
}

/** KPI counters across the whole mailbox. */
export function waveSummary(messages = [], options = {}) {
  const now = options.now ?? Date.now();
  const requests = (messages || []).filter((m) => m.kind === "request");
  const counts = { quoted: 0, booked: 0, attention: 0, inProgress: 0, expired: 0, declined: 0 };
  const quoteSecs = [];
  let onTime = 0;
  let savedVsNext = 0;
  const bySource = { portal: 0, tariff: 0, "email bid": 0 };
  const dest = new Map();
  for (const r of requests) {
    const o = outcomeOf(r, now);
    if (o === "booked") counts.booked++, counts.quoted++;
    else if (o === "quoted") counts.quoted++;
    else if (o === "needs attention") counts.attention++;
    else if (o === "expired") counts.expired++;
    else if (o === "declined") counts.declined++;
    else counts.inProgress++;

    const qt = ms(quoteOf(r)?.time);
    const rt = ms(r.received);
    if ((o === "quoted" || o === "booked") && qt != null && rt != null) {
      const sec = (qt - rt) / 1000;
      quoteSecs.push(sec);
      if (sec <= WINDOW_MS / 1000) onTime++;
    }
    const offer = r.proposal?.offer;
    if (offer?.source) bySource[offer.source] = (bySource[offer.source] || 0) + 1;
    const totals = (r.proposal?.alternatives || []).map((a) => Number(a.total_eur)).filter(Number.isFinite).sort((a, b) => a - b);
    if (totals.length >= 2) savedVsNext += totals[1] - totals[0];
    const code = r.fields?.destination;
    if (code) dest.set(code, (dest.get(code) || 0) + 1);
  }
  quoteSecs.sort((a, b) => a - b);
  const mid = quoteSecs.length >> 1;
  const median = !quoteSecs.length ? null
    : quoteSecs.length % 2 ? quoteSecs[mid] : (quoteSecs[mid - 1] + quoteSecs[mid]) / 2;
  return {
    requests: requests.length,
    ...counts,
    medianQuoteSeconds: median == null ? null : Math.round(median),
    onTimeRate: quoteSecs.length ? onTime / quoteSecs.length : null,
    savedVsNext: Math.round(savedVsNext),
    bySource,
    byDestination: [...dest].map(([code, n]) => ({ code, n })).sort((a, b) => b.n - a.n || a.code.localeCompare(b.code)),
  };
}
