# 5U AI Challenge — Condensed Brief

START HACK Munich 2026 (21.11.2026). Partner: 5U AI (Fehmi, Erhan, Taci, Linde). 5U builds AI workers for freight forwarders: read any mail in any language 24/7, work in the team's tools, reply in the team's tone, hand judgement calls to people.

**Task:** build a digital air forwarder that quotes, books and tracks shipments through one mailbox, by itself, all night. The forwarder buys transport for the customer: customer sends an RFQ by email → we find the best price → quote, book, track. Airlines and truckers bid by email.

## Cadence & rules
- A new air export RFQ reaches every team **every 6 min** and **expires 6 min later**. First request at 16:00.
- All teams get the same request at the same time and are scored separately. Several teams can do well.
- No second chance: a missed or rejected request is lost.
- Only mail started by 5U's agents is scored. Mail between teams changes nothing.
- After a few hours, the **setup changes about once an hour, unannounced**. Teams find out from failed quotes.
- **Scope:** air export RFQs only, including a door-to-port option (truck pickup + flight). **Out of scope:** building a TMS, margins, ocean freight, competing with other teams.

## Required steps
1. Read and route every mail: customer request, carrier bid, portal/desk reply, tariff sheet.
2. Price from rate sheets + rate portal + bids from 2–5 carriers.
3. Quote **one** option, the one the customer asked for: **cheapest or fastest**.
4. When the customer accepts, book by rate ID and keep the customer informed.
5. Answer tracking questions from our own CRM.
6. Reply in the customer's thread (`in_reply_to`). **Only the first quote in a thread counts.**
7. Every quote states **one all-in total with its currency**.

## What we build
| Part | Content |
|---|---|
| Router + ≤3 agents | Router sorts mail. Agents: **RFQ & booking**, **track & trace**, **tariff ingestion** |
| Pricing module | Holds tariffs as structured prices. New tariffs are ingested automatically |
| CRM | Quotes and booked shipments: history, won/lost, dashboard |
| Sessions UI (streaming) | Where a person steps in. Finding a session and intervening must be easy |

## Architecture
```
                 ┌────────────────────── 5U runs ──────────────────────┐
                 │ Request engine · Customer agents · Carrier agents   │
                 │ Scorer + leaderboard                                │
                 └───────┬──────────────────┬──────────────────┬───────┘
                         │                  │                  │
                     MAILBOX           RATE PORTAL        LLM GATEWAY
                  (1 per team,      (API / browser /    (OpenAI only,
                   all logged)       ratedesk email)     counts cost)
                         │                  │                  │
┌──────────────── we build ──────────────────────────────────────────────┐
│  poller ─► ROUTER ─┬─► RFQ & booking agent ──► pricing module ◄─ tariffs│
│                    ├─► tracking agent ───────► CRM                     │
│                    └─► tariff ingestion ─────► pricing module          │
│  CRM (quotes, bookings, won/lost, dashboard) · Sessions UI (streaming) │
└────────────────────────────────────────────────────────────────────────┘
```

One request, three stages:
- **Quote:** RFQ arrives → check rates → ask 2–5 carriers → quote within 6 min.
- **Book:** customer accepts → book with rate ID → confirm the booking.
- **Track:** customer asks for status → answer from the CRM.

## What 5U provides
| Part | Details |
|---|---|
| Start kit | 10 airline rate sheets in mixed formats, carrier list (airline + trucker emails), pickup zones. Some airlines are **only in the sheets**, others **only on the portal** |
| Mailbox | `aeiou@hackloop.mail`. Every mail in and out is logged |
| Rate portal | API with rate IDs + booking (rate-limited; a rate ID is valid **15 min**), a browser page behind a bot challenge, and a desk at `ratedesk@hackloop.mail` that answers by email |
| Customer agents | One sends RFQs and follow-ups. One asks tracking questions from `tracking@hackloop.mail` |
| Carrier agents | 2–5 per destination answer bid requests by email. Airlines also send monthly tariffs; truckers don't |
| LLM gateway | One key per team, OpenAI models only, uses our own OpenAI key, counts each team's cost. **A quote made without a gateway call earns no cost points** |
| Leaderboard | Score per email and in total. Who booked what is visible to everyone |
| API docs | Swagger at `<base>/docs` |

## Scoring
Up to **100 points per accepted quote**. Total = sum over all requests; every case is worth the same.

| | Brief | Slides | Rule |
|---|---|---|---|
| Price accuracy | 45 | 55 | Full points at the best price, fewer the further above it |
| LLM cost | 40 | 30 | Gateway spend per accepted quote in that hour: full at $0, zero at **$0.10** |
| Response time | 15 | 15 | Full within **2 min** of the request, zero at **6 min** |

- **Accepted** = within **5% of the best price**, first reply in the thread, within 6 min. Only accepted quotes score.
- Among carrier bids, the right one is the **most balanced: price and speed weighted 50/50**.
- "Read the rate sheets closely. The best price is not always the obvious one."
- Cost points are added hourly, up to ~1 h after a quote.
- **Bonus:** +10 for a correct tracking answer on a booked shipment. +10 from the jury for the accepted email that reads most human.
- **Winner:** the leaderboard alone doesn't decide. Every team pitches (3 min + 2 min Q&A). Jury criteria: 1. pitch, 2. UX & design, 3. codebase (tech depth), 4. how human the emails read, plus the leaderboard.
- Prizes: 2nd Hans Zimmer concert tickets (Munich); 3rd exclusive founders dinner.

## API — `https://api.start-hack.5u.ai`
Auth: `X-API-Key: <team key>` or `Authorization: Bearer <key>`. The key is `TEAM_API_KEY` in `.env`. The same key is used for mailbox, portal and gateway. Everything sent with it counts as ours; a lost key is replaced at the 5U booth.

**Mailbox**
- `GET /v1/mailbox/messages?since_id=N&limit≤200` returns mail sent *to* us with id > N, oldest first. There is no push, so poll every 1–2 s. Store `next_since_id`; ids only grow. `references` = thread ids from the first mail down to the parent. `attachments` = id, filename, type, size.
- `GET /v1/mailbox/messages/{id}`; `GET /v1/mailbox/messages/{id}/attachments/{aid}` (raw bytes).
- `POST /v1/mailbox/messages` `{to, subject, body, in_reply_to, attachments[{filename, content_type, content_base64}]}` → 201. `in_reply_to` must be a mail we sent or received (else 404). Allowed recipients: customers, carriers on the list, `ratedesk@`, `tracking@`; anything else → 422. Max 5 attachments, 10 MB each; types pdf/xlsx/xls/csv/txt/png/jpg/docx. **60 mails/min**, then 429.

**Portal**
- `GET /v1/portal/ports` lists lanes (German origin → destination) with their airlines. Destinations not listed are priced by email to the airline.
- `GET /v1/portal/brackets?airline=` returns `breaks_kg`; a bracket runs from its break to the next one. Airlines not on the portal → 404 with their email.
- `GET /v1/portal/rates?origin&destination&airline&weight_kg&volume_cbm` returns `rate_id, total` (freight + fuel + security), `currency, fx_eur_per_usd` (when not EUR), `chargeable_weight_kg` = max(kg, cbm×167), `transit_days, valid_until, dataset_version`. Rate-limited per minute (`X-RateLimit-Limit/Remaining`; 429 + `Retry-After`). Ports and brackets don't count. 503 → the message says where else to get the rate.
- `POST /v1/portal/bookings {rate_id, team_reference}`. 201 booked · 200 retry of the same pair · 404 unknown rate_id · 409 already booked under another reference · 410 expired (get a fresh rate) · 503 book by email with the airline. Then a mail "Booking <BK-xxxxxxxx> confirmed" arrives. There is no status call, so the CRM is the source of truth.

**Gateway (OpenAI-compatible, base_url `<base>/v1/gateway`, api_key = team key)**
- One-time setup: `POST /v1/gateway/openai-key {"api_key":"sk-..."}`; `GET` shows whether a key is stored.
- `/models`, `/chat/completions`, `/responses` (both support streaming), `/embeddings`.

**Board:** `GET /v1/board` (public: rank, totals, bookings, last 10 rounds; `frozen_at`). `GET /v1/board/me` returns per request: round, sent/answered time, verdict (`accepted|rejected|pending|open|no answer|voided`), **reason code**, points per kind, booked flag; plus our bookings. **This is our signal for the hourly changes.**

Other links: portal UI `https://start-hack.5u.ai/portal/ui`, leaderboard `https://api.start-hack.5u.ai/board`, kit zip `<base>/kit/start-kit.zip`.

## Start kit (`start-kit/`)
- **Tariffs (Nov 2026):** Cobalt (pdf), Danube Wings (2× jpg, needs OCR), Elbflug, Kestrel, Vega, Wayzata (xlsx), Helix, Orion (pdf), Quetzal (docx), Yellowtail (txt).
  - Structure: rows by lane, a minimum charge, and per-kg rates by break (−45/+45/+100/+250/+500/+1000/+2000), plus fuel and security per kg of billed weight.
  - Chargeable weight = max(actual, cbm×167). The minimum applies to freight before surcharges.
  - Trap: rates aren't always lower at higher breaks, and billing at the next break can be cheaper.
- **carrier-list.csv:** 41 airlines (+ the destinations each serves) and 10 truckers (+ the 2-digit postcode zones they serve), with emails `<slug>@hackloop.mail`.
- **pickup_zones.csv:** 2-digit postcode prefix → area → origin airport (FRA, MUC, DUS, STR, CGN, HAM).

## Sender identity (for email signatures)
5U AI B.V., Rotterdam, KvK 97730726, VAT DE464837483, MD Ali Yagizhan Abik · 5U AI GmbH, AG München HRB 313601, USt-IdNr. DE464836232, GF Ali Yagizhan Abik & Fehmi Sener. Address for both: Seidlstraße 26, 80335 München.
