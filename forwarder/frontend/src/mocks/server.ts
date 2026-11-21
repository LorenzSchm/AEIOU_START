/**
 * Mock backend for NEXT_PUBLIC_MOCK=1. Implements every endpoint of
 * API_CONTRACT.md over the in-memory store, including the SSE stream and the
 * human actions, so the UI can be built and demoed without Spring Boot.
 */
import type { NextRequest } from "next/server";
import type {
  AdminStatus,
  EventItem,
  LiveSwitches,
  LlmCall,
  MailDetail,
  MailSummary,
  SessionAction,
  SessionStatus,
  SessionSummary,
  Stats,
  TariffDetail,
  TariffSummary,
} from "@/api/generated/model";
import { ensureSeeded } from "./seed";
import { addEvent, db, hasSignal, nowIso, patchMail, patchSession, signal, type StoredLlmCall, type StreamListener } from "./store";

const json = (data: unknown, status = 200) => Response.json(data, { status });
const fail = (status: number, error: string, message: string) => json({ error, message }, status);

function paginate<T>(items: T[], params: URLSearchParams, defaultLimit = 50) {
  const limit = Math.min(200, Math.max(1, Number(params.get("limit") ?? defaultLimit)));
  const offset = Number(params.get("cursor") ?? 0) || 0;
  const page = items.slice(offset, offset + limit);
  const nextOffset = offset + limit;
  return { items: page, nextCursor: nextOffset < items.length ? String(nextOffset) : null };
}

const includes = (hay: (string | null | undefined)[], q: string) => hay.join(" ").toLowerCase().includes(q.toLowerCase());

function summaryOfTariff(t: TariffDetail): TariffSummary {
  const copy: Partial<TariffDetail> = { ...t };
  delete copy.lanes;
  return copy as TariffSummary;
}

function mailSummary(m: MailDetail): MailSummary {
  const copy: Partial<MailDetail> = { ...m };
  delete copy.body;
  delete copy.inReplyToExt;
  delete copy.referencesExt;
  delete copy.attachments;
  return copy as MailSummary;
}

function llmSummary(c: StoredLlmCall): LlmCall {
  const copy: Partial<StoredLlmCall> = { ...c };
  delete copy.request;
  delete copy.response;
  return copy as LlmCall;
}

function median(xs: number[]): number | null {
  if (xs.length === 0) return null;
  const s = [...xs].sort((a, b) => a - b);
  const m = Math.floor(s.length / 2);
  return s.length % 2 ? s[m] : (s[m - 1] + s[m]) / 2;
}
function quantile(xs: number[], q: number): number | null {
  if (xs.length === 0) return null;
  const s = [...xs].sort((a, b) => a - b);
  return s[Math.min(s.length - 1, Math.floor(q * s.length))];
}

function stats(hours: number): Stats {
  const d = db();
  const sessions = [...d.sessions.values()];
  const rfqs = sessions.filter((s) => s.type === "RFQ");
  const quoted = rfqs.filter((s) => s.quotedAt);
  const responses = quoted.map((s) => (Date.parse(s.quotedAt!) - Date.parse(s.createdAt)) / 1000);
  const llmTotal = d.llm.reduce((a, c) => a + Number(c.costUsd ?? 0), 0);
  const statusCounts = Object.fromEntries(
    (["NEW", "PRICING", "WAITING_FOR_BIDS", "QUOTED", "ACCEPTED", "BOOKED", "CONFIRMED", "ANSWERED", "CLOSED", "NEEDS_HUMAN", "FAILED", "EXPIRED"] as SessionStatus[]).map((st) => [
      st,
      sessions.filter((s) => s.status === st).length,
    ]),
  ) as Stats["statusCounts"];

  const now = Date.now();
  const hourly: Stats["hourly"] = [];
  for (let h = hours - 1; h >= 0; h--) {
    const start = new Date(now - h * 3600_000);
    start.setMinutes(0, 0, 0);
    const end = start.getTime() + 3600_000;
    const inHour = (iso: string | null) => iso != null && Date.parse(iso) >= start.getTime() && Date.parse(iso) < end;
    const hq = quoted.filter((s) => inHour(s.quotedAt));
    const hr = hq.map((s) => (Date.parse(s.quotedAt!) - Date.parse(s.createdAt)) / 1000);
    const board = d.board.filter((b) => inHour(b.fetchedAt));
    hourly.push({
      hour: start.toISOString(),
      rfqs: rfqs.filter((s) => inHour(s.createdAt)).length,
      quotes: hq.length,
      accepted: board.filter((b) => b.verdict === "accepted").length,
      rejected: board.filter((b) => b.verdict === "rejected").length,
      llmCostUsd: d.llm.filter((c) => inHour(c.createdAt)).reduce((a, c) => a + Number(c.costUsd ?? 0), 0).toFixed(6),
      medianResponseSeconds: median(hr),
    });
  }

  return {
    sessionsTotal: sessions.length,
    rfqsTotal: rfqs.length,
    quotesSent: quoted.length,
    booked: rfqs.filter((s) => s.booked).length,
    needsHuman: sessions.filter((s) => s.needsHuman).length,
    accepted: d.board.filter((b) => b.verdict === "accepted").length,
    rejected: d.board.filter((b) => b.verdict === "rejected").length,
    llmCostUsdTotal: llmTotal.toFixed(6),
    llmCostUsdPerQuote: (quoted.length ? llmTotal / quoted.length : 0).toFixed(6),
    medianResponseSeconds: median(responses),
    p90ResponseSeconds: quantile(responses, 0.9),
    hourly,
    statusCounts,
  };
}

function stream(request: NextRequest): Response {
  const encoder = new TextEncoder();
  let listener: StreamListener | null = null;
  let ping: ReturnType<typeof setInterval> | null = null;
  const cleanup = () => {
    if (listener) db().listeners.delete(listener);
    if (ping) clearInterval(ping);
    listener = null;
    ping = null;
  };
  const body = new ReadableStream<Uint8Array>({
    start(controller) {
      const send = (event: string, data: unknown) => {
        try {
          controller.enqueue(encoder.encode(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`));
        } catch {
          cleanup();
        }
      };
      controller.enqueue(encoder.encode(`retry: 2000\n: connected to mock stream\n\n`));
      listener = (type, data) => send(type, data);
      db().listeners.add(listener);
      ping = setInterval(() => send("ping", {}), 15_000);
      request.signal.addEventListener("abort", () => {
        cleanup();
        try {
          controller.close();
        } catch {
          // already closed
        }
      });
    },
    cancel: cleanup,
  });
  return new Response(body, {
    headers: {
      "Content-Type": "text/event-stream; charset=utf-8",
      "Cache-Control": "no-cache, no-transform",
      Connection: "keep-alive",
      "X-Accel-Buffering": "no",
    },
  });
}

function act(id: number, action: SessionAction): Response {
  const d = db();
  const s = d.sessions.get(id);
  if (!s) return fail(404, "not_found", `Session ${id} not found`);
  const human = { actor: "human" as const };
  switch (action.action) {
    case "take_over":
      patchSession(id, { autopilot: false });
      addEvent(id, "human.take_over", "Autopilot off: a human has this session", { ...human, payload: { by: "ops" } });
      break;
    case "release": {
      patchSession(id, { autopilot: true });
      addEvent(id, "human.release", "Autopilot back on", { ...human, payload: { by: "ops" } });
      const draft = d.drafts.get(id);
      if (draft && db().admin.autopilot) signal(id, "draft", draft.body);
      break;
    }
    case "approve": {
      const draft = d.drafts.get(id);
      if (!draft) return fail(409, "no_draft", "There is no draft to approve.");
      addEvent(id, "human.approve", "Draft approved and sent as written", { ...human, payload: { by: "ops" } });
      signal(id, "draft", draft.body);
      break;
    }
    case "edit_and_send": {
      if (!action.body?.trim()) return fail(400, "empty_body", "The reply body is empty.");
      addEvent(id, "human.edit_send", "Edited reply sent", { ...human, payload: { by: "ops", length: action.body.length } });
      if (!signal(id, "draft", action.body)) {
        return fail(409, "no_draft", "Nothing is waiting to be sent for this session.");
      }
      break;
    }
    case "reroute": {
      const lastIn = [...d.mails].reverse().find((m) => m.sessionId === id && m.direction === "in");
      if (lastIn) patchMail(lastIn.id, { routeType: action.type, routeConfidence: 1 });
      addEvent(id, "human.reroute", `Re-routed latest inbound mail to ${action.type}`, { ...human, mailId: lastIn?.id ?? null, payload: { type: action.type, mailId: lastIn?.id } });
      if (hasSignal(id, "human")) signal(id, "human", { type: action.type });
      break;
    }
    case "resolve":
      patchSession(id, { needsHuman: false, needsHumanReason: null });
      addEvent(id, "human.resolve", action.note ? `Resolved: ${action.note}` : "Marked as resolved", { ...human, payload: { note: action.note ?? null } });
      if (hasSignal(id, "human")) signal(id, "human", {});
      break;
  }
  return json(d.sessions.get(id));
}

function tariffAction(id: number, verdict: "activate" | "reject"): Response {
  const d = db();
  const t = d.tariffs.find((x) => x.id === id);
  if (!t) return fail(404, "not_found", `Tariff ${id} not found`);
  if (t.status !== "pending_review") return fail(409, "not_pending", "Only tariffs pending review can be changed.");
  d.tariffs = d.tariffs.map((x) => {
    if (x.id === id) return { ...x, status: verdict === "activate" ? "active" : "rejected" };
    if (verdict === "activate" && x.airlineName === t.airlineName && x.status === "active") return { ...x, status: "superseded" };
    return x;
  });
  const tariffSession = [...d.sessions.values()].find((s) => s.type === "TARIFF" && s.subject?.includes(t.version) && s.subject.includes(t.airlineName));
  addEvent(tariffSession?.id ?? null, verdict === "activate" ? "human.tariff_activated" : "human.tariff_rejected", `${t.airlineName} ${t.version} ${verdict === "activate" ? "activated" : "rejected"} by ops`, {
    actor: "human",
    payload: { tariffId: id },
  });
  if (tariffSession) signal(tariffSession.id, "tariff", verdict);
  return json(summaryOfTariff(d.tariffs.find((x) => x.id === id)!));
}

function adminStatus(): AdminStatus {
  return { ...db().admin };
}

function setLive(body: LiveSwitches): Response {
  const d = db();
  const changes: string[] = [];
  if (body.pollingEnabled !== undefined && body.pollingEnabled !== d.admin.pollingEnabled) {
    d.admin.pollingEnabled = body.pollingEnabled;
    changes.push(`mailbox polling ${body.pollingEnabled ? "on" : "off"}`);
  }
  if (body.autopilot !== undefined && body.autopilot !== d.admin.autopilot) {
    d.admin.autopilot = body.autopilot;
    d.settings = d.settings.map((s) => (s.key === "autopilot" ? { ...s, value: body.autopilot, updatedAt: nowIso(), updatedBy: "ops" } : s));
    changes.push(body.autopilot ? "automatic answering on" : "draft mode on");
  }
  if (changes.length) addEvent(null, "human.setting_changed", `Live switches: ${changes.join(", ")}`, { actor: "human", payload: body });
  return json(adminStatus());
}

async function readJson<T>(request: NextRequest): Promise<T | null> {
  try {
    return (await request.json()) as T;
  } catch {
    return null;
  }
}

export async function handleMockRequest(request: NextRequest, path: string[]): Promise<Response> {
  await ensureSeeded();
  const d = db();
  const q = request.nextUrl.searchParams;
  const method = request.method;
  const [a, b, c] = path;
  const id = Number(b);

  // Simulate a fast but real network.
  if (a !== "stream") await new Promise((r) => setTimeout(r, 40 + Math.random() * 120));

  if (method === "GET") {
    switch (a) {
      case "stream":
        return stream(request);
      case "sessions": {
        if (!b) {
          let list = [...d.sessions.values()];
          const status = q.get("status");
          const type = q.get("type");
          const nh = q.get("needsHuman");
          const search = q.get("q");
          if (status) list = list.filter((s) => s.status === status);
          if (type) list = list.filter((s) => s.type === type);
          if (nh != null) list = list.filter((s) => s.needsHuman === (nh === "true"));
          if (search) list = list.filter((s) => includes([s.publicRef, s.customerAddr, s.subject, s.lane, s.lastEventSummary], search));
          list.sort((x, y) => y.lastEventAt.localeCompare(x.lastEventAt) || y.id - x.id);
          return json(paginate<SessionSummary>(list, q));
        }
        const s = d.sessions.get(id);
        if (!s) return fail(404, "not_found", `Session ${id} not found`);
        if (!c) {
          return json({
            session: s,
            rfq: d.rfqs.get(id) ?? null,
            candidates: d.candidates.get(id) ?? [],
            bids: d.bids.get(id) ?? [],
            booking: d.bookings.find((x) => x.sessionId === id) ?? null,
          });
        }
        if (c === "events") return json(paginate<EventItem>(d.events.filter((e) => e.sessionId === id), q, 200));
        if (c === "mails") return json(d.mails.filter((m) => m.sessionId === id));
        if (c === "draft") {
          const draft = d.drafts.get(id);
          return draft ? json(draft) : fail(404, "no_draft", "No draft for this session");
        }
        break;
      }
      case "events": {
        if (b && c === "payload") {
          if (!d.payloads.has(id)) return fail(404, "not_found", "No payload");
          return json({ id, payload: d.payloads.get(id) });
        }
        const kind = q.get("kind");
        const list = d.events.filter((e) => !kind || e.kind.startsWith(kind)).reverse();
        return json(paginate(list, q));
      }
      case "mails": {
        if (b) {
          const m = d.mails.find((x) => x.id === id);
          return m ? json(m) : fail(404, "not_found", "Mail not found");
        }
        const dir = q.get("direction");
        const search = q.get("q");
        const list = d.mails
          .filter((m) => (!dir || m.direction === dir) && (!search || includes([m.subject, m.body, m.fromAddr, m.toAddr, m.sessionRef], search)))
          .reverse()
          .map(mailSummary);
        return json(paginate(list, q));
      }
      case "attachments": {
        const att = d.attachments.get(id);
        if (!att) return fail(404, "not_found", "Attachment not found");
        return new Response(att.body, {
          headers: { "Content-Type": att.type, "Content-Disposition": `attachment; filename="${att.filename}"` },
        });
      }
      case "llm-calls": {
        if (b) {
          const call = d.llm.find((x) => x.id === id);
          return call ? json(call) : fail(404, "not_found", "LLM call not found");
        }
        const sid = q.get("sessionId");
        const list = d.llm
          .filter((x) => !sid || x.sessionId === Number(sid))
          .reverse()
          .map(llmSummary);
        return json(paginate(list, q));
      }
      case "bookings": {
        const status = q.get("status");
        return json(paginate([...d.bookings].filter((x) => !status || x.status === status).reverse(), q));
      }
      case "shipments": {
        const { AIRPORTS } = await import("./reference");
        return json(
          d.bookings
            .filter((x) => x.status === "booked" || x.status === "confirmed")
            .map((x) => ({
              bookingId: x.id,
              sessionId: x.sessionId,
              publicRef: x.publicRef,
              bookingReference: x.bookingReference,
              airlineName: x.airlineName,
              status: x.status,
              origin: AIRPORTS[x.origin] ? { iata: x.origin, city: AIRPORTS[x.origin].city, lat: AIRPORTS[x.origin].lat, lon: AIRPORTS[x.origin].lon } : { iata: x.origin, city: null, lat: 50, lon: 8.5 },
              destination: AIRPORTS[x.destination]
                ? { iata: x.destination, city: AIRPORTS[x.destination].city, lat: AIRPORTS[x.destination].lat, lon: AIRPORTS[x.destination].lon }
                : { iata: x.destination, city: null, lat: 0, lon: 0 },
              departureAt: x.departureAt,
              etaAt: x.etaAt,
              transitDays: x.transitDays,
              price: x.price,
              currency: x.currency,
            })),
        );
      }
      case "stats":
        return json(stats(Math.min(168, Math.max(1, Number(q.get("hours") ?? 24)))));
      case "board":
        return json([...d.board].reverse());
      case "tariffs": {
        if (b) {
          const t = d.tariffs.find((x) => x.id === id);
          return t ? json(t) : fail(404, "not_found", "Tariff not found");
        }
        return json(d.tariffs.map(summaryOfTariff));
      }
      case "carriers":
        return json(d.carriers);
      case "settings":
        return json(d.settings);
      case "admin":
        if (b === "status") return json(adminStatus());
        break;
    }
  }

  if (method === "POST") {
    if (a === "sessions" && c === "actions") {
      const body = await readJson<SessionAction>(request);
      if (!body?.action) return fail(400, "bad_request", "Missing action");
      return act(id, body);
    }
    if (a === "tariffs" && (c === "activate" || c === "reject")) return tariffAction(id, c);
    if (a === "admin" && b === "openai-key") {
      const body = await readJson<{ apiKey?: string }>(request);
      const key = body?.apiKey?.trim() ?? "";
      if (!key.startsWith("sk-") || key.length < 20) {
        return json({ ok: false, message: "The gateway rejected this key: it does not look like an OpenAI key (sk-…)." });
      }
      d.admin.openAiKeyRegistered = true;
      d.admin.openAiKeyMessage = `Key ending in …${key.slice(-4)} is stored at the gateway.`;
      addEvent(null, "human.setting_changed", "OpenAI key registered with the LLM gateway", { actor: "human", payload: { keySuffix: key.slice(-4) } });
      return json({ ok: true, message: "Key registered. The gateway will use it for every LLM call from now on." });
    }
  }

  if (method === "PUT") {
    if (a === "settings" && b) {
      const body = await readJson<{ value: unknown }>(request);
      if (!body || !("value" in body)) return fail(400, "bad_request", "Missing value");
      const key = decodeURIComponent(b);
      const updated = { key, value: body.value, updatedAt: nowIso(), updatedBy: "ops" };
      d.settings = d.settings.some((s) => s.key === key) ? d.settings.map((s) => (s.key === key ? updated : s)) : [...d.settings, updated];
      if (key === "autopilot" && typeof body.value === "boolean") d.admin.autopilot = body.value;
      addEvent(null, "human.setting_changed", `${key} = ${JSON.stringify(body.value)}`, { actor: "human", payload: updated });
      return json(updated);
    }
    if (a === "admin" && b === "live") {
      const body = await readJson<LiveSwitches>(request);
      return setLive(body ?? {});
    }
  }

  return fail(404, "not_found", `No mock for ${method} /api/${path.join("/")}`);
}
