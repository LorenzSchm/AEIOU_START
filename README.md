# AEIOU air export dispatch

5U sends an air export RFQ every six minutes. Each expires six minutes after arrival. The first quote in the customer’s original thread must give one all-in total for the requested cheapest or fastest service. The challenge accepts quotes within 5% of its best price.

## The 16:00 wave

The Spring Boot worker checks pending work every second. Automatic activity starts at **16:00 Europe/Berlin on 21 November 2026**. The browser can be closed. Open the [wave desk](http://127.0.0.1:3000) for the exception queue and select **All mail** to inspect bids, quoted amounts, and deliveries.

1. OpenAI extracts the airports, priority, weight, volume and pickup zone from the active request. Java validates the result and independently converts clear totals from pounds and cubic feet. For text PDFs, XLSX, CSV and text packing lists, Java checks the cargo classification and reconciles the destination, weight and volume with the email. Consistent general-cargo documents can proceed to pricing. The worker asks for corrected or missing documents and checks customer replies automatically. When matching dangerous goods documents arrive and no dangerous goods service is available, it closes the request with a reply.
2. The worker starts with up to five carrier invitations. For door-to-port quotes, it checks the portal first. A bookable rate at the shipment's chargeable weight can free that airline's email slot for an eligible trucker. For cheapest door-to-port quotes, it prioritizes truckers serving the pickup zone. It reserves one airline slot when a carrier has confirmed the exact lane and two when airline coverage comes from the destination directory. If fewer truckers are available, it can invite up to three airlines. For fastest door-to-port quotes, it starts with up to three airline slots and fills the remaining slots with truckers. It compares portal rates at actual weight and the next published chargeable-weight break, alongside all ten [kit tariffs](start-kit/).
3. Replies must come from an invited address and match the outgoing mail ID or RFQ reference. The worker compares confirmed, unexpired EUR bids, converts portal USD offers using their supplied FX, and checks the local weight breaks, minimum, fuel and security. It ranks complete options by the customer’s requested preference.
4. The worker compares bids for up to **32 seconds after the first invitation** by default. A complete portal comparison covers pending email replies from airlines with current bookable rates. An incomplete portal lookup gets time to retry until 95 seconds after arrival. Portal rates observed within the last hour for the same route, carrier and weight bracket help prioritize live lookups, with base rates ahead of buy-ups. Door-to-port requires a confirmed truck price. Fastest also requires the time from collection to airport delivery. If a trucker gives only a lead time or an ambiguous pickup time, the worker asks for road transit and gives preference to confirmed alternatives. For cheapest, a firm price can still be quoted when no trucker confirms transit, with timing shown as pending.
5. The worker asks the original customer to confirm missing shipment details in the RFQ thread. For warehouse pickup it asks for the full five-digit postcode. If the customer supplies only a pickup town that uniquely matches a start-kit zone, the worker requests a firm citywide rate and verifies the trucker's coverage before quoting. When collection remains unpriced, it offers airport delivery as an alternative and switches the service only after the customer explicitly approves it in the same thread. The confirmed postcode or town determines the departure airport when the RFQ leaves it open. The six-minute deadline and a persisted send claim guard each first quote. Unpriced legs and uncertain deliveries appear under **Needs attention**. A lost acknowledgment is held for inspection.

With `DISPATCH_COUNTEROFFERS=true`, the worker can ask for a 5% reduction on one winning email bid per request. It targets the airline when the selected air rate came by email, or the selected trucker when the air rate came from the portal or tariffs. It waits up to 20 seconds for a lower all-in EUR rate with no slower transit, confirmed in the reply format requested in the email. It keeps the original bid when the response is missing or unusable. The worker skips this round when a complete quote arrives well after the initial bid window or when a portal rate would expire too soon. The 5U challenge brief documents initial bids. Carrier support for revised offers is unverified.

Before sending a portal quote, the worker fetches the current rate for the airline, route and weight break. If the price or transit changes during automatic pricing, the worker reranks the options and verifies any replacement portal winner before sending. A manual send requires another comparison when the displayed terms change.

When an RFQ gives a cargo ready date and a required arrival date, the worker ranks only the combinations expected to meet the deadline. It counts calendar days from the later of the cargo ready date and the quote date, rounding fractional transit up to the next day. It checks the deadline again before sending and states the estimated arrival in the quote.

For cheapest door-to-port quotes, the worker holds the draft for **Operator review** when any confirmed pickup bid is at least 2.5 times faster and the chosen bid is at least 40% cheaper. The case shows the lowest-priced alternative that meets both criteria.

In automatic mode, the worker follows up two minutes after an unanswered portal quote if 5U accepted it for scoring and the quoted rate is still valid. It replies once in the original quote thread and skips shipments with a customer reply, an earlier follow-up or a booking.

The worker books portal offers when the original customer explicitly instructs it to book in the quoted thread. Before the first booking attempt it verifies the current route, airline, weight, all-in price and transit against the accepted quote. Changed terms require customer approval of a new quote. Booking retries reuse the submitted rate ID and team reference. If 5U reports an expired rate, the worker verifies current terms again before changing the rate ID. Tracking inquiries from the 5U tracking desk receive a threaded reply when they match one verified CRM booking by reference, thread or route. Unmatched questions require review. SQLite records the gateway attempt and reuses the successful result across polling, manual edits, repricing and restarts. If the final gateway check returns HTTP 429, the worker waits for Retry-After and rechecks current rates and the customer deadline before retrying. Unconfirmed gateway calls require review.

When the original customer asks for an updated offer with revised shipment totals, the worker requests fresh carrier bids and portal rates and replies to that update. It keeps the agreed lane and service, includes the estimated arrival when the ready date and transit are known, and requires explicit approval of the revised quote before booking. Changes to other shipment terms and new attachments go to operator review.

For fastest door-to-port requests, the worker can ask the 5U gateway to interpret a competitive truck bid when Java has verified the price and found possible road timing. The gateway returns separate evidence for the price, collection wait and road transit. Java checks the evidence against the carrier reply and converts the duration. It records one attempt per reply and asks the carrier when usable transit is still missing.

The desk shows the official verdict, reason and available points from the 5U board alongside the RFQ. The worker polls the board once a minute and matches results using the original RFQ timestamp.

When a customer confirms the packing document in the original thread, the worker can apply documented corrections to destination airport, total gross weight and total volume independently. Matching corrections can arrive in one reply or across replies about the same document. Conflicting totals keep the quote on hold.

Before its first dangerous-goods document request, the worker can ask the 5U gateway to cross-check the UN numbers and missing documents. The check is advisory and recorded once per RFQ. Java applies the hold and sends the appropriate document request even if the gateway is unavailable.

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
| `DISPATCH_TARIFF_DIR` | `backend/data`. The quote engine reads the approved `tariffs-YYYY-MM.csv` for the RFQ's receipt month in Berlin on each price calculation. Publish a complete monthly CSV by atomically replacing the file. The packaged November 2026 catalog is the fallback for that month. |
| `DISPATCH_OPENAI_MODEL` | `gpt-4o-mini`. This model handles extraction, interpretation, and customer reply wording. |
| `DISPATCH_LLM_QUOTE_GATEWAY` | `true`. Use the 5U gateway for quote extraction and verification so accepted quotes can earn cost points. |
| `DISPATCH_LLM_DIRECT` | `true`. Use direct OpenAI for other model calls, including multilingual rendering. Set to `false` to route those supported calls through 5U as well. |
| `FIVEU_BASE_URL` | `https://api.start-hack.5u.ai`. Integration tests use an isolated local HTTP fixture and temporary database. |
| `DISPATCH_WEBHOOK_SECRET` | Unset. Set the signing secret for webhook-only mailbox delivery. An installation without a signing secret polls every six seconds and honors the server's retry delay. |
| `DISPATCH_API_URL` | `http://127.0.0.1:8000`. Set before building Next.js to point the desk to another backend. |

| `DATABASE_URL` | Unset. A `postgresql://user:password@host:port/db` URL. The backend mirrors every SQLite table to this Postgres database every 5 seconds. It upserts by primary key, deletes rows removed from SQLite and adds missing tables and columns. It also adds change tracking for read-only desks: a `sync_xid` column stamped by a trigger, a `sync_tombstone` log of deleted keys, and a `sync_meta` table. SQLite stays the worker's source of truth, and a failed sync never blocks the wave. Keep it in the Git-ignored root `.env`. |
| `OPENAI_API_KEY` | Enables direct OpenAI calls for customer reply rendering, booking interpretation, and desk translations. Falls back to the key file `backend/state/openai.key` locally or `/app/state/openai.key` in Docker. Desk translations use the shared `desk_translation` table with a local SQLite fallback. |
| `TRANSLATION_MODEL` | `gpt-4o-mini`. The OpenAI model for operator translations. |
| `DISPATCH_READ_ONLY` | `false`. Use `true` for a desk that only displays the data. It reads the writer's Postgres data every 5 seconds into its own `dispatch.replica.sqlite3`: only rows changed or deleted since the last pull on tracked tables, and on untracked tables (an older writer) a full re-read only when a table's hash changes. It never calls the 5U API, runs no worker jobs and answers every non-GET API request with 403. Attachment downloads need the 5U API and are unavailable there. With `DATABASE_URL` set, writers also share a Postgres advisory lock: only the worker that holds it polls the mailbox, runs the wave, accepts operator actions and mirrors to Postgres. A second writer stays idle until the lock is free. |

The November 2026 catalog uses the original Wayzata rates from the start kit. The operator withdrew the temporary override from mailbox attachment 685 after round 88. The workbook lists a 2025 validity period.

### Carrier rate sheets

The worker scans incoming carrier emails for tariff attachments from airlines in the dispatch directory. Open **Rate sheets** in the desk to inspect the original attachment, validity dates and lane prices, then approve or reject the sheet. XLSX, text PDFs and TXT in supported tariff layouts can be parsed. Other layouts and attachments with missing, reversed or expired dates are blocked with a reason.

Approved rates enter the quote comparison only for the listed carrier and lanes, and only while both the RFQ receipt date and the quote date fall within the sheet's inclusive Berlin validity dates. Future approvals take effect on their start date. Where approved sheets overlap on a lane, the most recently received sheet wins. The monthly CSV supplies lanes without a currently applicable approved sheet.

### This Mac

The current login runs the backend and desk under launchd. The service can recover from a process crash while the Mac stays logged in, awake and online. An idle-sleep assertion covers the first wave through 16:15 Berlin. Keep the lid open and the machine online. The local launch configuration and logs live under `backend/state/`. The backend runs a snapshot of the tested JAR at `backend/state/wave.jar`.

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
| Subscribe a public receiver URL | PUT | `/v1/mailbox/webhook` |
| Verify signed delivery | POST | `/v1/mailbox/webhook/test` |

Expose only `POST /api/v1/mailbox/webhook` through your public proxy or tunnel. Set `DISPATCH_WEBHOOK_SECRET` to the same secret used to subscribe. The receiver verifies the signature and timestamp before saving each message to SQLite for processing. Subscribe before live operation so it receives every new delivery.

## Dokploy worker deployment

Create a Dokploy **Application** for the worker and select the Dockerfile build type.

| Setting | Value |
| --- | --- |
| Git build path | `/` |
| Dockerfile path | `backend/Dockerfile` |
| Docker context path | `.` |
| Docker build stage | `runtime` |
| Container port | `8000` |
| Replicas | `1` |
| Persistent volume mount path | `/app/state` |

Set `DATABASE_URL` to the existing hosted Postgres connection string in Dokploy's runtime environment. Use a direct or session-pooled connection for the worker's session advisory lock. Set `TEAM_API_KEY` and `OPENAI_API_KEY` there as well. For mailbox polling, leave `DISPATCH_WEBHOOK_SECRET` empty. Polling requires no public domain. A signed webhook deployment only needs to route `POST /api/v1/mailbox/webhook` to port 8000.

The PostgreSQL database contains the mirror. The worker still stores its mailbox cursor, sent quotes, and pending work in `/app/state/dispatch.sqlite3`. For a live cutover, stop the current writer, take an SQLite backup, and restore it as `dispatch.sqlite3` in the named volume before starting the Dokploy worker with `AUTO_SEND=true`. The state directory and database must be writable by container UID `1001`. The image defaults to `AUTO_SEND=false`.

Check `GET /api/v1/status` from inside the container after deployment. For a live writer, check `auto_send` and that `last_poll` or `last_webhook` advances.

## VPS deployment with Compose

Compose builds the Spring Boot worker from `backend/Dockerfile` and the Next.js desk from `frontend/Dockerfile`. The desk connects to `http://worker:8000` on the Compose network. On the VPS, the worker and desk listen on loopback ports 8000 and 3000 by default.

To open the VPS desk from a laptop, run `ssh -N -L 3000:127.0.0.1:3000 user@vps` and browse to `http://127.0.0.1:3000`. The browser uses the desk's own `/api/v1` routes. If you expose the desk through a reverse proxy, protect access there.

On the VPS, create a private `.env` with `TEAM_API_KEY` and `OPENAI_API_KEY`. Set `DATABASE_URL` if the worker should continue mirroring to Postgres. Leave `DISPATCH_WEBHOOK_SECRET` empty to poll the mailbox, or keep it configured and point the upstream webhook at the VPS proxy before the cutover.

```sh
docker compose --env-file .env -f backend/docker-compose.yml config --quiet
docker compose --env-file .env -f backend/docker-compose.yml build
```

The named `dispatch-state` volume holds the dispatch database. When moving a live worker, stop the old writer and take an SQLite backup of `backend/state/dispatch.sqlite3`. Transfer the snapshot to the VPS as `dispatch.sqlite3`, then import it into an empty volume before starting the worker. The import refuses to overwrite an existing database.

```sh
docker compose --env-file .env -f backend/docker-compose.yml run --rm --no-deps -T --entrypoint sh worker -eu -c '
  target=/app/state/dispatch.sqlite3
  test ! -e "$target"
  temp=$(mktemp /app/state/dispatch.sqlite3.XXXXXX)
  trap '\''rm -f "$temp"'\'' EXIT
  cat > "$temp"
  test -s "$temp"
  mv "$temp" "$target"
' < dispatch.sqlite3
AUTO_SEND=true docker compose --env-file .env -f backend/docker-compose.yml up -d
curl -fsS http://127.0.0.1:8000/api/v1/status
curl -fsS http://127.0.0.1:3000/api/v1/status
```

Verify `auto_send` and an incoming `last_poll` or `last_webhook` before treating the cutover as complete. Remove `AUTO_SEND=true` to use the Compose default with automatic sends disabled. The standard portal rate API works independently of the optional browser fallback. A browser clearance issued to a different client or IP needs to be provisioned for the VPS if that fallback is used.

## OpenAI

Set `OPENAI_API_KEY` in `.env` or put the key on one line in the Git-ignored `backend/state/openai.key` file. On a fresh checkout, create the file with owner-only permissions (`mkdir -p backend/state && (umask 077 && touch backend/state/openai.key)`). Keep the key available while the worker is running.

The worker defaults to 5U's `POST /v1/gateway/chat/completions` for shipment extraction and quote verification, including separately priced shipments. When the model has confirmed English and Java has prepared an English reply, the worker reuses that reply. Multilingual rendering and other supported interpretation tasks can call OpenAI directly at `POST /v1/chat/completions`. Java checks the verified prices, numbers, airport codes and carrier names before sending. The 5U APIs handle mailbox traffic, rates and bookings.

For 5U cost scoring, leave `DISPATCH_LLM_QUOTE_GATEWAY=true` and register the OpenAI key with 5U from the repo root:

```sh
python3 backend/register-openai-key.py
```

The helper reads `TEAM_API_KEY` from `.env` or the environment and keeps existing registrations. Gateway calls use the team credential. Direct-only requests earn no LLM cost points under the challenge rules. To run all supported quote model calls directly, set `DISPATCH_LLM_QUOTE_GATEWAY=false` and `DISPATCH_LLM_DIRECT=true`.

## Sender details

Both entities list Seidlstraße 26, 80335 München, Germany.

| Entity | Registration | Tax ID | Management |
| --- | --- | --- | --- |
| 5U AI B.V. | Registered office in Rotterdam, Netherlands · KvK 97730726 | VAT DE464837483 | Managing Director Ali Yagizhan Abik |
| 5U AI GmbH | Amtsgericht München HRB 313601 | USt-IdNr. DE464836232 | Geschäftsführer Ali Yagizhan Abik and Fehmi Sener |
