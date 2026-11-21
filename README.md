# AEIOU autonomous air freight forwarder

Java 21 and Spring Boot service for air export requests. A persistent worker sources offers, quotes the customer, books accepted shipments and answers tracking questions. The browser application consumes the [frontend contract](docs/forwarder.openapi.json).

## Schnellstart für die Übergabe

Voraussetzung sind Git, ein JDK ab 21 und PowerShell ab 7.1. Beim ersten Build benötigt der Maven Wrapper Internetzugang für die Build-Abhängigkeiten. Der Start funktioniert aus einem frischen Checkout.

```powershell
git clone --single-branch --branch feat/autonomous-air-freight-forwarder https://github.com/LorenzSchm/AEIOU_START.git
cd AEIOU_START
pwsh -File ./Start.ps1
```

Der Standardstart baut die Anwendung und simuliert das Postfach mit einer eigenen Datenbank im Arbeitsspeicher. Unter `http://127.0.0.1:18092/health` meldet sie `DEMO_OFFLINE`. In einem zweiten Fenster zeigt der folgende Befehl die Testsendungen. Mit Strg+C im Startfenster endet die Demo. Falls der Port schon belegt ist, wähle z. B. `pwsh -File ./Start.ps1 -Port 18093`.

```powershell
Invoke-RestMethod 'http://127.0.0.1:18092/api/v1/dashboard' -Headers @{Authorization='Bearer demo'}
```

**Live-Übernahme:** Den bisherigen Worker samt automatischem Neustart zum vereinbarten Zeitpunkt stoppen. Diese Version braucht dessen kompatiblen PostgreSQL-Stand mit Schema V4, Postfachcursor, Versandnachweisen und offenen Aufträgen. Bei einem Wechsel von einem anderen Datenmodell müssen diese Daten zuerst zugeordnet und übernommen werden.

```powershell
pwsh -File ./Start.ps1 -CheckDatabase
```

Die Prüfung liest die übergebene Datenbank und zeigt den gespeicherten Cursor. Der folgende Befehl startet den echten Worker auf Port 18091. Ab dann kann er senden und buchen.

```powershell
$cursor = [long](Read-Host 'Finalen Mail-Cursor des gestoppten Workers eingeben')
pwsh -File ./Start.ps1 -Live -ExpectedCursor $cursor
```

Das Skript liest die PostgreSQL-JDBC-URL (mit dem vereinbarten `currentSchema`), Benutzer und Passwort aus `DATABASE_URL`, `DATABASE_USER` und `DATABASE_PASSWORD`. Für den Live-Start kommen `TEAM_API_KEY`, `GATEWAY_MODEL` und der private `UI_TOKEN` hinzu. Fehlende Werte fragt es im Terminal ab.

## Version 1.1

Version 1.1.3 sends complete airport quotes as soon as the gateway has reviewed the current shipment and all listed portal airlines on the lane have returned rates. A complete pickup itinerary gets a two-second window for other replies. These decisions use the evidence available at dispatch. Slower email bids can still improve on an early quote. The regular 45-second decision (`DECISION_SECONDS`) covers cases outside the fast path. An incomplete itinerary waits for evidence within the existing six-minute deadline. Air and pickup prices use the earlier expiry of both components. The customer objective determines the pickup option for each air bid before the existing carrier-bid scoring rule. One gateway extraction per ordinary RFQ checks missing fields or special cargo constraints. Customer wording uses local templates.

Tracking requests from the organizer's tracking mailbox are matched to one booking using the thread, exact references, or an unambiguous airport pair. Replies stay in the tracking thread and use the booked price, manifest and carrier reports where available.

The optional signed inbox is `POST /hooks/5u/mailbox` in the live profile. Supply the organizer's separately issued `WEBHOOK_SIGNING_SECRET` to accept pushes. Only publish this path if the team agrees to switch the shared subscription. Pushes and polling use the same durable inbox. Only polling advances the catch-up cursor. Set `MAIL_POLL_MS` to change the catch-up interval after verifying delivery. Registering the team URL remains a separate operator action.

## Start the offline demo

From this repository with JDK 21 installed, run `./mvnw spring-boot:run` on Linux or macOS, or the following command in PowerShell.

```powershell
.\mvnw.cmd spring-boot:run
```

The API listens at `http://127.0.0.1:8080`. The local demo uses a file-backed H2 database under `var/`, a public demo bearer value of `demo`, and a simulated clock starting on 21 November 2026. Once Maven has cached the dependencies and plug-ins, add `-o` for an offline build. The packaged JAR runs offline with `java -jar target/forwarder-1.1.3.jar` from the repository root. For a fresh PostgreSQL demo, run `docker compose --profile demo up --build`. Compose uses a persistent database volume.

Five simulated seconds pass per real second by default. In session `s_100`, 100 kg from MUC to LAX costs EUR 304.00 with Yellowtail. The worker issues its first offer at virtual second 90, then processes acceptance, carrier confirmation and a tracking question. Session `s_101` arrives while the first booking awaits confirmation. Its EUR 270 air-only offer lacks pickup. The complete EUR 330 option wins, and the cheaper bid arrives after the quote. Session events also show shadow timing estimates. The demo uses synthetic customers and organizer replies with the actual starter tariffs.

## Frontend handoff

```powershell
$base = 'http://127.0.0.1:8080'
$ui = @{Authorization='Bearer demo'}
Invoke-RestMethod "$base/health"
Invoke-WebRequest "$base/openapi.json" -OutFile "$env:TEMP/forwarder.openapi.json"
Invoke-RestMethod "$base/api/v1/sessions" -Headers $ui
$shipment = Invoke-RestMethod "$base/api/v1/sessions/s_100" -Headers $ui
$shipment.quote
curl.exe -N --max-time 5 -H 'Authorization: Bearer demo' "$base/api/v1/events/stream?after=$($shipment.snapshotCursor)"
Invoke-RestMethod "$base/api/v1/replays" -Method Post -Headers ($ui + @{'Idempotency-Key'='demo-replay-100'}) -ContentType application/json -Body '{"sessionId":"s_100","scenario":"RECORDED"}'
```

Replay becomes available when a quote decision is recorded. The stream stays open until the client disconnects. Its timed curl example exits after five seconds. Use a same-origin server proxy in production. For direct browser development, set `UI_ORIGINS` to comma-separated frontend origins and use authenticated fetch for both snapshots and SSE. On reconnect, send the last applied event ID. Mutation retries reuse the original idempotency key and payload.

## Build and test

```powershell
.\mvnw.cmd verify
```

JUnit covers the ten source tariffs, exact freight arithmetic, scope and bid selection, extraction, controls, replay, recovery after an interrupted mail send, and a full organizer workflow against a local HTTP stub. The Testcontainers test uses PostgreSQL 16 when Docker is available. Optional independent checks for a running demo:

```sh
python -m pip install openapi-spec-validator==0.7.2
python src/test/python/verify_contract.py
node src/test/frontend-smoke.mjs
npx --yes openapi-typescript@7.10.1 docs/forwarder.openapi.json -o target/forwarder.d.ts
npx --yes --package typescript@5.9.3 tsc --noEmit --strict target/forwarder.d.ts
```

The service imports the spreadsheets, PDFs, Word document and text tariff from `start-kit/`. The November Danube transcription is tied to the hashes of both reviewed image pages. The container includes Tesseract for new scans. Set `START_KIT` to use a different input directory. Rates carry source rows or page locations and currency. The bid policy uses equal-weight min-max normalization of cost and transit. The shadow wait policy records forecasts and evaluates a 15-second counterfactual against observed offers. Baseline decisions control dispatch.

## Live deployment

Create the ignored `.env` from `.env.example` for the live deployment. Set `TEAM_API_KEY`, a separate private `UI_TOKEN`, a gateway-supported `GATEWAY_MODEL`, and `DATABASE_PASSWORD`. The live runtime uses the team's existing organizer gateway registration. The deployment operator handles registration through the kit-card instructions below. A live startup checks the mailbox identity, gateway registration, supported model and portal lanes. Starting this profile enables real mailbox sends, bookings and gateway usage.

```sh
docker compose --profile live up --build
```

The Compose profiles use separate PostgreSQL schemas. On the same machine, stop the demo service before binding the live service to port 8080. For an external PostgreSQL installation, supply JDBC `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD` and `SPRING_PROFILES_ACTIVE=live` to the JAR. `GET /health` reports database, worker and upstream status. Use `DECISION_SECONDS` and `RESERVE_SECONDS` to tune the default 90-second decision and 20-second dispatch reserve.

## Verification from this checkout

The Java build passed on JDK 21. Eight JUnit tests passed. The Docker-dependent Testcontainers test was skipped because the Docker engine was stopped. A separate run used native PostgreSQL 16.6, processed both demo shipments, terminated the JVM and restarted it against the same database. The replay idempotency record and the single first quote survived. All 12 API operations were exercised over HTTP and validated against the OpenAPI schemas. Direct authenticated fetch and the same-origin proxy both resumed the SSE stream. The generated TypeScript definitions type-checked.

The organizer's public OpenAPI schema was retrieved read-only. Mailbox, rate, gateway, booking and confirmation exchanges were exercised with a local HTTP organizer stub. Authenticated organizer responses, delivery, paid usage, official scoring and performance of the shadow policy on competition traffic still require a live deployment. The actual number of organizer sends, bookings and paid requests during verification was zero.

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

The card gives a one-time setup command to register the team's own OpenAI API key. Run it from the repo root after replacing `sk-...` with that OpenAI key.

```sh
. ./.env
curl -X POST 'https://api.start-hack.5u.ai/v1/gateway/openai-key' \
  -H "X-API-Key: ${TEAM_API_KEY}" \
  -H 'Content-Type: application/json' \
  -d '{"api_key":"sk-..."}'
```

After registration, configure the OpenAI client with `https://api.start-hack.5u.ai/v1/gateway` as its `base_url` and the team key as its `api_key`.

## Sender details

Both entities list Seidlstraße 26, 80335 München, Germany.

| Entity | Registration | Tax ID | Management |
| --- | --- | --- | --- |
| 5U AI B.V. | Registered office in Rotterdam, Netherlands · KvK 97730726 | VAT DE464837483 | Managing Director Ali Yagizhan Abik |
| 5U AI GmbH | Amtsgericht München HRB 313601 | USt-IdNr. DE464836232 | Geschäftsführer Ali Yagizhan Abik and Fehmi Sener |
