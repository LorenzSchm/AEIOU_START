# Frontend bonus feature proposals

This is research only. It covers the "wave desk" in `frontend/app` as it stands on `feat/bullshitBranch`. The figures come from read-only GETs against the live replica at `http://127.0.0.1:8000/api/v1` on 2026-11-21 at about 22:25 UTC. That snapshot held 651 messages, 76 RFQs, 74 board verdicts and 42 bookings. Translation is out of scope because it is being built separately.

## What the jury and the scorer reward

| Source | Requirement | Weight |
| --- | --- | --- |
| Brief, criteria | Price accuracy: full marks at the best price, within 5% to be accepted | 45 pts |
| Brief, criteria | LLM cost: gateway spend per accepted quote, full at 0 USD, zero at 0.10 USD | 40 pts |
| Brief, criteria | Response time: full within 2 min, zero at 6 min | 15 pts |
| Brief, bonus | Correct tracking answer on a booked shipment | +10 |
| Brief, bonus | Most human accepted email (jury) | +10 |
| Brief, final ranking | Jury: 1. pitch (3 min + 2 min Q&A), 2. UX and design, 3. codebase, 4. how human the emails read | decides the winner |
| Brief, "What a team builds" | **CRM**: quotes and booked shipments, history, won and lost, a dashboard | required |
| Brief and deck, "ALSO REQUIRED" | **Sessions UI with streaming**: where a person steps into the autonomous system. *Finding a session and intervening has to be easy.* | required |
| Deck, 5U pitch | "Judgement calls go to people" | required |
| Brief, rules | The setup changes about once an hour, unannounced. Teams learn of a change from failed quotes. | robustness story |

## Required items: current state of the UI

| Required item | State | Evidence |
| --- | --- | --- |
| Sessions UI with **streaming** | **Weak** | `lib/api.js` re-fetches the full `/messages` payload (~970 KB) every 2 s. Nothing streams and there is no event feed. The backend has no SSE or WebSocket endpoint (`DispatchController` only has GET/POST). |
| **CRM** (history, won/lost, dashboard) | **Missing** | No view lists bookings, customers or won/lost. The 42 bookings (`BK-…`, `AEIOU-…` refs) are visible only one case at a time. |
| Judgement calls go to people | **Partial, with a bug** | `needsAttention()` in `lib/format.js` covers `kind === "request"` only and excludes `delivery === "sent"`. It therefore hides 14 `tracking review` mails and 4 sent quotes whose `review_reason` is *"Booking needs attention: The carrier's price or transit changed…"* (ids 16038, 15227, 14945, 14657). The 6 dangerous-goods holds in `waiting for documents` are also absent from the Attention scope and the nav badge. |
| Finding a session easily | Partial | The Queue search matches subject, sender, id, body and airports. It does not match booking reference, team reference (`AEIOU-20758`), postcode, airline or outgoing mail. |
| Score view of the criteria | Partial | The Overview shows a single "5U board points" sum. The per-criterion breakdown (`accuracy`, `cost`, `time`, `tracking`, `humanity`, `booking`) exists in `board_result.points` but appears only inside an opened case. |
| Evidence that inputs were understood | Partial | `voice_transcript` (10 voice RFQs and callbacks) and `carrier_offer_text` (13) are never rendered. The outgoing kind `requote` has no label in `conversation.jsx`. |

## Live numbers the UI could already show

Everything here is derived client-side from the current API with no backend change:

- 74 verdicts: 53 accepted, 15 rejected (6 `too_low`, 6 `too_high`, 1 `wrong_language`, 1 `excluded_airline`, 1 `dg_docs_missing`), 6 `no answer`. Total board points: 5,072.
- Average points per scored quote: accuracy 38.8 / 45, cost 39.97 / 40, time 13.9 / 15, tracking 6.2 / 10 (45 cases), humanity +10 on 11 cases.
- Implied LLM spend: `(40 - cost) / 400` USD. That gives about **0.00007 USD per quote**, roughly 1,500 times below the zero-point threshold. This assumes the brief's linear scale, so label it "derived".
- Time to quote, from the outgoing quote time minus `received`, over 62 quotes: median **36 s**. 46 of the 62 landed inside 2 minutes and the slowest took 349 s.
- 62 quotes, 42 booked (68% conversion), 11 customers, 38 cheapest and 36 fastest requests, RFQs in German, French and Chinese.
- 44 of 76 proposals list the same airline at the same price twice, for example portal plus carrier bid. `OfferBars` shows both rows.

---

## Prioritized proposals

The order weighs pitch impact, required-item coverage and effort. Effort: S = under 2 h, M = half a day, L = a day or more.

### 1. Live scorecard: the scoring formula as a panel
- **What**: an Overview panel with three gauges filled to the team's average (accuracy /45, LLM cost /40, response time /15) and two bonus counters (tracking +10 hits/misses, humanity +10). Below them, a "points left on the table" list grouped by `board_result.reason` (`too_low`, `too_high`, `wrong_language`, `no answer`…), where each entry opens its cases. Add a headline stat for LLM spend per quote (derived USD, compared with the 0.10 USD zero line) and the acceptance rate.
- **Serves**: all three scored criteria and both bonuses. Pitch, UX.
- **Why**: the jury sees its own formula live on screen, with the team scoring near the maximum on cost and time. It also shows where points went missing, and for an operator that is the to-do list after an hourly setup change.
- **Effort**: S–M.
- **Files**: `components/overview/Overview.jsx`, new `components/overview/Scorecard.jsx`, `lib/process.js` (a `scoreSummary()` next to `waveSummary()`), `style.css`.
- **Data**: available (`board_result.points`, `verdict`, `reason`, `total`, `cost_pending`). The spend figure is derived. Exact spend would need the backend to expose gateway usage (see #11).

### 2. Fix and upgrade the judgement-call inbox
- **What**: (a) Widen `needsAttention()` to cover `tracking review`, quotes with a post-send `review_reason` (booking terms changed), and the DG/document holds as a separate "Waiting on customer" bucket. (b) Give each attention row a "why and next step" card, for example *Price changed: approve requote* or *DG docs missing: waiting since 4 min*, with the one matching action button. (c) Show the counts in the nav badge and the sidebar.
- **Serves**: "Judgement calls go to people" and "finding a session and intervening has to be easy". UX.
- **Why**: today the Attention tab can say "Nothing needs you" while 18+ mails need a person. That is a real operator bug and something a jury member can catch during Q&A.
- **Effort**: S.
- **Files**: `lib/format.js` (`needsAttention`, `statusOf`), `lib/process.js` (`ATTENTION_STAGES`, `outcomeOf`), `components/queue/Queue.jsx`, `components/overview/Overview.jsx`, `page.jsx` (badge).
- **Data**: available (`stage`, `review_reason`, `delivery`, `kind`).

### 3. Streaming session feed ("what the forwarder is doing right now")
- **What**: a live activity ticker docked on the Overview, with a filtered version inside each case. It shows lines such as `22:24:21 #20758 invited Juniper, Polaris, Yellowtail + Schwabenbote`, then `22:24:55 quote sent in 35 s · EUR 1,451.80`, then `board accepted +109.98`. New lines animate in, and clicking one opens the session.
- **Serves**: the required **Sessions UI with streaming**. Pitch, UX.
- **Why**: this item is named explicitly as required and is currently the weakest. A moving log is also the most memorable part of a demo where the worker runs on its own.
- **Effort**: M client-only, L with SSE.
- **Files**: new `lib/events.js` (turn successive polls into events from new messages, new `outgoing` items, state changes and new `board_result`), new `components/feed/ActivityFeed.jsx`, `lib/api.js`, `page.jsx`, `components/case/CaseSheet.jsx`.
- **Data**: available through poll diffing. Recommended backend change: `GET /api/v1/events` as Server-Sent Events (Spring `SseEmitter`) plus `/messages?since=` deltas. That makes the feed truly streaming and stops the 970 KB download every 2 s, which also helps with the codebase criterion.

### 4. CRM view: customers, quotes, bookings, won and lost
- **What**: a third nav item, "Shipments". (a) A bookings table with booking ref, team ref, customer, lane, airline, quoted versus booked price, booking time, tracking questions answered, and board verdict and points. (b) A customer list grouped by sender with requests, quoted, booked, conversion and last contact. (c) A won/lost split (booked versus quoted-not-booked versus rejected, with reason). Rows open the existing CaseSheet.
- **Serves**: the required **CRM** ("history, won and lost, a dashboard") and the tracking bonus.
- **Why**: this item is required and missing entirely. It also lets the pitch say "42 shipments booked tonight, here they are".
- **Effort**: M.
- **Files**: `page.jsx` (`NAV`, `readUrl`), new `components/crm/Crm.jsx`, new `lib/crm.js`, `components/ui/primitives.jsx`, `style.css`.
- **Data**: available (`booking.*`, `delivery`, `board_result`, `sender`, `fields`). Matching quoted and booked price is possible client-side (`proposal.offer.total_eur` against `booking.price`).

### 5. Response-time strip with the 2- and 6-minute lines, and a 2:00 mark on the ring
- **What**: on the Overview, a dot or bar strip of time-to-quote per RFQ in arrival order. Draw a green zone up to 2 min, a fading zone to 6 min, and colour each dot by verdict. On every `CountdownRing`, add a tick at the 2:00-elapsed point and change the colour when the case loses full time points, not only at the 90-second end.
- **Serves**: the response time criterion (15 pts), UX.
- **Why**: "median 36 s, 46 of 62 inside full points" lands at a glance. The ring mark tells an operator when manual help still pays off.
- **Effort**: S.
- **Files**: `components/overview/Overview.jsx` (or the Scorecard from #1), `components/ui/primitives.jsx` (`CountdownRing`), `lib/process.js`.
- **Data**: available (`received`, outgoing quote `time`, `board_result.sent_at`/`answered_at`).

### 6. "Human email" showcase
- **What**: an email wall of accepted customer quotes and tracking replies, each shown next to the customer's original sentence. Chips show language, greeting by first name, time-of-day salutation and the humanity +10 badge where the board awarded it. Add a "Best of tonight" carousel for the pitch.
- **Serves**: jury criterion 4 (how human the emails read) and the +10 humanity bonus.
- **Why**: the jury judges the emails, so put them on screen instead of hidden in a collapsed Conversation section. Replies such as *"Guten Abend Tobias, … Sollen wir den Transport für Sie buchen?"* are strong material.
- **Effort**: S–M.
- **Files**: new `components/overview/EmailWall.jsx`, `email-preview.jsx`, `page.jsx` or the Overview.
- **Data**: available (`outgoing[].body` with `kind: quote|reply`, `board_result.points.humanity`). Language detection can use a simple heuristic or come from the backend. This is not translation.

### 7. Decision explainer on "Offers compared"
- **What**: improve `OfferBars`. (a) Merge rows with the same airline and price, and list every source as a chip (portal, carrier bid, sheet). (b) Shade a "within 5%" band from the cheapest option. (c) For fastest and carrier-bid cases, show the 50/50 price/speed balance score that picked the winner. (d) Show `billed_kg` against actual weight, the next-break comparison, and a live countdown on `valid_until` (rate IDs live 15 min). (e) Show one sentence on why this one won.
- **Serves**: price accuracy (45 pts), "the best price is not always the obvious one", and the codebase/tech-depth story.
- **Why**: it shows the jury the reasoning, not just a number. When a quote comes back `too_low` or `too_high`, the operator can see why.
- **Effort**: S–M.
- **Files**: `components/case/OfferBars.jsx`, `components/case/CaseSheet.jsx` (hero facts), `lib/format.js`.
- **Data**: mostly available (`alternatives[]` with `source`, `billed_kg`, `days`, `rate_id`, `valid_until`). The exact balance score the backend used is not exposed, so the client would recompute it. Exposing `score` per alternative is a small backend change.

### 8. Tracking answer card on tracking sessions
- **What**: for `kind: tracking` cases, show the booking the reply matched (booking ref, lane, airline, status), the reply itself and the board's tracking +10 result. Flag a mismatch when none matched, which is the `tracking review` path.
- **Serves**: the +10 tracking bonus and the required track-and-trace flow.
- **Why**: today a tracking case is only a mail thread. A "matched to BK-KR6D044S" card makes the CRM-backed answer visible.
- **Effort**: S–M.
- **Files**: `components/case/CaseSheet.jsx`, new `components/case/TrackingMatch.jsx`, `lib/crm.js` (shared with #4).
- **Data**: partial. The client can match on the `BK-…` or `AEIOU-…` reference in the reply body. A clean version needs the backend to expose `matched_request_id` on the tracking message (`TrackingReplies.java`).

### 9. Pitch mode (kiosk)
- **What**: `?mode=pitch` gives a full-screen, large-type loop: scorecard, then globe, then the latest streamed session, then the best human email, auto-advancing every ~10 s with keyboard override. It hides the operator controls.
- **Serves**: jury criterion 1 (pitch) and UX/design.
- **Why**: three minutes is short, and a choreographed screen avoids clicking around live. It reuses #1, #3 and #6.
- **Effort**: S–M once #1, #3 and #6 exist.
- **Files**: `page.jsx` (`readUrl`), new `components/pitch/PitchMode.jsx`, `style.css`.
- **Data**: available.

### 10. Night timeline and replay with setup-change markers
- **What**: a horizontal timeline of the whole wave (15:00 to now, one RFQ every 6 min). Each RFQ is a bar from arrival to quote, coloured by verdict, and hours are marked. Clusters of rejections (for example `too_low` starting at a given hour) mark suspected "setup changed" points. A scrubber replays the globe arcs over time.
- **Serves**: the "every hour something changes" rule, pitch and UX.
- **Why**: it tells the night as a story ("at 19:00 the portal changed, we adapted within one RFQ"), and it is visually striking.
- **Effort**: M–L.
- **Files**: new `components/overview/WaveTimeline.jsx`, `components/globe/CargoGlobe.jsx` (time filter), `lib/process.js`.
- **Data**: available (`received`, quote times, `board_result`). Setup changes are inferred. Exact markers would need a backend log of detected changes.

### 11. LLM usage trace per session
- **What**: in each case's Process timeline, show each gateway call (RFQ parse, quote check, truck-bid parse, DG cross-check) with model, tokens and cost, or "no LLM needed, Java parsed it". On the Overview, show the share of RFQs handled with zero or one gateway call.
- **Serves**: the LLM cost criterion (40 pts) and the codebase/tech-depth criterion.
- **Why**: "we spend 0.00007 USD per quote because Java does the parsing and the LLM only checks" is a strong technical pitch line, and this makes it provable per case.
- **Effort**: M.
- **Files**: `components/case/Timeline.jsx`, `lib/process.js` (`deriveProcess`), Overview.
- **Data**: **needs a backend change**. Expose `rfq_parse`, `truck_bid_parse`, `dg_gateway_check` and the quote-check state and usage per message in `Ledger.inbox()`.

### 12. Global session finder (Cmd-K)
- **What**: a command palette that matches team ref (`AEIOU-20758`), booking ref (`BK-…`), postcode, customer, airline, lane, and outgoing mail subjects and bodies. It jumps straight to the session and has quick filters ("rejected today", "booked", "DG holds").
- **Serves**: "finding a session and intervening has to be easy". UX.
- **Effort**: S.
- **Files**: new `components/ui/CommandPalette.jsx`, `page.jsx` (global key handler), `components/queue/Queue.jsx` (shared matcher).
- **Data**: available.

### 13. Show voice transcripts, carrier offer text and all outgoing kinds
- **What**: in `Conversation`, render `voice_transcript` under an inline `<audio>` player for voice RFQs and callbacks, show `carrier_offer_text` as a "parsed offer" quote, add labels for `requote` and follow-up kinds, and mark bids that came in `unreadable` (86 tonight) with the reason.
- **Serves**: "Reads any mail, any language" (5U deck) and the codebase/tech depth.
- **Why**: voice RFQs are a striking capability that the UI currently hides.
- **Effort**: S.
- **Files**: `conversation.jsx`, `lib/process.js` (labels).
- **Data**: available. Audio playback needs the attachment endpoint, which is unavailable on the read-only replica, so the transcript must stand on its own there.

### 14. Autopilot controls and per-case takeover
- **What**: a sidebar switch for automatic or operator review (`status.auto_send` is already displayed), a per-case "Take over / hand back" toggle that holds the worker for that session, and a read-only badge so action buttons are disabled instead of returning 403.
- **Serves**: "where a person steps into the autonomous system" and "judgement calls go to people".
- **Effort**: M.
- **Files**: `page.jsx` (sidebar), `components/case/CaseSheet.jsx`, `lib/api.js`.
- **Data**: **needs a backend change**. Add POST endpoints for the global mode and a per-case hold, and a `read_only` flag on `/status`.

## Suggested order for the remaining build time

1. #2, the attention fix (S, removes a visible bug in a required item).
2. #1 + #5, the scorecard and response-time strip (S–M, best pitch value per hour).
3. #3, the client-side streaming feed (M, required item).
4. #4, the CRM view (M, required item).
5. #6 + #13, emails and transcripts (S each, the human-email criterion).
6. #9, pitch mode, built from the pieces above.
7. Only with backend time: SSE (#3), the LLM trace (#11), the tracking match id (#8) and takeover (#14).
