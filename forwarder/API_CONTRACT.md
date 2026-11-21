# Control panel API contract (backend → frontend)

The backend serves these endpoints under `/api` (springdoc publishes `/v3/api-docs`). The frontend generates its client from that spec with orval (`npm run gen:api`). Until the backend is up, the frontend works from `frontend/openapi.json`, which is written by hand from this file.

Conventions:
- JSON in camelCase. Times are ISO-8601 with offset. Money is a decimal **string** with 2 places (`"1387.35"`).
- Lists use cursor pagination: `?limit=50&cursor=<opaque>` returns `{ items: T[], nextCursor: string | null }`.
- Errors: `{ error: string, message: string }` with a 4xx/5xx status.
- The operationIds below are used verbatim, so orval generates stable hook names.

## Types
```ts
type SessionStatus = "NEW"|"PRICING"|"WAITING_FOR_BIDS"|"QUOTED"|"ACCEPTED"|"BOOKED"|"CONFIRMED"|"ANSWERED"|"CLOSED"|"NEEDS_HUMAN"|"FAILED"|"EXPIRED";
type SessionType = "RFQ"|"TRACKING"|"TARIFF"|"OTHER";

interface SessionSummary {
  id: number; publicRef: string; type: SessionType; status: SessionStatus;
  customerAddr: string|null; subject: string|null; mode: "cheapest"|"fastest"|null;
  needsHuman: boolean; needsHumanReason: string|null; autopilot: boolean;
  deadlineAt: string|null; quotedAt: string|null; quotedTotal: string|null; quotedCurrency: string|null;
  booked: boolean; llmCostUsd: string; eventCount: number; mailCount: number;
  lastEventAt: string; lastEventSummary: string|null; createdAt: string;
  lane: string|null;            // e.g. "FRA → SIN", from rfq
  boardVerdict: string|null;    // accepted | rejected | pending | open | no answer | voided
}

interface Rfq {
  originZip: string|null; originAirport: string|null; destination: string|null;
  weightKg: string|null; volumeCbm: string|null; chargeableKg: string|null; pieces: number|null;
  mode: string|null; doorPickup: boolean; readyDate: string|null; commodity: string|null;
  language: string|null; customerName: string|null;
}

interface PriceCandidate {
  id: number; source: "sheet"|"portal"|"bid"|"ratedesk"|"truck"|"combined";
  airlineCode: string|null; airlineName: string|null;
  total: string; currency: string; totalEur: string; transitDays: number|null;
  rateId: string|null; validUntil: string|null; breakdown: Record<string, unknown>|null;
  chosen: boolean; rejectReason: string|null; createdAt: string;
}

interface BidRequest {
  id: number; carrierName: string; carrierEmail: string; kind: "airline"|"trucker"|"ratedesk";
  sentAt: string; answeredAt: string|null; price: string|null; currency: string|null;
  transitDays: number|null; declined: boolean;
}

interface Booking {
  id: number; sessionId: number; publicRef: string; rateId: string|null; teamReference: string;
  bookingReference: string|null; airlineCode: string|null; airlineName: string|null;
  origin: string; destination: string; price: string|null; currency: string|null; transitDays: number|null;
  status: "requested"|"booked"|"confirmed"|"failed";
  bookedAt: string; confirmedAt: string|null; departureAt: string|null; etaAt: string|null;
}

interface SessionDetail {
  session: SessionSummary; rfq: Rfq|null;
  candidates: PriceCandidate[]; bids: BidRequest[]; booking: Booking|null;
}

interface EventItem {
  id: number; sessionId: number|null; mailId: number|null;
  kind: string;                 // "mail.in", "route.decision", "llm.call", ... (ARCHITECTURE.md §6)
  actor: "system"|"llm"|"human"; summary: string; createdAt: string;
  hasPayload: boolean;
}
interface EventPayload { id: number; payload: unknown; }

interface MailSummary {
  id: number; externalId: number; direction: "in"|"out"; fromAddr: string; toAddr: string;
  subject: string; snippet: string;   // first ~160 chars of the body
  sessionId: number|null; sessionRef: string|null; routeType: string|null; routeConfidence: number|null;
  sentAt: string; attachmentCount: number;
}
interface Attachment { id: number; filename: string; contentType: string|null; size: number|null; }
interface MailDetail extends MailSummary {
  body: string; inReplyToExt: number|null; referencesExt: number[]; attachments: Attachment[];
}

interface LlmCall {
  id: number; sessionId: number|null; purpose: string; model: string;
  promptTokens: number|null; completionTokens: number|null; costUsd: string|null;
  latencyMs: number|null; success: boolean; createdAt: string;
  request?: unknown; response?: unknown;    // only on the detail endpoint
}

interface ShipmentPoint { iata: string; city: string|null; lat: number; lon: number; }
interface ActiveShipment {
  bookingId: number; sessionId: number; publicRef: string; bookingReference: string|null;
  airlineName: string|null; status: Booking["status"];
  origin: ShipmentPoint; destination: ShipmentPoint;
  departureAt: string|null; etaAt: string|null; transitDays: number|null;
  price: string|null; currency: string|null;
}

interface Stats {
  sessionsTotal: number; rfqsTotal: number; quotesSent: number; booked: number; needsHuman: number;
  accepted: number; rejected: number;       // from board results
  llmCostUsdTotal: string; llmCostUsdPerQuote: string;
  medianResponseSeconds: number|null; p90ResponseSeconds: number|null;
  hourly: { hour: string; rfqs: number; quotes: number; accepted: number; rejected: number; llmCostUsd: string; medianResponseSeconds: number|null; }[];
  statusCounts: Record<SessionStatus, number>;
}

interface BoardResult {
  requestKey: string; sessionId: number|null; publicRef: string|null; round: string|null;
  verdict: string|null; reasonCode: string|null; points: Record<string, number>|null; fetchedAt: string;
}

interface TariffSummary {
  id: number; airlineName: string; airlineCode: string|null; version: string; source: "kit"|"mail";
  validFrom: string|null; validTo: string|null; currency: string;
  status: "active"|"pending_review"|"superseded"|"rejected"; laneCount: number; notes: string|null; createdAt: string;
}
interface TariffLane {
  origin: string; destination: string; transitDays: number|null; minimum: string;
  breaks: { fromKg: number; rate: string }[]; fuelPerKg: string; securityPerKg: string;
}
interface TariffDetail extends TariffSummary { lanes: TariffLane[]; }

interface Setting { key: string; value: unknown; updatedAt: string; updatedBy: string|null; }
interface Carrier { id: number; name: string; code: string|null; kind: "airline"|"trucker"; email: string; serves: string[]; }

// Human actions
type SessionAction =
  | { action: "approve" }                               // send the pending draft as is
  | { action: "edit_and_send"; body: string }           // send this body as the quote/reply
  | { action: "reroute"; type: string }                 // MailType for the session's latest inbound mail
  | { action: "take_over" }                             // autopilot off for this session
  | { action: "release" }                               // autopilot back on
  | { action: "resolve"; note?: string };               // clear needsHuman

interface Draft { sessionId: number; to: string; subject: string; body: string; inReplyToExt: number|null; createdAt: string; }
```

## Endpoints
| Method & path | operationId | Query / body | Returns |
|---|---|---|---|
| GET `/api/sessions` | `listSessions` | `status?, type?, needsHuman?, q?, limit?, cursor?` | `Page<SessionSummary>` |
| GET `/api/sessions/{id}` | `getSession` | | `SessionDetail` |
| GET `/api/sessions/{id}/events` | `listSessionEvents` | `limit?, cursor?` (ascending by id) | `Page<EventItem>` |
| GET `/api/sessions/{id}/mails` | `listSessionMails` | | `MailDetail[]` (oldest first) |
| GET `/api/sessions/{id}/draft` | `getSessionDraft` | | `Draft` or 404 |
| POST `/api/sessions/{id}/actions` | `sessionAction` | `SessionAction` | `SessionSummary` |
| GET `/api/events` | `listEvents` | `kind?, limit?, cursor?` (newest first, global feed) | `Page<EventItem>` |
| GET `/api/events/{id}/payload` | `getEventPayload` | | `EventPayload` |
| GET `/api/mails` | `listMails` | `direction?, q?, limit?, cursor?` (newest first) | `Page<MailSummary>` |
| GET `/api/mails/{id}` | `getMail` | | `MailDetail` |
| GET `/api/attachments/{id}` | `downloadAttachment` | | raw bytes |
| GET `/api/llm-calls` | `listLlmCalls` | `sessionId?, limit?, cursor?` | `Page<LlmCall>` |
| GET `/api/llm-calls/{id}` | `getLlmCall` | | `LlmCall` with request/response |
| GET `/api/bookings` | `listBookings` | `status?, limit?, cursor?` | `Page<Booking>` |
| GET `/api/shipments/active` | `listActiveShipments` | | `ActiveShipment[]` |
| GET `/api/stats` | `getStats` | `hours?` (default 24) | `Stats` |
| GET `/api/board` | `listBoardResults` | | `BoardResult[]` |
| GET `/api/tariffs` | `listTariffs` | | `TariffSummary[]` |
| GET `/api/tariffs/{id}` | `getTariff` | | `TariffDetail` |
| POST `/api/tariffs/{id}/activate` | `activateTariff` | | `TariffSummary` |
| POST `/api/tariffs/{id}/reject` | `rejectTariff` | | `TariffSummary` |
| GET `/api/carriers` | `listCarriers` | | `Carrier[]` |
| GET `/api/settings` | `listSettings` | | `Setting[]` |
| PUT `/api/settings/{key}` | `updateSetting` | `{ value: unknown }` | `Setting` |
| GET `/api/stream` | `stream` | | `text/event-stream` (see below) |

## Live stream (`GET /api/stream`, Server-Sent Events)
- `event: event`, `data: EventItem`: every appended event.
- `event: session`, `data: SessionSummary`: whenever a session row changes.
- `event: ping`, `data: {}`: every 15 s as a heartbeat.

The frontend updates or invalidates TanStack Query caches from these events, so no page ever needs a manual refresh and nothing polls.

## Admin
A hidden admin page (not linked in the navigation) for the gateway key and the live switches.

```ts
interface AdminStatus {
  openAiKeyRegistered: boolean|null;   // from GET {5U}/v1/gateway/openai-key; null if unknown/unreachable
  openAiKeyMessage: string|null;
  pollingEnabled: boolean;             // runtime setting "pollingEnabled"
  autopilot: boolean;                  // runtime setting "autopilot": answer live mails automatically
  envPollerEnabled: boolean;           // app.poller.enabled (POLLER_ENABLED), read-only
  envSendEnabled: boolean;             // app.mail.send-enabled (SEND_ENABLED), read-only
}
interface OpenAiKeyRequest { apiKey: string; }
interface OpenAiKeyResult { ok: boolean; message: string; }
interface LiveUpdate { pollingEnabled?: boolean; autopilot?: boolean; }   // only present fields change
```

| Method & path | operationId | Body | Returns |
|---|---|---|---|
| GET `/api/admin/status` | `getAdminStatus` | | `AdminStatus` |
| POST `/api/admin/openai-key` | `registerOpenAiKey` | `OpenAiKeyRequest` | `OpenAiKeyResult` (proxies `POST {5U}/v1/gateway/openai-key {"api_key"}`) |
| PUT `/api/admin/live` | `setLive` | `LiveUpdate` | `AdminStatus` |

- The OpenAI key is never logged, stored or put into an event. Registration writes only `admin.openai_key_registered` (actor human) with ok/failed and the key's last 4 characters.
- `setLive` writes `human.live_toggled` with the changed switches.

## Auth
There is no user auth. The frontend checks `APP_PASSWORD` from its env: a login page sets an httpOnly cookie, and the Next.js proxy (Next 16 renamed `middleware` to `proxy`) blocks every page and `/api/*` route without it. The browser never talks to the backend directly. The frontend proxies `/api/*` (including SSE) to `BACKEND_URL`. The backend trusts its network, and compose only publishes 8080 for local development.
