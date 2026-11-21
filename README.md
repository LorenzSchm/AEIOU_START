# AEIOU air export dispatch

5U sends an air export RFQ every six minutes. Each expires six minutes after arrival. The first quote in the customer’s original thread must give one all-in total for the requested cheapest or fastest service. The challenge accepts quotes within 5% of its best price.

## The 16:00 wave

The Spring Boot worker polls every second. Automatic activity starts at **16:00 Europe/Berlin on 21 November 2026**. The browser can be closed. Open the [wave desk](http://127.0.0.1:3000) for the exception queue and select **All mail** to inspect bids, quoted amounts, and deliveries.

1. Java extracts the airports, priority, weight, volume and pickup zone. Ambiguous RFQs go to the 5U gateway for one structured parse. Java validates the result. For text PDFs, XLSX, CSV and text packing lists, Java checks the cargo classification and reconciles the destination, weight and volume with the email. Consistent general-cargo documents can proceed to pricing. The worker asks for corrected or missing documents and checks customer replies automatically. When matching dangerous goods documents arrive and no dangerous goods service is available, it closes the request with a reply.
2. The worker invites up to five carriers before querying the portal. For door-to-port it invites up to three airlines and fills the remaining slots with truckers serving the pickup zone. It prioritizes airlines whose latest route list before the RFQ includes the exact lane and falls back to the directory for airlines without a route list. It compares portal rates at actual weight and the next published chargeable-weight break, alongside all ten [kit tariffs](start-kit/).
3. Replies must come from an invited address and match the outgoing mail ID or RFQ reference. The worker compares confirmed, unexpired EUR bids, converts portal USD offers using their supplied FX, and checks the local weight breaks, minimum, fuel and security. Among airline email bids it weights price and transit equally. It ranks complete options by the customer’s requested preference.
4. The worker waits until all invited carriers answer or **40 seconds after the last invitation**, whichever comes first, within the six-minute deadline. An incomplete portal lookup gets time to retry until 95 seconds after arrival. Door-to-port requires a confirmed truck price. Fastest also requires the time from collection to airport delivery. If existing bids only give the wait until collection, the worker asks the truckers for transit and can try up to two alternatives. For cheapest, a firm truck price without transit is usable and the email states that pickup timing is pending.
5. The worker asks the original customer to confirm missing shipment details in the RFQ thread. For warehouse pickup it asks for the full five-digit postcode. If the customer supplies only a pickup town that uniquely matches a start-kit zone, the worker requests a firm citywide rate and verifies the trucker's coverage before quoting. When collection remains unpriced, it offers airport delivery as an alternative and switches the service only after the customer explicitly approves it in the same thread. The confirmed postcode or town determines the departure airport when the RFQ leaves it open. The six-minute deadline and a persisted send claim guard each first quote. Unpriced legs and uncertain deliveries appear under **Needs attention**. A lost acknowledgment is held for inspection.

With `DISPATCH_COUNTEROFFERS=true`, the worker can ask for a 5% reduction on one winning email bid per request. It targets the airline when the selected air rate came by email, or the selected trucker when the air rate came from the portal or tariffs. It waits up to 20 seconds for a lower all-in EUR rate with no slower transit, confirmed in the reply format requested in the email. It keeps the original bid when the response is missing or unusable. The worker skips this round when a complete quote arrives well after the initial bid window or when a portal rate would expire too soon. The 5U challenge brief documents initial bids. Carrier support for revised offers is unverified.

The worker books portal offers when the original customer explicitly instructs it to book in the quoted thread. It reuses the same rate ID and team reference for retries. If 5U reports an expired rate, it verifies the refreshed route, airline, price and transit before booking. Tracking inquiries from the 5U tracking desk receive a threaded reply when they match one verified CRM booking by reference, thread or route. Unmatched questions require review. SQLite records the gateway attempt and reuses the successful result across polling, manual edits, repricing and restarts. If the final gateway check returns HTTP 429, the worker waits for Retry-After and rechecks current rates and the customer deadline before retrying. Unconfirmed gateway calls require review.

For fastest door-to-port requests, the worker can ask the 5U gateway to interpret a competitive truck bid when Java has verified the price and found possible road timing. The gateway returns separate evidence for the price, collection wait and road transit. Java checks the evidence against the carrier reply and converts the duration. It records one attempt per reply and asks the carrier when usable transit is still missing.

The desk shows the official verdict, reason and available points from the 5U board alongside the RFQ. The worker polls the board once a minute and matches results using the original RFQ timestamp.

When a customer confirms the packing document in the original thread, the worker can apply documented corrections to destination airport, total gross weight and total volume independently. Matching corrections can arrive in one reply or across replies about the same document. Conflicting totals keep the quote on hold.

## Architecture

| Component | Responsibility | Production reuse |
| --- | --- | --- |
| [Java freight library](backend/core/src/main/java/ai/aeiou/freight/) | RFQ and bid parsing, kit data, chargeable weight, tariff buy-ups, bid ranking and pickup composition | Plain Java 21 library with no Spring or database dependency. Build with `:core:jar` and supply tariff, carrier and zone readers. |
| [Spring Boot service](backend/app/src/main/java/ai/aeiou/dispatch/) | Mail polling, 5U HTTP adapter, six-minute workflow, SQLite cursor, bid correlation, bookings and outbound claims | Run as one process. The service packages the [158 carrier–lane tariffs](backend/data/tariffs-2026-11.csv) and the kit directory. |
| [Next.js desk](frontend/) | Exception queue and operator controls | The production frontend can consume the same `/api/v1` HTTP endpoints through its own authenticated proxy. Credentials stay in the backend. |

The [interactive API](http://127.0.0.1:8000/docs) and [OpenAPI contract](http://127.0.0.1:8000/openapi.json) cover status, directory, messages, attachments and per-message price, resume, quote, bids, reply and book actions.

## Run from a fresh checkout

Use a JDK 21 installation and Node 22 LTS or newer. Put the team key in the repo-root `.env` as `TEAM_API_KEY`. From the repo root:

```sh
./backend/gradlew -p backend test :app:bootJar
java -jar backend/app/build/libs/app-0.1.0.jar
```

In a second terminal:

```sh
cd frontend
npm ci
npm run build
npm start -- --hostname 127.0.0.1 --port 3000
```

| Configuration | Default |
| --- | --- |
| `AUTO_SEND` | `true`. Use `false` to disable the automatic worker. |
| `DISPATCH_COUNTEROFFERS` | `false`. Use `true` to enable the optional counteroffer round. |
| `DISPATCH_START_AT` | `2026-11-21T15:00:00Z` (16:00 Berlin). The worker handles RFQs arriving from this instant onward. |
| `DISPATCH_DB` | `backend/state/dispatch.sqlite3`. Preserve this file across restarts so the worker remembers sent quotes and pending bids. |
| `FIVEU_GATEWAY_MODEL` | `gpt-4o-mini`. The worker sends up to 8,000 characters of email. Structured extraction allows up to 240 completion tokens and the quote check allows up to 24. |
| `FIVEU_BASE_URL` | `https://api.start-hack.5u.ai`. Integration tests use an isolated local HTTP fixture and temporary database. |
| `DISPATCH_API_URL` | `http://127.0.0.1:8000`. Set before building Next.js to point the desk to another backend. |
| `SUPABASE_URL` | Unset. With `SUPABASE_SECRET_KEY`, the backend mirrors the carrier directory and every carrier invitation and bid to Supabase every 5 seconds. Apply [the migration](backend/supabase/migrations/) first. SQLite stays the worker's source of truth, and a failed sync never blocks the wave. |
| `SUPABASE_SECRET_KEY` | Unset. The project's secret (or legacy `service_role`) key. Keep it in the Git-ignored root `.env`. |

### This Mac

The current login runs the backend and desk under launchd. The service can recover from a process crash while the Mac stays logged in, awake and online. An idle-sleep assertion covers the first wave through 16:15 Berlin. Keep the lid open and the machine online. The local launch configuration and logs live in the ignored `backend/state/` directory. The backend runs a snapshot of the tested JAR at `backend/state/wave.jar`.

Check the active worker:

```sh
curl -fsS http://127.0.0.1:8000/api/v1/status
```

To stop automatic operation on this Mac, unload the worker:

```sh
launchctl bootout "gui/$(id -u)/ai.aeiou.dispatch-wave"
```

To re-arm the installed worker:

```sh
launchctl bootstrap "gui/$(id -u)" backend/state/ai.aeiou.dispatch-wave.plist
```

## Team AEIOU kit card

Fehmi at 5U AI sent this card to Lorenz Walter Schmidt. The first request arrives at **16:00**. Before then, the team can read the API docs, try the portal, and download the start kit.

## Team access

| Resource | Details |
| --- | --- |
| Team mailbox | [aeiou@hackloop.mail](mailto:aeiou@hackloop.mail) |
| API base URL | `https://api.start-hack.5u.ai` |
| API documentation | [Read the API docs](https://api.start-hack.5u.ai/docs) |
| Portal | [Open the team portal](https://start-hack.5u.ai/portal/ui) |
| Start kit | [Local files](start-kit/) · [Download ZIP](https://api.start-hack.5u.ai/kit/start-kit.zip) (rate sheets, carrier list, and pickup zones) |
| Leaderboard | [View the leaderboard](https://api.start-hack.5u.ai/board) |

The mailbox, portal, and LLM gateway share one team API key. This checkout stores it as `TEAM_API_KEY` in the Git-ignored `.env` file at the repo root. For API calls, send either `X-API-Key: <team key>` or `Authorization: Bearer <team key>`.

Keep the key within the team. Everything sent with it counts as team AEIOU activity. If the key is lost, visit the 5U booth for a replacement.

## Mail API

| Action | Method | Path relative to the API base URL |
| --- | --- | --- |
| Poll for messages | GET | `/v1/mailbox/messages?since_id=0` |
| Send a message or reply | POST | `/v1/mailbox/messages` |

## LLM gateway

Put your raw OpenAI API key on one line in `backend/state/openai.key`. This local staging file is Git-ignored. On a fresh checkout, create it with owner-only permissions (`mkdir -p backend/state && (umask 077 && touch backend/state/openai.key)`).

From the repo root, register it with 5U and verify the stored-key status:

```sh
python3 backend/register-openai-key.py
```

The helper reads `TEAM_API_KEY` from the root `.env` or the environment. It checks the current registration first. If the team already has a key registered, it leaves it in place. Once 5U confirms registration you can delete the local staging file. The running worker uses only the team credential and picks up registration immediately.

The worker calls `POST /v1/gateway/chat/completions` through the 5U API with a small structured-output model. Ambiguous RFQs use structured extraction. If no gateway call was needed for extraction, the worker checks the proposed shipment against the customer email before sending the quote. This check returns one advisory boolean with a limit of 24 completion tokens. The result is stored across retries and restarts. Java validates shipment and packing details before sending and handles carrier replies, tariff calculations, pickup costs, ranking and customer email.

## Sender details

Both entities list Seidlstraße 26, 80335 München, Germany.

| Entity | Registration | Tax ID | Management |
| --- | --- | --- | --- |
| 5U AI B.V. | Registered office in Rotterdam, Netherlands · KvK 97730726 | VAT DE464837483 | Managing Director Ali Yagizhan Abik |
| 5U AI GmbH | Amtsgericht München HRB 313601 | USt-IdNr. DE464836232 | Geschäftsführer Ali Yagizhan Abik and Fehmi Sener |
