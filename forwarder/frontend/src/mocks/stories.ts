/**
 * Scripted mock scenarios. Each story walks one session through the same
 * steps the real agents take (ARCHITECTURE.md §4) and writes the same event
 * kinds, so the UI exercises every code path it will see in production.
 */
import type { BidRequest, Booking, MailDetail, PriceCandidate, PriceSource, Rfq } from "@/api/generated/model";
import * as T from "./mails";
import {
  AIRPORTS,
  CARRIERS,
  COMMODITIES,
  CUSTOMERS,
  MAILBOX,
  PERSONA,
  PICKUP,
  PORTAL_ADDR,
  REGION_RATE,
  REGION_TRANSIT,
  SHEET_AIRLINES,
  TRACKING_ADDR,
  airlineCode,
  type Customer,
  type Origin,
} from "./reference";
import {
  addEvent,
  addLlmCall,
  addMail,
  between,
  createSession,
  db,
  intBetween,
  money2,
  next,
  nowIso,
  patchMail,
  patchSession,
  pick,
  rand,
  session,
  shuffle,
  sleep,
  waitForSignal,
} from "./store";

const SEC = 1000;
const MIN = 60 * SEC;
const hex = (n: number) => Array.from({ length: n }, () => Math.floor(rand() * 16).toString(16)).join("");

export type Outcome = "booked" | "rejected" | "quoted" | "expired";

export interface RfqSpec {
  customer: Customer;
  origin: Origin;
  dest: string;
  door: boolean;
  kg: number;
  cbm: number;
  pieces: number;
  commodity: number;
  mode: "cheapest" | "fastest";
  outcome: Outcome;
  quoteAfterS: number;
  ambiguous?: boolean;
  manual?: boolean;
  confirmAfterS?: number;
  portal503?: boolean;
}

// ───────────────────────────── pricing ─────────────────────────────

interface Quote {
  source: PriceSource;
  airline: string | null;
  totalEur: number;
  total: number;
  currency: string;
  transit: number;
  rateId: string | null;
  breakdown: Record<string, unknown>;
}

const chargeable = (kg: number, cbm: number) => Math.max(kg, Math.round(cbm * 167 * 10) / 10);

function airQuote(airline: string, spec: RfqSpec, source: PriceSource, factor = 1): Quote {
  const region = AIRPORTS[spec.dest]?.region ?? "AS";
  const ch = chargeable(spec.kg, spec.cbm);
  const breakKg = [45, 100, 250, 500, 1000, 2000].filter((b) => b <= ch).pop() ?? 0;
  const base = REGION_RATE[region] * between(0.82, 1.22) * factor;
  const breakFactor = breakKg >= 1000 ? 0.62 : breakKg >= 500 ? 0.72 : breakKg >= 250 ? 0.85 : breakKg >= 100 ? 1 : breakKg >= 45 ? 1.18 : 1.4;
  const rate = Math.round(base * breakFactor * 100) / 100;
  const fuel = Math.round(between(0.62, 1.15) * 100) / 100;
  const security = Math.round(between(0.1, 0.22) * 100) / 100;
  const minimum = Math.round(between(70, 130));
  const freight = Math.max(rate * ch, minimum);
  const totalEur = Math.round((freight + (fuel + security) * ch) * 100) / 100;
  const usd = source === "portal" && rand() < 0.3;
  const fx = 0.9234;
  const [tmin, tmax] = REGION_TRANSIT[region];
  return {
    source,
    airline,
    totalEur,
    total: usd ? Math.round((totalEur / fx) * 100) / 100 : totalEur,
    currency: usd ? "USD" : "EUR",
    transit: intBetween(tmin, tmax),
    rateId: source === "portal" ? `R-${hex(10)}` : null,
    breakdown: {
      chargeableKg: ch,
      break: breakKg === 0 ? "-45" : `+${breakKg}`,
      ratePerKg: rate.toFixed(2),
      minimum: minimum.toFixed(2),
      freight: freight.toFixed(2),
      fuelPerKg: fuel.toFixed(2),
      securityPerKg: security.toFixed(2),
      ...(usd ? { fxEurPerUsd: fx } : {}),
    },
  };
}

function airlinesFor(dest: string) {
  return CARRIERS.filter((c) => c.kind === "airline" && c.serves.includes(dest));
}

function addCandidate(sessionId: number, q: Quote): PriceCandidate {
  const c: PriceCandidate = {
    id: next("cand"),
    source: q.source,
    airlineCode: q.airline ? airlineCode(q.airline) : null,
    airlineName: q.airline,
    total: money2(q.total),
    currency: q.currency,
    totalEur: money2(q.totalEur),
    transitDays: q.transit,
    rateId: q.rateId,
    validUntil: q.rateId ? new Date(db().clock.now() + 15 * MIN).toISOString() : null,
    breakdown: q.breakdown,
    chosen: false,
    rejectReason: null,
    createdAt: nowIso(),
  };
  const list = db().candidates.get(sessionId) ?? [];
  db().candidates.set(sessionId, [...list, c]);
  return c;
}

function choose(sessionId: number, mode: "cheapest" | "fastest"): PriceCandidate {
  const list = (db().candidates.get(sessionId) ?? []).filter((c) => c.source !== "truck");
  const sorted = [...list].sort((a, b) =>
    mode === "cheapest"
      ? Number(a.totalEur) - Number(b.totalEur)
      : (a.transitDays ?? 99) - (b.transitDays ?? 99) || Number(a.totalEur) - Number(b.totalEur),
  );
  const winner = sorted[0];
  const updated = (db().candidates.get(sessionId) ?? []).map((c) => {
    if (c.id === winner.id) return { ...c, chosen: true };
    if (c.source === "truck") return { ...c, rejectReason: "Pickup leg, added to the air price" };
    const diff = Number(c.totalEur) - Number(winner.totalEur);
    const reason =
      mode === "fastest" && (c.transitDays ?? 99) > (winner.transitDays ?? 99)
        ? `Slower: ${c.transitDays}d vs ${winner.transitDays}d`
        : `€${diff.toFixed(2)} above the chosen option`;
    return { ...c, rejectReason: reason };
  });
  db().candidates.set(sessionId, updated);
  return { ...winner, chosen: true };
}

// ───────────────────────────── helpers ─────────────────────────────

function factsOf(spec: RfqSpec, readyDate: Date): T.RfqFacts {
  const pickup = spec.door ? (PICKUP.find((p) => p.airport === spec.origin) ?? PICKUP[0]) : null;
  return { ...spec, pickup: pickup ? { zip: pickup.zip, city: pickup.city } : null, readyDate };
}

function routerCall(sessionId: number, mail: MailDetail, type: string, confidence: number, extract: Record<string, unknown>) {
  return addLlmCall(sessionId, {
    purpose: "route+extract",
    model: "gpt-4o-mini",
    promptTokens: intBetween(780, 1150),
    completionTokens: intBetween(110, 230),
    latencyMs: intBetween(620, 1480),
    request: {
      model: "gpt-4o-mini",
      temperature: 0,
      response_format: { type: "json_schema", json_schema: { name: "route_and_extract", strict: true } },
      messages: [
        {
          role: "system",
          content:
            "You route inbound mail for an air freight forwarder. Classify it as NEW_RFQ, ACCEPTANCE, FOLLOW_UP, CARRIER_BID, TARIFF, TRACKING_QUESTION or OTHER, give a confidence, and extract the shipment facts. Answer with JSON only.",
        },
        { role: "user", content: `From: ${mail.fromAddr}\nSubject: ${mail.subject}\n\n${mail.body}` },
      ],
    },
    response: {
      id: `chatcmpl-${hex(12)}`,
      choices: [{ index: 0, finish_reason: "stop", message: { role: "assistant", content: JSON.stringify({ type, confidence, ...extract }) } }],
    },
  });
}

function writerCall(sessionId: number, purpose: string, facts: Record<string, unknown>, body: string) {
  return addLlmCall(sessionId, {
    purpose,
    model: "gpt-4o-mini",
    promptTokens: intBetween(520, 760),
    completionTokens: intBetween(140, 260),
    latencyMs: intBetween(1100, 2600),
    request: {
      model: "gpt-4o-mini",
      temperature: 0.7,
      messages: [
        {
          role: "system",
          content: `You are ${PERSONA.name} on the ${PERSONA.company} desk. Write a short, warm, natural reply in the customer's language and register. No bullet lists, no clichés. Use the facts exactly as given; never change a number.`,
        },
        { role: "user", content: JSON.stringify(facts, null, 2) },
      ],
    },
    response: {
      id: `chatcmpl-${hex(12)}`,
      choices: [{ index: 0, finish_reason: "stop", message: { role: "assistant", content: body } }],
    },
  });
}

function addBoard(sessionId: number, verdict: string, reasonCode: string | null, points: Record<string, number> | null) {
  const s = session(sessionId);
  const round = db().board.length + 1;
  db().board.push({
    requestKey: `req-${String(round).padStart(3, "0")}`,
    sessionId,
    publicRef: s.publicRef,
    round: `R${round}`,
    verdict,
    reasonCode,
    points,
    fetchedAt: nowIso(),
  });
  patchSession(sessionId, { boardVerdict: verdict });
  const pts = points ? Object.values(points).reduce((a, b) => a + b, 0) : 0;
  addEvent(sessionId, "board.verdict", `Board: ${verdict}${reasonCode ? ` (${reasonCode})` : ""}${points ? ` · ${pts.toFixed(1)} pts` : ""}`, {
    payload: { round: `R${round}`, verdict, reasonCode, points },
  });
}

// ───────────────────────────── RFQ story ─────────────────────────────

export async function rfqStory(spec: RfqSpec): Promise<void> {
  const ready = new Date(db().clock.now() + intBetween(0, 3) * 86_400_000);
  const facts = factsOf(spec, ready);
  const subject = spec.ambiguous ? "Re: our call this morning" : T.rfqSubject(facts);
  const body = spec.ambiguous ? T.ambiguousBody(facts) : T.rfqBody(facts);
  const lane = `${spec.origin} → ${spec.dest}`;

  const s = createSession({
    type: "RFQ",
    customerAddr: spec.customer.email,
    subject,
    deadlineAt: new Date(db().clock.now() + 6 * MIN).toISOString(),
  });
  const created = db().clock.now();
  const inbound = addMail({
    sessionId: s.id,
    direction: "in",
    from: spec.customer.email,
    to: MAILBOX,
    subject,
    body,
    attachments:
      spec.pieces > 4 && !spec.ambiguous
        ? [
            {
              filename: "packing-list.txt",
              contentType: "text/plain",
              body: Array.from({ length: spec.pieces }, (_, i) => `#${i + 1}  ${Math.round(spec.kg / spec.pieces)} kg  120x80x${intBetween(60, 140)} cm`).join("\n"),
            },
          ]
        : [],
  });
  addEvent(s.id, "mail.in", `${spec.customer.name}: ${subject}`, {
    mailId: inbound.id,
    payload: { mailId: inbound.id, externalId: inbound.externalId, from: inbound.fromAddr, subject },
  });

  await sleep(between(250, 500));
  addEvent(s.id, "route.decision", "Stage 1: sender is not a carrier or service address → stage 2", {
    mailId: inbound.id,
    payload: { stage: 1, matched: null, hints: ["customer_domain", "has_weight", "has_destination"] },
  });

  await sleep(between(700, 1400));
  const confidence = spec.ambiguous ? 0.58 : Math.round(between(0.87, 0.98) * 100) / 100;
  const pickup = facts.pickup;
  const rfq: Rfq = {
    originZip: pickup?.zip ?? null,
    originAirport: spec.origin,
    destination: spec.dest,
    weightKg: spec.kg.toFixed(1),
    volumeCbm: spec.cbm.toFixed(2),
    chargeableKg: chargeable(spec.kg, spec.cbm).toFixed(1),
    pieces: spec.pieces,
    mode: spec.mode,
    doorPickup: spec.door,
    readyDate: ready.toISOString().slice(0, 10),
    commodity: COMMODITIES[spec.commodity].en,
    language: spec.customer.lang,
    customerName: spec.customer.name,
  };
  routerCall(s.id, inbound, spec.ambiguous ? "FOLLOW_UP" : "NEW_RFQ", confidence, { rfq });
  patchMail(inbound.id, { routeType: spec.ambiguous ? "FOLLOW_UP" : "NEW_RFQ", routeConfidence: confidence });
  addEvent(s.id, "route.decision", `${spec.ambiguous ? "FOLLOW_UP" : "NEW_RFQ"} · confidence ${confidence.toFixed(2)}`, {
    actor: "llm",
    mailId: inbound.id,
    payload: { stage: 2, type: spec.ambiguous ? "FOLLOW_UP" : "NEW_RFQ", confidence, threshold: 0.75, rfq },
  });
  db().rfqs.set(s.id, rfq);
  patchSession(s.id, { lane, mode: spec.mode, status: "PRICING" });

  if (spec.ambiguous) {
    patchSession(s.id, {
      status: "NEEDS_HUMAN",
      needsHuman: true,
      needsHumanReason: `Router confidence ${confidence.toFixed(2)} is below 0.75: reads like a follow-up to a phone call, but carries new cargo details and asks for a pickup.`,
    });
    addEvent(s.id, "route.needs_human", "Parked for a human: low router confidence", { payload: { confidence, threshold: 0.75 } });
    const decision = await waitForSignal<{ type?: string }>(s.id, "human");
    if (decision.type && decision.type !== "NEW_RFQ") {
      patchSession(s.id, { status: "CLOSED", needsHuman: false, needsHumanReason: null });
      return;
    }
    patchSession(s.id, { status: "PRICING", needsHuman: false, needsHumanReason: null });
    await sleep(800);
  }

  // Sheets: every kit airline on the lane, priced in memory.
  const onLane = airlinesFor(spec.dest);
  const sheet = shuffle(onLane.filter((a) => SHEET_AIRLINES.has(a.name))).slice(0, 3);
  const portal = shuffle(onLane.filter((a) => !SHEET_AIRLINES.has(a.name))).slice(0, 3);
  await sleep(between(300, 700));
  for (const a of sheet) {
    const q = airQuote(a.name, spec, "sheet");
    addCandidate(s.id, q);
    addEvent(s.id, "pricing.candidate", `Sheet · ${a.name} · €${money2(q.totalEur)} · ${q.transit}d`, { payload: q });
  }

  // Portal: one /rates call per airline, rate-limited.
  await sleep(between(500, 1100));
  if (spec.portal503) {
    addEvent(s.id, "portal.error", `GET /v1/portal/rates ${spec.origin}-${spec.dest} → 503 · use ratedesk@`, {
      payload: { status: 503, body: { detail: "Rate engine maintenance. Ask ratedesk@hackloop.mail." } },
    });
  } else {
    for (const [i, a] of portal.entries()) {
      if (i === 1 && rand() < 0.35) {
        addEvent(s.id, "portal.error", `GET /v1/portal/rates ${a.name} → 429 · retry in 2s`, {
          payload: { status: 429, headers: { "Retry-After": "2", "X-RateLimit-Remaining": "0", "X-RateLimit-Limit": "30" } },
        });
        await sleep(2000);
      }
      const q = airQuote(a.name, spec, "portal");
      addCandidate(s.id, q);
      addEvent(s.id, "portal.rate", `${a.name} · ${q.currency} ${money2(q.total)} · ${q.transit}d · ${q.rateId}`, {
        payload: {
          request: { method: "GET", path: "/v1/portal/rates", query: { origin: spec.origin, destination: spec.dest, airline: a.name, weight_kg: spec.kg, volume_cbm: spec.cbm } },
          status: 200,
          response: { rate_id: q.rateId, total: q.total, currency: q.currency, transit_days: q.transit, chargeable_weight_kg: chargeable(spec.kg, spec.cbm), dataset_version: "2026-11-21.3" },
          headers: { "X-RateLimit-Limit": "30", "X-RateLimit-Remaining": String(intBetween(8, 27)) },
        },
      });
      await sleep(between(150, 400));
    }
  }

  // Bids by mail to 2–3 airlines on the lane, plus a trucker for door pickup.
  const bidders = shuffle(onLane.filter((a) => !sheet.includes(a) && !portal.includes(a))).slice(0, spec.portal503 ? 2 : 3);
  const truckers = spec.door ? CARRIERS.filter((c) => c.kind === "trucker" && c.serves.includes((pickup?.zip ?? "").slice(0, 2))) : [];
  const trucker = truckers[0] ?? (spec.door ? CARRIERS.find((c) => c.kind === "trucker") : undefined);
  patchSession(s.id, { status: "WAITING_FOR_BIDS" });
  const requested: { bid: BidRequest; out: MailDetail }[] = [];
  for (const a of [...bidders, ...(trucker ? [trucker] : [])]) {
    await sleep(between(200, 450));
    const isTruck = a.kind === "trucker";
    const out = addMail({
      sessionId: s.id,
      direction: "out",
      from: MAILBOX,
      to: a.email,
      subject: isTruck ? `Abholung ${pickup?.zip} ${pickup?.city} → ${spec.origin}` : `Rate request ${spec.origin}-${spec.dest} ${spec.kg} kg`,
      body: isTruck ? T.truckRequestBody(facts) : T.bidRequestBody(a.name, facts),
    });
    const bid: BidRequest = {
      id: next("bid"),
      carrierName: a.name,
      carrierEmail: a.email,
      kind: isTruck ? "trucker" : "airline",
      sentAt: nowIso(),
      answeredAt: null,
      price: null,
      currency: null,
      transitDays: null,
      declined: false,
    };
    db().bids.set(s.id, [...(db().bids.get(s.id) ?? []), bid]);
    requested.push({ bid, out });
    addEvent(s.id, "mail.out", `Bid request to ${a.name}`, { mailId: out.id });
    addEvent(s.id, "bid.requested", `${a.name} (${bid.kind})`, { payload: { carrier: a.name, email: a.email, mailId: out.id } });
  }

  // Replies trickle in; some carriers never answer.
  const bidWindow = Math.max(12, Math.min(spec.quoteAfterS - 14, 80)) * SEC;
  const replies = requested
    .map(({ bid, out }) => ({ bid, out, at: between(0.2, spec.outcome === "expired" ? 2 : 1.15) * bidWindow, decline: bid.kind === "airline" && rand() < 0.18 }))
    .sort((a, b) => a.at - b.at);
  let elapsed = 0;
  for (const r of replies) {
    if (r.at > bidWindow) break;
    await sleep(r.at - elapsed);
    elapsed = r.at;
    const isTruck = r.bid.kind === "trucker";
    const q = isTruck ? null : airQuote(r.bid.carrierName, spec, "bid", 0.94);
    const truckPrice = money2(between(140, 280));
    const reply = addMail({
      sessionId: s.id,
      direction: "in",
      from: r.bid.carrierEmail,
      to: MAILBOX,
      subject: `Re: ${r.out.subject}`,
      body: r.decline ? T.bidDeclineBody(r.bid.carrierName) : isTruck ? T.truckReplyBody(r.bid.carrierName, facts, truckPrice) : T.bidReplyBody(r.bid.carrierName, facts, money2(q!.totalEur), q!.transit),
      inReplyTo: r.out,
      routeType: "CARRIER_BID",
      routeConfidence: 1,
    });
    addEvent(s.id, "mail.in", `${r.bid.carrierName} replied`, { mailId: reply.id });
    addEvent(s.id, "route.decision", "Stage 1: CARRIER_BID (reply to our bid request)", { mailId: reply.id, payload: { stage: 1, type: "CARRIER_BID", confidence: 1 } });
    const answered: BidRequest = {
      ...r.bid,
      answeredAt: nowIso(),
      declined: r.decline,
      price: r.decline ? null : isTruck ? truckPrice : money2(q!.totalEur),
      currency: r.decline ? null : "EUR",
      transitDays: r.decline || isTruck ? null : q!.transit,
    };
    db().bids.set(s.id, (db().bids.get(s.id) ?? []).map((b) => (b.id === answered.id ? answered : b)));
    if (r.decline) {
      addEvent(s.id, "bid.received", `${r.bid.carrierName} declined`, { payload: { carrier: r.bid.carrierName, declined: true } });
    } else if (isTruck) {
      addCandidate(s.id, { source: "truck", airline: null, total: Number(truckPrice), totalEur: Number(truckPrice), currency: "EUR", transit: 0, rateId: null, breakdown: { trucker: r.bid.carrierName, pickupZip: pickup?.zip } });
      addEvent(s.id, "bid.received", `${r.bid.carrierName} · pickup €${truckPrice}`, { payload: { carrier: r.bid.carrierName, price: truckPrice } });
    } else {
      addCandidate(s.id, q!);
      addEvent(s.id, "bid.received", `${r.bid.carrierName} · €${money2(q!.totalEur)} · ${q!.transit}d`, { payload: { carrier: r.bid.carrierName, parsed: { price: money2(q!.totalEur), currency: "EUR", transit_days: q!.transit } } });
    }
  }
  await sleep(Math.max(0, bidWindow - elapsed));
  for (const b of db().bids.get(s.id) ?? []) {
    if (!b.answeredAt) addEvent(s.id, "bid.timeout", `${b.carrierName} did not answer in ${Math.round(bidWindow / SEC)}s`, { payload: { carrier: b.carrierName } });
  }

  if (spec.outcome === "expired" || (db().candidates.get(s.id) ?? []).filter((c) => c.source !== "truck").length === 0) {
    const left = Math.max(0, created + 6 * MIN - db().clock.now());
    await sleep(left);
    patchSession(s.id, { status: "EXPIRED" });
    addEvent(s.id, "system.alert", "RFQ expired without a usable price (portal 503, no bids)", { payload: { reason: "no_candidates" } });
    return;
  }

  // Decide.
  const chosen = choose(s.id, spec.mode);
  const truck = (db().candidates.get(s.id) ?? []).find((c) => c.source === "truck");
  let finalTotal = Number(chosen.totalEur);
  if (truck) {
    finalTotal = Math.round((finalTotal + Number(truck.totalEur)) * 100) / 100;
    db().candidates.set(
      s.id,
      (db().candidates.get(s.id) ?? []).map((c) => (c.id === chosen.id ? { ...c, chosen: false, rejectReason: "Air leg of the combined offer" } : c)),
    );
    const combined = addCandidate(s.id, {
      source: "combined",
      airline: chosen.airlineName,
      total: finalTotal,
      totalEur: finalTotal,
      currency: "EUR",
      transit: chosen.transitDays ?? 0,
      rateId: chosen.rateId,
      breakdown: { air: chosen.totalEur, truck: truck.totalEur, airline: chosen.airlineName },
    });
    db().candidates.set(s.id, (db().candidates.get(s.id) ?? []).map((c) => (c.id === combined.id ? { ...c, chosen: true } : c)));
  }
  const all = db().candidates.get(s.id) ?? [];
  addEvent(s.id, "pricing.decision", `${spec.mode}: ${chosen.airlineName} via ${chosen.source}${truck ? " + truck" : ""} · €${money2(finalTotal)} · ${chosen.transitDays}d`, {
    payload: { mode: spec.mode, chosenId: chosen.id, totalEur: money2(finalTotal), candidates: all.map((c) => ({ id: c.id, source: c.source, airline: c.airlineName, totalEur: c.totalEur, transitDays: c.transitDays, rejectReason: c.rejectReason })) },
  });
  if (rand() < 0.2 && chosen.source === "sheet") {
    addEvent(s.id, "pricing.mismatch", `Sheet vs portal for ${chosen.airlineName}: €${between(3, 18).toFixed(2)} apart`, { payload: { airline: chosen.airlineName } });
  }

  // Wait so the quote lands when this story wants it to.
  const sinceStart = db().clock.now() - created;
  await sleep(Math.max(0, spec.quoteAfterS * SEC - sinceStart - 2500));

  const quote = { total: money2(finalTotal), currency: "EUR", airline: chosen.airlineName ?? "", transit: chosen.transitDays ?? 0, truck: Boolean(truck) };
  let reply = T.quoteBody(facts, quote);
  writerCall(s.id, "quote-writer", { ...quote, customer: spec.customer.name, language: spec.customer.lang, register: spec.customer.register }, reply);

  const draftMode = !db().admin.autopilot;
  if (spec.manual || draftMode || !session(s.id).autopilot) {
    if (session(s.id).autopilot && !draftMode) {
      patchSession(s.id, { autopilot: false });
      addEvent(s.id, "human.take_over", "Autopilot off for this session", { actor: "human", payload: { by: "ops" } });
    }
    db().drafts.set(s.id, { sessionId: s.id, to: spec.customer.email, subject: `Re: ${subject}`, body: reply, inReplyToExt: inbound.externalId, createdAt: nowIso() });
    patchSession(s.id, {
      needsHuman: true,
      needsHumanReason: draftMode
        ? "Draft mode: automatic answering is off, so this quote waits for approval."
        : "Quote draft is waiting for approval (autopilot off).",
    });
    addEvent(s.id, "quote.draft", `Draft ready for review · €${quote.total}`, { payload: { total: quote.total } });
    reply = await waitForSignal<string>(s.id, "draft");
    db().drafts.delete(s.id);
    patchSession(s.id, { needsHuman: false, needsHumanReason: null });
  }

  const out = addMail({ sessionId: s.id, direction: "out", from: MAILBOX, to: spec.customer.email, subject: `Re: ${subject}`, body: reply, inReplyTo: inbound });
  addEvent(s.id, "mail.out", `Quote to ${spec.customer.name}`, { mailId: out.id });
  const tookS = Math.round((db().clock.now() - created) / SEC);
  patchSession(s.id, { status: "QUOTED", quotedAt: nowIso(), quotedTotal: quote.total, quotedCurrency: "EUR" });
  addEvent(s.id, "quote.sent", `€${quote.total} all-in · ${Math.floor(tookS / 60)}:${String(tookS % 60).padStart(2, "0")} after the request`, {
    payload: { total: quote.total, currency: "EUR", mailId: out.id, responseSeconds: tookS },
  });

  if (spec.outcome === "rejected") {
    await sleep(between(80, 160) * SEC);
    addBoard(s.id, "rejected", pick(["PRICE_ABOVE_TOLERANCE", "WRONG_OPTION", "PRICE_ABOVE_TOLERANCE"]), { price: 0, cost: 0, time: 0 });
    patchSession(s.id, { status: "CLOSED" });
    return;
  }

  const timePts = tookS <= 120 ? 15 : Math.max(0, 15 * (1 - (tookS - 120) / 240));
  const points = { price: Math.round(between(41, 45) * 10) / 10, cost: Math.round(between(33, 39.5) * 10) / 10, time: Math.round(timePts * 10) / 10 };

  if (spec.outcome === "quoted") {
    await sleep(between(90, 200) * SEC);
    addBoard(s.id, "accepted", null, points);
    return;
  }

  // Customer accepts → book by rate ID.
  await sleep(between(45, 110) * SEC);
  const accept = addMail({ sessionId: s.id, direction: "in", from: spec.customer.email, to: MAILBOX, subject: `Re: Re: ${subject}`, body: T.acceptanceBody(facts), inReplyTo: out });
  addEvent(s.id, "mail.in", `${spec.customer.name} accepted`, { mailId: accept.id });
  await sleep(between(600, 1200));
  routerCall(s.id, accept, "ACCEPTANCE", 0.97, {});
  patchMail(accept.id, { routeType: "ACCEPTANCE", routeConfidence: 0.97 });
  addEvent(s.id, "route.decision", "ACCEPTANCE · confidence 0.97", { actor: "llm", mailId: accept.id, payload: { stage: 2, type: "ACCEPTANCE", confidence: 0.97 } });
  patchSession(s.id, { status: "ACCEPTED" });

  await sleep(between(400, 900));
  let rateId = chosen.rateId ?? `R-${hex(10)}`;
  if (rand() < 0.3) {
    addEvent(s.id, "portal.error", `POST /v1/portal/bookings → 410 rate expired · fetching a fresh rate`, { payload: { status: 410, rate_id: rateId } });
    await sleep(900);
    rateId = `R-${hex(10)}`;
    addEvent(s.id, "portal.rate", `Fresh rate ${rateId} for ${chosen.airlineName}`, { payload: { rate_id: rateId } });
  }
  const bookingRef = `BK-${hex(8).toUpperCase()}`;
  addEvent(s.id, "portal.booking", `POST /v1/portal/bookings → 201 · ${bookingRef}`, {
    payload: { request: { rate_id: rateId, team_reference: s.publicRef }, status: 201, response: { booking_reference: bookingRef } },
  });
  const departure = new Date(Math.max(db().clock.now() + 6 * 3600_000, ready.getTime() + 10 * 3600_000));
  const eta = new Date(departure.getTime() + (chosen.transitDays ?? 3) * 86_400_000);
  const booking: Booking = {
    id: next("booking"),
    sessionId: s.id,
    publicRef: s.publicRef,
    rateId,
    teamReference: s.publicRef,
    bookingReference: bookingRef,
    airlineCode: chosen.airlineCode,
    airlineName: chosen.airlineName,
    origin: spec.origin,
    destination: spec.dest,
    price: quote.total,
    currency: "EUR",
    transitDays: chosen.transitDays,
    status: "booked",
    bookedAt: nowIso(),
    confirmedAt: null,
    departureAt: departure.toISOString(),
    etaAt: eta.toISOString(),
  };
  db().bookings.push(booking);
  patchSession(s.id, { status: "BOOKED", booked: true });
  addEvent(s.id, "booking.created", `${chosen.airlineName} · ${bookingRef} · departs ${departure.toISOString().slice(5, 10)}`, { payload: booking });

  await sleep(between(900, 1800));
  const bookedText = T.bookedBody(facts, { ref: bookingRef, airline: chosen.airlineName ?? "", departure, eta });
  writerCall(s.id, "booking-writer", { bookingRef, airline: chosen.airlineName, departure, eta }, bookedText);
  const conf = addMail({ sessionId: s.id, direction: "out", from: MAILBOX, to: spec.customer.email, subject: `Re: Re: ${subject}`, body: bookedText, inReplyTo: accept });
  addEvent(s.id, "mail.out", `Booking confirmation to ${spec.customer.name}`, { mailId: conf.id });

  await sleep(between(60, 140) * SEC);
  addBoard(s.id, "accepted", null, points);

  await sleep((spec.confirmAfterS ?? between(40, 120)) * SEC);
  const confirmMail = addMail({
    sessionId: s.id,
    direction: "in",
    from: PORTAL_ADDR,
    to: MAILBOX,
    subject: `Booking ${bookingRef} confirmed`,
    body: T.bookingConfirmedBody(bookingRef, s.publicRef, chosen.airlineName ?? ""),
    routeType: "BOOKING_CONFIRMATION",
    routeConfidence: 1,
  });
  addEvent(s.id, "mail.in", `Booking ${bookingRef} confirmed`, { mailId: confirmMail.id });
  addEvent(s.id, "route.decision", "Stage 1: BOOKING_CONFIRMATION (subject rule)", { mailId: confirmMail.id, payload: { stage: 1, type: "BOOKING_CONFIRMATION", confidence: 1 } });
  const confirmed = { ...booking, status: "confirmed" as const, confirmedAt: nowIso() };
  db().bookings = db().bookings.map((b) => (b.id === booking.id ? confirmed : b));
  patchSession(s.id, { status: "CONFIRMED" });
  addEvent(s.id, "booking.confirmed", `${bookingRef} confirmed by ${chosen.airlineName}`, { payload: confirmed });
}

// ───────────────────────────── tracking story ─────────────────────────────

export async function trackingStory(bookingId?: number): Promise<void> {
  const booking = bookingId ? db().bookings.find((b) => b.id === bookingId) : pick(db().bookings);
  if (!booking) return;
  const subject = `Status ${booking.bookingReference}`;
  const s = createSession({ type: "TRACKING", customerAddr: TRACKING_ADDR, subject, lane: `${booking.origin} → ${booking.destination}` });
  const q = addMail({ sessionId: s.id, direction: "in", from: TRACKING_ADDR, to: MAILBOX, subject, body: T.trackingQuestion(booking.publicRef, booking.bookingReference ?? "", booking.destination), routeType: "TRACKING_QUESTION", routeConfidence: 1 });
  addEvent(s.id, "mail.in", `Tracking question about ${booking.bookingReference}`, { mailId: q.id });
  await sleep(300);
  addEvent(s.id, "route.decision", "Stage 1: TRACKING_QUESTION (tracking@ sender)", { mailId: q.id, payload: { stage: 1, type: "TRACKING_QUESTION", confidence: 1 } });
  patchSession(s.id, { status: "PRICING" });
  await sleep(500);
  addEvent(s.id, "crm.lookup", `Found ${booking.bookingReference} → ${booking.publicRef} (${booking.status})`, { payload: booking });
  await sleep(between(1200, 2200));
  const text = T.trackingAnswer({
    ref: booking.bookingReference ?? "",
    airline: booking.airlineName ?? "",
    origin: booking.origin,
    dest: booking.destination,
    departure: new Date(booking.departureAt ?? nowIso()),
    eta: new Date(booking.etaAt ?? nowIso()),
    confirmed: booking.status === "confirmed",
  });
  writerCall(s.id, "tracking-writer", { booking: booking.bookingReference, status: booking.status, eta: booking.etaAt }, text);
  const a = addMail({ sessionId: s.id, direction: "out", from: MAILBOX, to: TRACKING_ADDR, subject: `Re: ${subject}`, body: text, inReplyTo: q });
  addEvent(s.id, "mail.out", "Tracking answer sent", { mailId: a.id });
  patchSession(s.id, { status: "ANSWERED" });
}

// ───────────────────────────── tariff story ─────────────────────────────

export async function tariffStory(tariffId: number): Promise<void> {
  const tariff = db().tariffs.find((t) => t.id === tariffId);
  if (!tariff) return;
  const carrier = CARRIERS.find((c) => c.name === tariff.airlineName);
  const filename = `${tariff.airlineName.toLowerCase().replaceAll(" ", "-")}-tariff-${tariff.version}.csv`;
  const csv = [
    "lane,transit,minimum,-45,+45,+100,+250,+500,+1000,+2000,fuel,security",
    ...tariff.lanes.map((l) => [`${l.origin}-${l.destination}`, l.transitDays, l.minimum, ...l.breaks.map((b) => b.rate), l.fuelPerKg, l.securityPerKg].join(",")),
  ].join("\n");
  const subject = `${tariff.airlineName}: cargo tariff ${tariff.version}`;
  const s = createSession({ type: "TARIFF", customerAddr: carrier?.email ?? null, subject });
  const m = addMail({
    sessionId: s.id,
    direction: "in",
    from: carrier?.email ?? "revenue@hackloop.mail",
    to: MAILBOX,
    subject,
    body: T.tariffMailBody(tariff.airlineName),
    routeType: "TARIFF",
    routeConfidence: 1,
    attachments: [{ filename, contentType: "text/csv", body: csv }],
  });
  addEvent(s.id, "mail.in", subject, { mailId: m.id });
  await sleep(400);
  addEvent(s.id, "route.decision", "Stage 1: TARIFF (carrier sender + tariff attachment)", { mailId: m.id, payload: { stage: 1, type: "TARIFF", confidence: 1 } });
  patchSession(s.id, { status: "PRICING" });
  await sleep(1500);
  addLlmCall(s.id, {
    purpose: "tariff-extract",
    model: "gpt-4.1-mini",
    promptTokens: intBetween(4800, 6200),
    completionTokens: intBetween(1900, 2600),
    latencyMs: intBetween(7000, 11000),
    request: { model: "gpt-4.1-mini", messages: [{ role: "system", content: "Extract every lane of this tariff into the tariff_lane JSON schema." }, { role: "user", content: `[attachment ${filename}]` }] },
    response: { choices: [{ message: { role: "assistant", content: `{"lanes": [… ${tariff.lanes.length} lanes …]}` } }] },
  });
  addEvent(s.id, "tariff.ingested", `${tariff.airlineName} ${tariff.version}: ${tariff.lanes.length} lanes parsed`, { payload: { tariffId: tariff.id, lanes: tariff.lanes.length } });
  await sleep(300);
  patchSession(s.id, { status: "NEEDS_HUMAN", needsHuman: true, needsHumanReason: tariff.notes ?? "Sanity check failed against the active version." });
  addEvent(s.id, "system.alert", `Tariff held for review: ${tariff.notes}`, { payload: { tariffId: tariff.id, checks: { laneCount: "ok", rateDelta: "failed", breakOrder: "ok" } } });
  const verdict = await waitForSignal<"activate" | "reject">(s.id, "tariff");
  patchSession(s.id, { status: "CLOSED", needsHuman: false, needsHumanReason: null });
  addEvent(s.id, verdict === "activate" ? "tariff.ingested" : "tariff.rejected", verdict === "activate" ? `${tariff.airlineName} ${tariff.version} is now active` : `${tariff.airlineName} ${tariff.version} rejected`, {
    actor: "human",
    payload: { tariffId: tariff.id },
  });
}

// ───────────────────────────── generators ─────────────────────────────

const DESTS = ["SIN", "JFK", "DXB", "ORD", "GRU", "HKG", "SYD", "PVG", "LAX", "MEX", "NRT", "DEL", "JNB", "ICN", "YYZ", "TLV", "BKK", "MIA"];

export function randomSpec(overrides: Partial<RfqSpec> = {}): RfqSpec {
  const kg = Math.round(pick([between(60, 240), between(240, 700), between(700, 1800)]));
  return {
    customer: pick(CUSTOMERS),
    origin: pick(["FRA", "MUC", "DUS", "STR", "CGN", "HAM"] as const),
    dest: pick(DESTS),
    door: rand() < 0.3,
    kg,
    cbm: Math.round((kg / between(150, 260)) * 100) / 100,
    pieces: intBetween(1, 9),
    commodity: intBetween(0, COMMODITIES.length - 1),
    mode: rand() < 0.65 ? "cheapest" : "fastest",
    outcome: pick(["booked", "booked", "booked", "quoted", "rejected"] as const),
    quoteAfterS: intBetween(55, 115),
    ...overrides,
  };
}
