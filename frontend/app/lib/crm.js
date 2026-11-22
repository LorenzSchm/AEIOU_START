// Pure CRM derivation over the /messages payload (no React): bookings, customers, won/lost funnel.
// Everything is computed client-side from fields the backend already returns.

import { outcomeOf } from "./process";

const ms = (value) => {
  const t = Date.parse(value);
  return Number.isFinite(t) ? t : null;
};
const num = (v) => (v == null || v === "" || !Number.isFinite(Number(v)) ? null : Number(v));
const threadOf = (m) => m?.thread_id ?? (m?.root != null && m.root !== m.id ? m.root : m?.id);
const words = (s) => String(s || "").replaceAll("_", " ");

/** "lindqvist-medtech@hackloop.mail" → "Lindqvist Medtech". */
export function customerName(email) {
  const local = String(email || "").split("@")[0];
  if (!local) return "Unknown sender";
  return local
    .split(/[-_.]+/)
    .filter(Boolean)
    .map((w) => w[0].toUpperCase() + w.slice(1))
    .join(" ");
}

export const initials = (name) =>
  String(name || "?")
    .split(" ")
    .slice(0, 2)
    .map((w) => w[0])
    .join("")
    .toUpperCase();

// Board rejection reasons → operator-readable loss labels.
const BOARD_REASONS = {
  too_low: "Price too low",
  too_high: "Price too high",
  no_pdf_offer: "No PDF offer",
  wrong_language: "Wrong language",
  excluded_airline: "Excluded airline",
  dg_docs_missing: "DG documents missing",
};

const CONFIRM_RE = /\b(book|buchen|gebucht|réserv|reserv|proceed|go ahead|confirm)/i;

/**
 * Booking time is not in the API. Best proxy: the customer's booking instruction, i.e. the first
 * follow-up in the thread after the quote that asks to book (else the first follow-up after it).
 */
function bookedAtOf(quoteTime, followUps) {
  const after = followUps.filter((f) => quoteTime == null || (ms(f.received) ?? 0) >= quoteTime);
  const hit = after.find((f) => CONFIRM_RE.test(f.body || "")) || after[0];
  return hit ? ms(hit.received) : null;
}

/**
 * Where a request ended up. group: "won" | "lost" | "open".
 * key/label group the loss reasons; tone follows the chip palette.
 */
/**
 * DeskSummary.bookingOf: the request's own booking, else one entry over its separately booked
 * shipments (references and airlines joined, air prices added, status "booked 1/2" while partial).
 */
function bookingOf(r) {
  if (r.booking?.booking_reference) return r.booking;
  const legs = r.shipment_bookings || [];
  const booked = legs.filter((l) => l.booking_reference);
  if (!booked.length) return null;
  const distinct = (key) => [...new Set(booked.map((l) => l[key]).filter(Boolean))];
  const prices = booked.map((l) => num(l.price));
  const codes = distinct("airline_code"), currencies = distinct("currency");
  return {
    booking_reference: booked.map((l) => l.booking_reference).join(" · "),
    team_reference: distinct("team_reference").join(" · ") || null,
    airline: distinct("airline").join(" · ") || null,
    airline_code: codes.length === 1 ? codes[0] : null,
    price: prices.some((p) => p == null) ? null : prices.reduce((s, p) => s + p, 0),
    currency: currencies.length === 1 ? currencies[0] : null,
    status: booked.length === legs.length ? "booked" : "booked " + booked.length + "/" + legs.length,
    shipments_booked: booked.length,
    shipments: legs.length,
  };
}

function resultOf(r, { quoted, followUpsAfterQuote, now }) {
  if (r.booking?.booking_reference) return { group: "won", key: "booked", label: "Booked", tone: "ok" };
  const split = bookingOf(r);
  if (split) return { group: "won", key: "booked", label: split.status === "booked" ? "Booked" : "Booked " + split.shipments_booked + "/" + split.shipments, tone: "ok" };
  const b = r.board_result;
  const verdict = String(b?.verdict || "").toLowerCase();
  if (verdict === "rejected") {
    const key = b.reason || "rejected";
    return { group: "lost", key, label: BOARD_REASONS[key] || words(key) || "Rejected by board", tone: "bad" };
  }
  if (/^booking needs attention/i.test(r.review_reason || ""))
    return { group: "lost", key: "booking_blocked", label: "Booking blocked · carrier changed", tone: "warn" };
  if (["waiting for documents", "waiting for customer"].includes(r.stage) || (r.stage === "review" && !quoted))
    return { group: "open", key: "hold", label: r.stage === "waiting for customer" ? "Waiting for customer" : "On hold · documents", tone: "warn" };
  const outcome = outcomeOf(r, now);
  if (!quoted) {
    if (outcome === "expired" || r.stage === "expired") return { group: "lost", key: "expired", label: "Expired before quote", tone: "bad" };
    if (outcome === "declined") return { group: "lost", key: "declined", label: "Declined by us", tone: "muted" };
    if (outcome === "needs attention") return { group: "open", key: "attention", label: "Needs attention", tone: "warn" };
    return { group: "open", key: "pricing", label: "Pricing", tone: "accent" };
  }
  // Quoted, accepted (or not yet judged), not booked.
  if (b?.closed && b?.booked === false)
    return followUpsAfterQuote.length
      ? { group: "lost", key: "not_booked", label: "Customer reply, not booked", tone: "warn" }
      : { group: "lost", key: "no_reply", label: "No customer reply", tone: "muted" };
  if (verdict === "no answer") return { group: "lost", key: "no_reply", label: "No customer reply", tone: "muted" };
  return { group: "open", key: "awaiting", label: "Awaiting customer", tone: "info" };
}

/**
 * crmModel(messages) → { deals, bookings, customers, funnel, reasons, kpis }
 * deals: one row per customer request (RFQ) with its commercial outcome.
 */
export function crmModel(messages = [], now = Date.now()) {
  const byThread = new Map();
  for (const m of messages) {
    const k = threadOf(m);
    if (!byThread.has(k)) byThread.set(k, []);
    byThread.get(k).push(m);
  }

  const deals = messages
    .filter((m) => m.kind === "request")
    .map((r) => {
      const thread = byThread.get(threadOf(r)) || [];
      const quotes = (r.outgoing || []).filter((o) => o.kind === "quote" || o.kind === "requote");
      const firstQuote = quotes.map((q) => ms(q.time)).filter((t) => t != null).sort((a, b) => a - b)[0] ?? null;
      const quoted = r.delivery === "sent" || quotes.some((q) => q.state === "sent");
      const received = ms(r.received);
      const followUps = thread
        .filter((m) => m.kind === "follow-up")
        .sort((a, b) => (ms(a.received) ?? 0) - (ms(b.received) ?? 0));
      const followUpsAfterQuote = firstQuote == null ? [] : followUps.filter((f) => (ms(f.received) ?? 0) >= firstQuote);
      const offer = r.proposal?.offer || null;
      const allIn = num(offer?.total_eur);
      const pickup = num(r.proposal?.pickup_eur) ?? 0;
      const airPrice = num(r.booking?.price);
      // Booked all-in: air price actually booked plus the quoted pickup leg (falls back to the quote).
      const booking = bookingOf(r);
      const bookedAllIn = booking && booking !== r.booking
        // Separately booked shipments: each booked air price plus that shipment's quoted pickup.
        ? (r.shipment_bookings || []).filter((l) => l.booking_reference).reduce((s, l) => {
          const air = num(l.price);
          return s + (air != null ? air + (num(l.pickup_eur) ?? 0) : num(l.total_eur) ?? 0);
        }, 0)
        : r.booking ? (airPrice != null ? airPrice + pickup : allIn) : null;
      const lastActivity = Math.max(
        ...thread.map((m) => ms(m.received) ?? 0),
        ...(r.outgoing || []).map((o) => ms(o.time) ?? 0),
        received ?? 0,
      );
      return {
        id: r.id,
        m: r,
        email: String(r.sender || "").toLowerCase(),
        customer: customerName(r.sender),
        origin: r.fields?.origin || null,
        destination: r.fields?.destination || null,
        weightKg: num(r.fields?.weight_kg),
        received,
        quoted,
        quotedAt: firstQuote,
        quoteSec: quoted && firstQuote != null && received != null ? (firstQuote - received) / 1000 : null,
        allIn,
        bookedAllIn,
        carrier: booking?.airline || r.booking?.airline || offer?.airline || null,
        days: num(offer?.days),
        accepted: quoted && String(r.board_result?.verdict || "").toLowerCase() === "accepted",
        board: r.board_result || null,
        booking,
        bookedAt: booking ? bookedAtOf(firstQuote, followUps) : null,
        lastActivity: lastActivity || null,
        result: resultOf(r, { quoted, followUpsAfterQuote, now }),
      };
    });

  const bookings = deals.filter((d) => d.booking);

  const customers = new Map();
  for (const d of deals) {
    if (!customers.has(d.email))
      customers.set(d.email, {
        email: d.email, name: d.customer, requests: 0, quoted: 0, won: 0, lost: 0, open: 0,
        quotedValue: 0, bookedValue: 0, lastActivity: null, latestId: null, lanes: new Map(),
      });
    const c = customers.get(d.email);
    c.requests++;
    if (d.quoted) {
      c.quoted++;
      c.quotedValue += d.allIn ?? 0;
    }
    if (d.result.group === "won") {
      c.won++;
      c.bookedValue += d.bookedAllIn ?? 0;
    } else if (d.result.group === "lost") c.lost++;
    else c.open++;
    if (d.lastActivity && (!c.lastActivity || d.lastActivity > c.lastActivity)) {
      c.lastActivity = d.lastActivity;
      c.latestId = d.id;
    }
    if (d.destination) c.lanes.set(d.destination, (c.lanes.get(d.destination) || 0) + 1);
  }
  const customerList = [...customers.values()].map((c) => ({
    ...c,
    winRate: c.won + c.lost ? c.won / (c.won + c.lost) : null,
    topDestination: [...c.lanes].sort((a, b) => b[1] - a[1])[0]?.[0] ?? null,
  }));

  const funnel = [
    { key: "rfq", label: "RFQs received", n: deals.length },
    { key: "quoted", label: "Quoted", n: deals.filter((d) => d.quoted).length },
    { key: "accepted", label: "Accepted by board", n: deals.filter((d) => d.accepted).length },
    { key: "booked", label: "Booked", n: bookings.length },
  ];

  const reasonMap = new Map();
  for (const d of deals) {
    if (d.result.group === "won") continue;
    const k = d.result.group + ":" + d.result.key;
    if (!reasonMap.has(k)) reasonMap.set(k, { id: k, ...d.result, n: 0, value: 0 });
    const r = reasonMap.get(k);
    r.n++;
    r.value += d.allIn ?? 0;
  }
  const reasons = [...reasonMap.values()].sort((a, b) => b.n - a.n || a.label.localeCompare(b.label));

  const quoteSecs = deals.map((d) => d.quoteSec).filter((s) => s != null).sort((a, b) => a - b);
  const mid = quoteSecs.length >> 1;
  const quotedCount = funnel[1].n;
  const bookedValue = bookings.reduce((s, d) => s + (d.bookedAllIn ?? 0), 0);
  const open = deals.filter((d) => d.result.group === "open");
  const kpis = {
    conversion: deals.length ? bookings.length / deals.length : null,
    quoteToBook: quotedCount ? bookings.length / quotedCount : null,
    bookedValue,
    avgBooking: bookings.length ? bookedValue / bookings.length : null,
    avgQuoteSec: quoteSecs.length ? quoteSecs.reduce((a, b) => a + b, 0) / quoteSecs.length : null,
    medianQuoteSec: !quoteSecs.length ? null : quoteSecs.length % 2 ? quoteSecs[mid] : (quoteSecs[mid - 1] + quoteSecs[mid]) / 2,
    fastQuotes: quoteSecs.filter((s) => s <= 120).length,
    quoteCount: quoteSecs.length,
    quotedValue: deals.reduce((s, d) => s + (d.quoted ? d.allIn ?? 0 : 0), 0),
    openCount: open.length,
    openValue: open.reduce((s, d) => s + (d.allIn ?? 0), 0),
    lostCount: deals.filter((d) => d.result.group === "lost").length,
    lostValue: deals.reduce((s, d) => s + (d.result.group === "lost" && d.quoted ? d.allIn ?? 0 : 0), 0),
  };

  return { deals, bookings, customers: customerList, funnel, reasons, kpis };
}
