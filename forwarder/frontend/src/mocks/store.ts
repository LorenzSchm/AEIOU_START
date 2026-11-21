/**
 * In-memory database and discrete-event clock for mock mode.
 *
 * Stories (see stories.ts) are plain async functions that `sleep()` between
 * steps. While seeding, the scheduler runs them on a virtual clock that starts
 * three hours ago, so history is generated instantly and in time order. When
 * the virtual clock catches up with the wall clock it hands over to real
 * timers, and the same stories keep producing live events.
 */
import type {
  AdminStatus,
  BidRequest,
  Booking,
  BoardResult,
  Carrier,
  Draft,
  EventItem,
  Actor,
  LlmCall,
  MailDetail,
  PriceCandidate,
  Rfq,
  SessionSummary,
  Setting,
  TariffDetail,
} from "@/api/generated/model";

export type StreamListener = (type: "event" | "session", data: EventItem | SessionSummary) => void;

export interface StoredLlmCall extends LlmCall {
  request: unknown;
  response: unknown;
}

class Scheduler {
  private queue: { at: number; seq: number; resolve: () => void }[] = [];
  private seq = 0;
  private virtualNow: number | null = null;

  startVirtual(at: number) {
    this.virtualNow = at;
  }

  get isVirtual() {
    return this.virtualNow !== null;
  }

  now(): number {
    return this.virtualNow ?? Date.now();
  }

  sleep(ms: number): Promise<void> {
    const at = this.now() + Math.max(0, ms);
    if (this.virtualNow === null) {
      return new Promise((r) => setTimeout(r, Math.max(0, at - Date.now())));
    }
    return new Promise((resolve) => this.queue.push({ at, seq: this.seq++, resolve }));
  }

  /** Drain all virtual steps up to the wall clock, then switch to real timers. */
  async catchUp(): Promise<void> {
    const tick = () => new Promise<void>((r) => setImmediate(r));
    for (;;) {
      await tick();
      if (this.queue.length === 0) break;
      this.queue.sort((a, b) => a.at - b.at || a.seq - b.seq);
      const next = this.queue[0];
      if (next.at > Date.now()) break;
      this.queue.shift();
      this.virtualNow = next.at;
      next.resolve();
    }
    const rest = this.queue.splice(0);
    this.virtualNow = null;
    for (const item of rest) setTimeout(item.resolve, Math.max(0, item.at - Date.now()));
  }
}

export interface MockDb {
  clock: Scheduler;
  seq: Record<"session" | "event" | "mail" | "ext" | "att" | "cand" | "bid" | "booking" | "llm" | "tariff", number>;
  sessions: Map<number, SessionSummary>;
  rfqs: Map<number, Rfq>;
  candidates: Map<number, PriceCandidate[]>;
  bids: Map<number, BidRequest[]>;
  bookings: Booking[];
  events: EventItem[];
  payloads: Map<number, unknown>;
  mails: MailDetail[];
  attachments: Map<number, { body: string; type: string; filename: string }>;
  llm: StoredLlmCall[];
  drafts: Map<number, Draft>;
  tariffs: TariffDetail[];
  settings: Setting[];
  board: BoardResult[];
  carriers: Carrier[];
  listeners: Set<StreamListener>;
  signals: Map<string, (value: unknown) => void>;
  admin: AdminStatus;
  ready: Promise<void> | null;
}

const globalKey = "__forwarderMockDb";
type GlobalWithDb = typeof globalThis & { [globalKey]?: MockDb };

export function db(): MockDb {
  const g = globalThis as GlobalWithDb;
  if (!g[globalKey]) {
    g[globalKey] = {
      clock: new Scheduler(),
      seq: { session: 1040, event: 0, mail: 0, ext: 88_000, att: 0, cand: 0, bid: 0, booking: 0, llm: 0, tariff: 0 },
      sessions: new Map(),
      rfqs: new Map(),
      candidates: new Map(),
      bids: new Map(),
      bookings: [],
      events: [],
      payloads: new Map(),
      mails: [],
      attachments: new Map(),
      llm: [],
      drafts: new Map(),
      tariffs: [],
      settings: [],
      board: [],
      carriers: [],
      listeners: new Set(),
      signals: new Map(),
      admin: {
        openAiKeyRegistered: false,
        openAiKeyMessage: "No key stored at the gateway yet.",
        openAiKeyLast4: null,
        openAiKeyCheckedAt: null,
        pollingEnabled: true,
        autopilot: true,
        envPollerEnabled: true,
        envSendEnabled: true,
      },
      ready: null,
    };
  }
  return g[globalKey];
}

export const nowIso = () => new Date(db().clock.now()).toISOString();
export const sleep = (ms: number) => db().clock.sleep(ms);
export const next = (key: keyof MockDb["seq"]) => ++db().seq[key];

function emit(type: "event" | "session", data: EventItem | SessionSummary) {
  for (const l of db().listeners) l(type, data);
}

// ───────────────────────────── sessions ─────────────────────────────

export function createSession(init: Partial<SessionSummary> & Pick<SessionSummary, "type">): SessionSummary {
  const id = next("session");
  const at = nowIso();
  const s: SessionSummary = {
    id,
    publicRef: `AE-${id}`,
    status: "NEW",
    customerAddr: null,
    subject: null,
    mode: null,
    needsHuman: false,
    needsHumanReason: null,
    autopilot: true,
    deadlineAt: null,
    quotedAt: null,
    quotedTotal: null,
    quotedCurrency: null,
    booked: false,
    llmCostUsd: "0.00",
    eventCount: 0,
    mailCount: 0,
    lastEventAt: at,
    lastEventSummary: null,
    createdAt: at,
    lane: null,
    boardVerdict: null,
    ...init,
  };
  db().sessions.set(id, s);
  emit("session", s);
  return s;
}

export function session(id: number): SessionSummary {
  const s = db().sessions.get(id);
  if (!s) throw new Error(`mock session ${id} missing`);
  return s;
}

export function patchSession(id: number, patch: Partial<SessionSummary>): SessionSummary {
  const s = { ...session(id), ...patch };
  db().sessions.set(id, s);
  emit("session", s);
  return s;
}

// ───────────────────────────── events ─────────────────────────────

export function addEvent(
  sessionId: number | null,
  kind: string,
  summary: string,
  opts: { actor?: Actor; payload?: unknown; mailId?: number | null } = {},
): EventItem {
  const id = next("event");
  const e: EventItem = {
    id,
    sessionId,
    mailId: opts.mailId ?? null,
    kind,
    actor: opts.actor ?? (kind === "llm.call" ? "llm" : kind.startsWith("human.") ? "human" : "system"),
    summary,
    createdAt: nowIso(),
    hasPayload: opts.payload !== undefined,
  };
  db().events.push(e);
  if (opts.payload !== undefined) db().payloads.set(id, opts.payload);
  emit("event", e);
  if (sessionId != null && db().sessions.has(sessionId)) {
    const s = session(sessionId);
    patchSession(sessionId, { eventCount: s.eventCount + 1, lastEventAt: e.createdAt, lastEventSummary: summary });
  }
  return e;
}

// ───────────────────────────── mails ─────────────────────────────

export function addMail(m: {
  sessionId: number | null;
  direction: "in" | "out";
  from: string;
  to: string;
  subject: string;
  body: string;
  inReplyTo?: MailDetail | null;
  routeType?: string | null;
  routeConfidence?: number | null;
  attachments?: { filename: string; contentType: string; body: string }[];
}): MailDetail {
  const id = next("mail");
  const externalId = next("ext");
  const s = m.sessionId != null ? db().sessions.get(m.sessionId) : undefined;
  const attachments = (m.attachments ?? []).map((a) => {
    const attId = next("att");
    db().attachments.set(attId, { body: a.body, type: a.contentType, filename: a.filename });
    return { id: attId, filename: a.filename, contentType: a.contentType, size: new TextEncoder().encode(a.body).length };
  });
  const mail: MailDetail = {
    id,
    externalId,
    direction: m.direction,
    fromAddr: m.from,
    toAddr: m.to,
    subject: m.subject,
    snippet: m.body.replace(/\s+/g, " ").trim().slice(0, 160),
    sessionId: m.sessionId,
    sessionRef: s?.publicRef ?? null,
    routeType: m.routeType ?? null,
    routeConfidence: m.routeConfidence ?? null,
    sentAt: nowIso(),
    attachmentCount: attachments.length,
    body: m.body,
    inReplyToExt: m.inReplyTo?.externalId ?? null,
    referencesExt: m.inReplyTo ? [...m.inReplyTo.referencesExt, m.inReplyTo.externalId] : [],
    attachments,
  };
  db().mails.push(mail);
  if (s) patchSession(s.id, { mailCount: s.mailCount + 1 });
  return mail;
}

export function patchMail(id: number, patch: Partial<MailDetail>) {
  const list = db().mails;
  const i = list.findIndex((m) => m.id === id);
  if (i >= 0) list[i] = { ...list[i], ...patch };
}

// ───────────────────────────── llm ─────────────────────────────

const PRICE_PER_1K: Record<string, [number, number]> = {
  "gpt-4o-mini": [0.00015, 0.0006],
  "gpt-4.1-nano": [0.0001, 0.0004],
  "gpt-4.1-mini": [0.0004, 0.0016],
};

export function addLlmCall(
  sessionId: number | null,
  c: { purpose: string; model: string; promptTokens: number; completionTokens: number; latencyMs: number; request: unknown; response: unknown; success?: boolean },
): StoredLlmCall {
  const [pin, pout] = PRICE_PER_1K[c.model] ?? [0.00015, 0.0006];
  const cost = (c.promptTokens / 1000) * pin + (c.completionTokens / 1000) * pout;
  const call: StoredLlmCall = {
    id: next("llm"),
    sessionId,
    purpose: c.purpose,
    model: c.model,
    promptTokens: c.promptTokens,
    completionTokens: c.completionTokens,
    costUsd: cost.toFixed(6),
    latencyMs: c.latencyMs,
    success: c.success ?? true,
    createdAt: nowIso(),
    request: c.request,
    response: c.response,
  };
  db().llm.push(call);
  if (sessionId != null) {
    const s = session(sessionId);
    patchSession(sessionId, { llmCostUsd: (Number(s.llmCostUsd) + cost).toFixed(6) });
  }
  addEvent(
    sessionId,
    "llm.call",
    `${c.purpose} · ${c.model} · ${c.promptTokens + c.completionTokens} tok · $${cost.toFixed(5)} · ${c.latencyMs} ms`,
    {
      payload: {
        llmCallId: call.id,
        purpose: c.purpose,
        model: c.model,
        promptTokens: c.promptTokens,
        completionTokens: c.completionTokens,
        costUsd: call.costUsd,
        latencyMs: c.latencyMs,
      },
    },
  );
  return call;
}

// ───────────────────────────── signals ─────────────────────────────

/** Pause a story until an operator acts (draft mode or a taken-over session). */
export function waitForSignal<T>(sessionId: number, name: string): Promise<T> {
  return new Promise<T>((resolve) => {
    db().signals.set(`${sessionId}:${name}`, (v) => resolve(v as T));
  });
}

export function signal(sessionId: number, name: string, value: unknown): boolean {
  const key = `${sessionId}:${name}`;
  const fn = db().signals.get(key);
  if (!fn) return false;
  db().signals.delete(key);
  fn(value);
  return true;
}

export function hasSignal(sessionId: number, name: string): boolean {
  return db().signals.has(`${sessionId}:${name}`);
}

// ───────────────────────────── random ─────────────────────────────

let seed = 20261121;
/** Deterministic PRNG so the seeded history looks the same on every restart. */
export function rand(): number {
  seed = (seed * 1664525 + 1013904223) % 4294967296;
  return seed / 4294967296;
}
export const between = (a: number, b: number) => a + rand() * (b - a);
export const intBetween = (a: number, b: number) => Math.floor(between(a, b + 1));
export const pick = <T,>(xs: readonly T[]): T => xs[Math.floor(rand() * xs.length)];
export function shuffle<T>(xs: readonly T[]): T[] {
  const a = [...xs];
  for (let i = a.length - 1; i > 0; i--) {
    const j = Math.floor(rand() * (i + 1));
    [a[i], a[j]] = [a[j], a[i]];
  }
  return a;
}
export const money2 = (n: number) => n.toFixed(2);
