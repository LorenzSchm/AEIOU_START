# forwarder

The digital air forwarder: a Spring Boot backend plus a Next.js control panel, with Postgres, all run through docker compose.

- [ARCHITECTURE.md](ARCHITECTURE.md): design
- [API_CONTRACT.md](API_CONTRACT.md): REST and SSE contract between backend and frontend
- [../BRIEF.md](../BRIEF.md): the challenge

## Run
```sh
cp .env.example .env            # set TEAM_API_KEY and APP_PASSWORD
docker compose up --build       # UI on :3000, backend on :8080 (Swagger at /swagger-ui.html)
```
`POLLER_ENABLED`, `SEND_ENABLED` and `CUSTOMER_REPLIES_ENABLED` are false by default. Only the single live instance sets all three to true; otherwise customers receive duplicate mails.

**Test mode against the real API** (nothing scored is answered): `POLLER_ENABLED=true SEND_ENABLED=true CUSTOMER_REPLIES_ENABLED=false` plus `LLM_BASE_URL=https://api.openai.com/v1` and `LLM_API_KEY=<own key>`, so LLM cost doesn't land on the team's gateway bill. Carriers and the rate desk get real mail; quotes to customers and answers to tracking@ are stored as dry runs and drafts.

## Develop
```sh
docker compose up -d postgres
cd backend && ./gradlew bootRun     # Java 21 toolchain is downloaded automatically
cd frontend && npm run dev          # BACKEND_URL=http://localhost:8080 APP_PASSWORD=dev
cd frontend && npm run gen:api      # regenerate the API client from the running backend
```
