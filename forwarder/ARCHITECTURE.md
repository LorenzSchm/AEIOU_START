# Architecture — Team AEIOU digital air forwarder

Companion to [BRIEF.md](../BRIEF.md). Facts from the brief and API docs are marked **(given)**. Everything else is our design and can change.

## 1. Goals that shape the design
| Goal | Consequence |
|---|---|
| Quote within 2 min (full time points), never later than 6 | Single backend process with no queues between services, parallel I/O on virtual threads, tariffs held in memory |
| Within 5% of the best price **(given)** | A deterministic pricing module that checks every source (sheet, portal, bids, rate desk) and tries every weight break |
| LLM cost: full points at $0, zero at $0.10 per quote, and a quote with no gateway call gets no cost points **(given)** | Exactly 1–2 small LLM calls per RFQ. No LLM in the price math |
| Only the first quote in a thread counts **(given)** | A database constraint allows one quote per session |
| Unannounced changes every hour **(given)** | Settings editable at runtime, a watchdog on `/v1/board/me`, an LLM fallback when parsing fails, a human in the loop |
| Jury judges UX, codebase and how human the emails read **(given)** | Full audit trail, live control panel, 3D globe, **every** outgoing mail (customer replies, bid requests, rate-desk questions) written by `EmailWriter` to read like a real person |

## 2. System overview
```
                       5U (given)
   Mailbox API ──── Rate portal API ──── LLM gateway ──── Board API
        │                 │                   │               │
┌───────┼─────────────────┼───────────────────┼───────────────┼────────┐
│ BACKEND  Spring Boot 3 · Java 21 · virtual threads                    │
│       │                 │                   │               │        │
│  MailPoller (1s) ──► Router ──┬─► RfqAgent ─────► PricingService      │
│       ▲                       │      │  ▲              ▲ (in-memory)  │
│       │ MailSender ◄──────────┤      │  └─ BidCollector               │
│       │                       ├─► TrackingAgent ──► CRM              │
│       │                       └─► TariffIngestion ─► TariffStore      │
│  BoardWatcher (60s)    EventLog (append-only) ──► SSE hub            │
│                        REST API + OpenAPI (springdoc)                 │
└───────────────┬───────────────────────────────┬──────────────────────┘
                │ JDBC                           │ REST + SSE
          PostgreSQL 16                  FRONTEND Next.js (App Router)
                                         orval client · TanStack Query
                                         control panel · CRM · globe
```
Everything runs in `docker compose`: `postgres`, `backend`, `frontend`.

## 3. Mail pipeline

### 3.1 Poller
- `GET /v1/mailbox/messages?since_id=N&limit=200` every ~1 s. `N` is stored in `poller_state` so a restart neither loses nor repeats mail **(given: ids only grow)**.
- Each mail is upserted by `external_id` (idempotent), its attachments are downloaded, a `mail.in` event is written, and the mail goes to the router on its own virtual thread.

### 3.2 Router: two stages
**Stage 1, hard facts (free, ~0 ms).** Decides only unambiguous cases; otherwise it attaches hints for stage 2.

| Signal | Source | Route |
|---|---|---|
| Sender is in `carrier` and the mail replies to our bid request | carrier-list.csv **(given)** + our thread | `CARRIER_BID` |
| Sender is in `carrier` with a tariff-like attachment and no thread of ours | carrier-list.csv; airlines send monthly tariffs **(given)** | `TARIFF` |
| Sender is `ratedesk@hackloop.mail` | **(given)** | `RATEDESK_REPLY` |
| Sender is `tracking@hackloop.mail` | **(given)** | `TRACKING_QUESTION` |
| Subject matches `Booking BK-… confirmed` | **(given)** | `BOOKING_CONFIRMATION` |
| Anything else | | stage 2 |

**Stage 2, one LLM call (classify + extract).** Uses structured output (JSON schema), `gpt-4o-mini`-class model, and returns:
```json
{ "type": "NEW_RFQ|ACCEPTANCE|FOLLOW_UP|CARRIER_BID|TARIFF|TRACKING_QUESTION|OTHER",
  "confidence": 0.0-1.0,
  "rfq":  { "origin_zip", "origin_airport", "destination", "weight_kg", "volume_cbm",
            "pieces", "mode": "cheapest|fastest", "door_pickup": bool, "ready_date", "commodity" },
  "bid":  { "price", "currency", "transit_days", "valid_until", "airline" },
  "tracking": { "reference", "question" } }
```
- Customer mails always reach stage 2, because the RFQ has to be extracted anyway, so classifying it in the same call costs almost nothing.
- **Needs human** when confidence is below the threshold (setting, default 0.75), when the result contradicts a stage-1 hint, or when the schema fails validation. The session is flagged and shown at the top of the control panel.
- Every decision is stored as a `route.decision` event, and human corrections as `human.reroute`. Together they form a labelled dataset in case we add an embedding classifier later.

### 3.3 Sessions
A **session** is one unit of work, usually one customer thread. Thread linking uses `references` / `in_reply_to` **(given)**. Bid requests, carrier replies and rate-desk answers attach to the session that caused them. Tariff mails get their own session.

## 4. Agents

### 4.1 RFQ & booking agent
```
T+0     NEW_RFQ extracted (router stage 2)
T+~2s   fan out in parallel:
          a) sheet price from TariffStore for every airline on the lane
          b) portal: /ports → /rates for each airline on the lane (respect X-RateLimit-*)
          c) bid request mails to the airlines serving the destination (2–5 answer, given)
          d) door pickup → zip prefix → pickup_zone → truckers serving the zone → bid request
          e) portal 503 / lane missing → ratedesk@ mail
T+bidWait (setting, default 90 s) or all answered → decide
T+~100s quote mail in the customer thread (one all-in total + currency, given)
```
**Decision.** All candidates are converted to EUR, using `fx_eur_per_usd` when given.
- `cheapest`: lowest all-in total.
- `fastest`: lowest transit; ties go to the lower price.
- Carrier bids: the most balanced bid, price and speed 50/50 **(given)**. Our formula: `score = 0.5·price/minPrice + 0.5·transit/minTransit`, lowest wins. It needs checking against the first results.
- Door-to-port: trucker price + air price.

Every candidate is stored in `price_candidate` with `chosen` or a `reject_reason`, so the UI can show why it won.

**Quote mail.** A second small LLM call writes a natural reply from a fixed fact block (total, currency, airline, transit, validity) and a few style rules. A post-check regex makes sure the total and currency appear exactly once and unchanged; if not, a template is used instead.

**One quote per session.** `UPDATE session SET quoted_at=now() WHERE id=? AND quoted_at IS NULL` must affect exactly 1 row before anything is sent.

**Acceptance.** The `rate_id` behind the chosen candidate goes to `POST /v1/portal/bookings` with `team_reference = session.public_ref`.
- 410 means the rate expired: get a new rate and book again.
- 503 means book by email with the airline.

Confirm to the customer, and mark the booking `confirmed` when the "Booking … confirmed" mail arrives. Rate IDs are only valid for 15 minutes **(given)**, so a late acceptance always triggers a new rate.

### 4.2 Tracking agent
- Look up the booking by reference, customer or thread, then answer from the CRM (status, airline, booking ref, departure, ETA). One small LLM call writes the reply.
- A correct answer on a booked shipment is worth +10 **(given)**.
- If nothing matches, the session needs a human.

### 4.3 Tariff ingestion
- **Start kit, run once and seeded:** one parser per format (xlsx via Apache POI, pdf via PDFBox, docx via POI, txt). The two Danube Wings JPGs go through a vision model once offline and are checked by hand. Each sheet gets a unit test.
- **Monthly tariff mails:** if the file matches a known layout, use the deterministic parser; otherwise an LLM extracts it into the `tariff_lane` schema. Sanity checks against the previous version: lane count, rate deltas within ±50%, break order. If a check fails, the tariff is saved as `pending_review` and needs a human; it is never activated silently.
- The new version becomes active from its `valid_from`, and the in-memory `TariffStore` reloads atomically.

## 5. Pricing module
```
chargeable = max(weight_kg, volume_cbm × 167)                              (given)
for each break b ≥ bracket(chargeable):        # a higher break can be cheaper (given hint)
    billed  = max(chargeable, b)
    freight = max(rate[b] × billed, minimum)    # minimum applies to freight before surcharges (given)
    total_b = freight + (fuel + security) × billed
price = min(total_b)  → convert to EUR
```
- Pure Java with no I/O, backed by the in-memory `TariffStore`. A lookup takes microseconds.
- **Check against the portal:** for airlines that appear both in a sheet and on the portal, compare our price with `/v1/portal/rates` and log differences as `pricing.mismatch` events. This catches formula errors early.
- Open question for the first rounds: are fuel and security charged on `billed` or on `chargeable` weight? The tariff text says "weight the freight is billed on", so we start with `billed`.

## 6. Event log & audit trail
Every action becomes an append-only `event` row tied to a session, and is pushed to the SSE hub right after commit.

| Kind | Payload |
|---|---|
| `mail.in` / `mail.out` | mail id (full body and attachments are in `mail`) |
| `route.decision` | stage, type, confidence, hints |
| `llm.call` | purpose, model, tokens, cost_usd, latency, prompt + response (also in `llm_call`) |
| `portal.rate` / `portal.booking` / `portal.error` | request, response, HTTP status, rate-limit headers |
| `bid.requested` / `bid.received` / `bid.timeout` | carrier, mail id, parsed bid |
| `pricing.candidate` / `pricing.decision` / `pricing.mismatch` | candidates, chosen, reasons |
| `quote.sent` / `booking.created` / `booking.confirmed` | ids, totals |
| `tariff.ingested` / `tariff.rejected` | airline, version, diff summary |
| `human.*` | approve, edit_send, reroute, take_over, resolve, setting_changed (with user) |
| `board.verdict` | verdict + reason code from `/v1/board/me` |
| `system.alert` | rejection spike, 429/503 bursts, parse failures |

## 7. Data model (PostgreSQL 16, migrated with Flyway)
```
poller_state      key PK, since_id
mail              id PK, external_id UNIQUE, direction(in|out), from_addr, to_addr, subject, body TEXT,
                  in_reply_to_ext, references_ext BIGINT[], session_id FK, received_at, raw JSONB
attachment        id PK, mail_id FK, external_id, filename, content_type, size, content BYTEA
session           id PK, public_ref UNIQUE, type, status, customer_addr, mode, needs_human BOOL,
                  deadline_at, quoted_at, quoted_total_eur, quoted_currency, booked BOOL,
                  llm_cost_usd, event_count, last_event_at, created_at, updated_at
                  -- doubles as the denormalized summary for list views
event             id BIGSERIAL PK, session_id FK, mail_id FK NULL, kind, actor(system|llm|human),
                  summary TEXT, payload JSONB, created_at
rfq               session_id PK/FK, origin_zip, origin_airport, destination, weight_kg, volume_cbm,
                  chargeable_kg, mode, door_pickup, ready_date, commodity
bid_request       id PK, session_id FK, carrier_id FK, kind(airline|trucker), out_mail_id, in_mail_id,
                  sent_at, answered_at, price, currency, transit_days
price_candidate   id PK, session_id FK, source(sheet|portal|bid|ratedesk|truck), airline_code,
                  total, currency, total_eur, transit_days, rate_id, valid_until, chosen BOOL, reject_reason
booking           id PK, session_id FK, rate_id, team_reference, booking_reference, airline_code,
                  origin, destination, price, currency, status(booked|confirmed), booked_at,
                  departure_at, eta_at
llm_call          id PK, session_id FK, purpose, model, prompt_tokens, completion_tokens, cost_usd,
                  latency_ms, request JSONB, response JSONB, created_at
tariff            id PK, airline_code, version, source(kit|mail), source_mail_id, valid_from, valid_to,
                  currency, status(active|pending_review|superseded)
tariff_lane       tariff_id FK, origin, destination, transit_days, minimum, breaks JSONB, fuel, security
carrier           id PK, name, kind, email UNIQUE, serves TEXT[]
pickup_zone       zone PK, area, airport
airport           iata PK, name, city, lat, lon              -- for the globe
board_result      request_id PK, round, verdict, reason_code, points JSONB, fetched_at
setting           key PK, value JSONB, updated_at, updated_by
```
**Indexes:** `event(session_id, id)`, `event(created_at DESC)`, `session(status, last_event_at DESC)`, `session(needs_human) WHERE needs_human`, `mail(session_id, received_at)`, `mail(in_reply_to_ext)`, a GIN full-text index on `mail(subject, body)`, `booking(booking_reference)`, `booking(status)`.

## 8. Backend REST API (for the frontend)
| Endpoint | Purpose |
|---|---|
| `GET /api/sessions?status&type&needsHuman&q&cursor` | Session list, cursor-paginated, reads only from `session` |
| `GET /api/sessions/{id}` | Summary + RFQ + candidates + booking |
| `GET /api/sessions/{id}/events?cursor` | Timeline |
| `POST /api/sessions/{id}/actions` | `approve`, `edit_and_send`, `reroute`, `take_over`, `resolve` |
| `GET /api/mails?cursor&q`, `GET /api/mails/{id}`, `GET /api/attachments/{id}` | Mail browser |
| `GET /api/llm-calls?sessionId` | Prompt/response inspection |
| `GET /api/shipments/active` | Globe data: bookings + airport coordinates |
| `GET /api/stats` | KPIs: quotes, accepted, booked, cost per quote, median response time, by hour |
| `GET /api/tariffs`, `GET /api/tariffs/{id}`, `POST /api/tariffs/{id}/activate` | Tariff review |
| `GET /api/board` | Cached `/v1/board/me` |
| `GET/PUT /api/settings` | Runtime tuning: bidWait, models, confidence threshold, autopilot on/off |
| `GET /api/stream` | SSE stream of events: `{kind, sessionId, summary, …}` |

## 9. Live data
- **Backend:** after each commit the event goes to an in-process SSE hub (`SseEmitter` per client, heartbeat every 15 s). If we ever run more than one backend instance, the hub moves to Postgres `LISTEN/NOTIFY`.
- **Frontend:** one `EventSource` for the whole app. Each event updates the TanStack Query cache directly where possible (`setQueryData` on session lists and the open timeline) and otherwise invalidates the affected query keys. No manual refresh and no polling.
- **Deadline timers** count down on the client from `deadline_at`, so they don't need server ticks.

## 10. Frontend (Next.js App Router)
- **API client:** generated from the backend's `/v3/api-docs` with **orval**, producing typed fetchers and TanStack Query hooks, via `npm run gen:api`.
- **Views:**
  1. **Live board:** sessions as a list or kanban by status, with deadline countdowns, needs-human items pinned on top, and cost and points per session.
  2. **Session detail:** the full timeline (mails, LLM calls, portal calls, decisions) with raw JSON on demand, a price candidates table showing the chosen one and the reasons, and action buttons (approve, edit & send, re-route, take over).
  3. **Mailbox:** every mail in and out, searchable and linked to its session.
  4. **CRM & dashboard:** quotes won/lost, bookings, accuracy versus board verdicts, LLM cost per hour, response-time distribution, and a rejection-reason feed (the early warning for hourly changes).
  5. **Globe:** see §11.
  6. **Tariffs & settings:** tariff versions, review queue, runtime settings.

## 11. 3D globe
- `react-globe.gl` (three.js), loaded with `dynamic(..., { ssr: false })`.
- **Arcs:** origin to destination for every booked or confirmed shipment, coloured by status.
- **Plane:** a custom 3D object placed on the great circle at `progress = (now − departure_at) / transit`. The shipments and airlines are fictional, so no real tracking feed exists (Flightradar24 doesn't apply, and its API is paid anyway).
- **Time mode toggle:** *real*, where transit takes days, or *demo*, where each arc loops in about 10 s.
- **`departure_at`:** the RFQ ready date if one is given, otherwise the booking confirmation time. `eta_at = departure_at + transit_days`.
- **Optional background layer:** real cargo traffic from the free OpenSky Network API, shown as faint dots.
- Airport coordinates are seeded into `airport` for all IATA codes in the kit (origins FRA, MUC, DUS, STR, CGN, HAM plus all destinations).

## 12. Detecting hourly changes
- **BoardWatcher** polls `/v1/board/me` every 60 s and stores verdicts and reason codes as `board.verdict` events. Two rejections in a row, or a new reason code, raise `system.alert`, shown as a banner in the UI.
- **Defensive defaults:**
  - Any unexpected HTTP status from the portal sends us to the email channel (rate desk or airline).
  - Extraction or parsing failures fall back to an LLM, and if that fails too, the session goes to a human.
  - Tariff versions are compared before activation.
- All tuning knobs live in `setting` and can be changed in the UI without a redeploy.

## 13. Performance budget
- **Target:** p95 under 50 ms for every UI endpoint.
- **List views** read only `session` (denormalized counters updated in the same transaction as the event), use cursor pagination (`WHERE (last_event_at, id) < (?, ?)`), never OFFSET, and return DTO projections without JSONB payloads.
- **Detail views** load payloads lazily per event.
- **No N+1 queries:** jOOQ or explicit SQL for read models; JPA only where it really helps.
- **Pricing** runs in memory. Portal calls go out in parallel through a token bucket that follows the `X-RateLimit-*` headers.
- HikariCP is sized for virtual threads (around 20 connections), and long LLM or HTTP calls never hold a DB connection.

## 14. Repository layout & running
```
forwarder/backend   Spring Boot 4 (Gradle, Java 21), Dockerfile
forwarder/frontend  Next.js 16, Dockerfile, src/api/generated (orval, committed)
forwarder/API_CONTRACT.md  REST + SSE contract between both
../start-kit        given tariffs + CSVs (seed source)
docker-compose.yml postgres (healthcheck) → backend (depends_on healthy) → frontend
.env               TEAM_API_KEY, OPENAI model names, POSTGRES_* (git-ignored)
```
`docker compose up --build` starts everything. The frontend uses `BACKEND_URL` internally and a public URL in the browser for SSE.

## 15. Human-sounding email (applies to every outgoing mail)
- One `EmailWriter` service creates every mail body: quotes, booking confirmations, tracking answers, bid requests to airlines and truckers, rate-desk questions.
- Input is a fact block plus the conversation so far. The output reads like a named person on our desk:
  - answer in the sender's language and greet them by name if they signed
  - mirror their register (formal or casual)
  - short paragraphs, no bullet-point walls, no "I hope this email finds you well", no AI clichés
  - vary the phrasing between mails
  - sign with a consistent persona (first name + "AEIOU Air Freight" desk)
- Hard facts (total, currency, airline, transit, references) are checked afterwards with a regex. If a check fails, the mail is rewritten once and otherwise falls back to a human-written template.

## 16. Access
There is no user management. `APP_PASSWORD` in `.env` protects the control panel: a login page sets an httpOnly cookie, and the Next.js proxy checks it on every page and `/api/*` call. The browser only talks to Next.js, which forwards `/api/*` and the SSE stream to the backend.

## 17. Open points
- Formats of carrier bids, rate-desk answers and customer follow-ups are unknown until the first mails arrive. Store them and tune the prompts against them.
- Check the 50/50 bid formula and the surcharge base against the first board verdicts.
