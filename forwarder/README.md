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
`POLLER_ENABLED` and `SEND_ENABLED` are false by default. Only the single live instance may set them to true; otherwise customers receive duplicate mails.

## Develop
```sh
docker compose up -d postgres
cd backend && ./gradlew bootRun     # Java 21 toolchain is downloaded automatically
cd frontend && npm run dev          # BACKEND_URL=http://localhost:8080 APP_PASSWORD=dev
cd frontend && npm run gen:api      # regenerate the API client from the running backend
```
