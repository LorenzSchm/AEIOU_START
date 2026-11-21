# edgelab

Add-ons for the live bot, kept apart from `backend/` (Lorenz's bot is never touched).
Standalone Java 21 Gradle project with its own wrapper. Package root `ai.aeiou.edgelab`.

## Commands

Run from this folder (`edgelab/`). Java 8 comes first on PATH on Thure's laptop, so point
`JAVA_HOME` at Java 21 for the wrapper (Git Bash):

```bash
export JAVA_HOME="C:/Program Files/Eclipse Adoptium/jdk-21.0.13.10002-hotspot"

# Tests (includes the backend guard)
./gradlew test

# Read one file and print its Reading
./gradlew -q read --args="../start-kit/yellowtail-air-freight-tariff-2026-11.txt"
```

On macOS / Linux with Java 21 as default, plain `./gradlew test` works.

## The reader

One entry point:

```java
Reading r = new EdgeReader().read(filename, bytes);
```

`Reading` fields: `text`, `confidence` (0..1), `detectedType` (MIME, from the bytes, not
the file ending), `status` (`OK` | `PARTIAL` | `UNREADABLE`), `parts` (files found inside,
each `name`, `detectedType`, `status`, `confidence`), `notes` (short reasons).

It never throws for bad input. Empty input, input over 50 MB, unknown types and reader
crashes come back as `UNREADABLE` with a note.

`Router` sends each detected type to one named slot: photo (`image/*`), voice (`audio/*`),
video (`video/*`), container (zip, eml, msg, ...), plain text (`text/plain`), office
(everything else, the Tika catch-all). Today only plain text is wired; the others answer
"no ... reader yet". Each later reader replaces its own line in `Router.standard()`.

## Backend guard

`BackendGuardTest` compares `../backend/` with `origin/feat/bullshitBranch` (changed,
deleted and new files) and fails on any difference. It skips with a message if git or
the ref is not available. Run `git fetch` first if Lorenz's branch moved on.

## Change alarm (Thure's laptop only)

Watches the shared Postgres every 60 s and pops up a Windows tray message when something
new shows up. It needs `DATABASE_URL=postgresql://user:password@host:port/db` in the
repo-root `.env` (or in the environment). It never needs the team key and never calls the 5U API.

```bash
export JAVA_HOME="C:/Program Files/Eclipse Adoptium/jdk-21.0.13.10002-hotspot"
./gradlew -q --console=plain alarm             # runs until Ctrl+C
./gradlew -q alarm --args="--once"             # one poll, then exit
./gradlew -q alarm --args="--replay"           # what today's history would raise (no pop-ups, memory untouched)
```

- **Strictly read-only.** The session starts with `default_transaction_read_only=on`, the
  connection is marked read-only, and only SELECTs run. The alarm refuses to start if the
  server does not confirm read-only. Proof against the real DB (skipped by default):
  `EDGELAB_DB_TEST=1 ./gradlew test --tests '*ReadOnlyDatabaseTest'` (a write is rejected
  with SQLState 25006).
- **First run** (no `alarm-memory.json` yet) learns everything in history as normal and
  sends no alerts. After that each new thing alerts exactly once, also across restarts.
- **What alerts:** a new rejection reason or verdict, a new hold reason in `wave_case`, a
  new attachment type (ending + content type), a mail in an "unreadable" stage with a new
  type or a new sender, a request with no quote (or a mail in `review`) after 4 minutes, a
  new mail kind or sender, a board result with an `adjustment` deduction, a `no answer`
  round or an `expired` request.
- **Output:** a one-line pop-up (at most 3 per poll, then "and N more"), and a full entry in
  `alerts.log`: time, topic, round, example mail id and subject, and which edgelab reader
  fits. `alerts.log` and `alarm-memory.json` are git-ignored. Delete the memory file to
  learn a fresh baseline.
- **Stopping:** Ctrl+C. Memory is written atomically after each poll, so stopping at any
  moment loses nothing.
- The pure part is `ChangeDetector.decide(snapshot, memory)`; it is tested on an anonymised
  snapshot of the real DB from 2026-11-21 (no mail bodies) in `src/test/resources/alarm/`.
