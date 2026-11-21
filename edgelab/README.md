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
(everything else, the Tika catch-all). All six slots are wired in `Router.standard()`.

The video reader samples up to three frames for the photo reader and forwards an available
audio track to the voice reader. Sampled video returns `PARTIAL` because text can appear
between frames. Archives and email use Tika to enumerate parts. Each member goes through
content detection again. A shared budget limits the walk to 32 parts, three container
levels and 32 MiB of member data, with up to 8 MiB per part and 100,000 characters of
output. Check the status and notes for skipped content.

## Office reader (`ai.aeiou.edgelab.office`)

`OfficeReader` runs Apache Tika on Word, Excel, PowerPoint (old and new), OpenOffice, RTF,
HTML, CSV, markdown, text in UTF-8 / Latin-1 / Windows-1252, PDF with a text layer, and the
body of eml/msg mails. Table rows come out one per line, cells joined with ` | `. Numbers keep
English decimals (2.85). Attachments and embedded files are not opened here; a note lists
them. A PDF page with almost no text gets the note `page N looks scanned`, and the Reading is
`partial`; `OfficeReader.extract(...)` returns the pages so the photo reader can render them.
The container reader handles EML and MSG bodies together with their attachments.

## Packing tools

The lightweight `packing-tools` module recognizes a PDF header and recovers complete text
from explicitly headed packing lists in UTF-8, UTF-16 with BOM, and Windows-1252.
It shares the plain-text decoder with the main reader. Run `./gradlew :packing-tools:test`
to test it independently. The module uses only Java 21 at runtime.

## Backend guard

`BackendGuardTest` compares `../backend/` with `origin/feat/bullshitBranch` (changed,
deleted and new files) and fails on any difference. It skips with a message if git or
the ref is not available. Run `git fetch` first if Lorenz's branch moved on.

## Voice reader

`audio/*` (mp3, wav, m4a, ogg, opus, flac, aac, audio-only webm) is decoded to 16 kHz mono
with the ffmpeg bundled in JavaCV, then transcribed locally by Whisper (whisper-jni, which
ships its own native engine). No gateway cost, nothing to install.

- Model: `base-q5_1` (multilingual, ~57 MB). Downloaded once from Hugging Face into
  `~/.cache/edgelab/whisper/`, then works offline. Override with `EDGELAB_WHISPER_MODEL`
  (e.g. `base`, `small-q5_1`) and `EDGELAB_WHISPER_DIR`. `tiny` was too weak for German numbers.
- Language is detected automatically (German and English tested).
- Confidence: whisper-jni does not expose token scores, so it is judged from the speaking
  rate and repetition loops in the transcript (0.85 for normal speech).
- ffmpeg natives are pulled only for macOS arm64 and Windows x64. Intel Mac:
  `./gradlew test -PffmpegPlatforms=macosx-x86_64` (comma-separated list also works).
- Corrupt audio comes back `UNREADABLE` with a note. Over 10 minutes: cut, status `PARTIAL`.

```bash
./gradlew -q read --args="src/test/resources/voice/rfq-en.mp3"
```

Voice fixtures (`src/test/resources/voice/`) were made with Windows' built-in voices
(PowerShell `System.Speech.Synthesis.SpeechSynthesizer`, Zira en-US and Hedda de-DE,
16 kHz mono wav); the mp3s come from those wavs via `MakeVoiceFixtures` (test sources).
Voice tests skip with a message if the model cannot be downloaded.

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

## Gateway client

`ai.aeiou.edgelab.gateway.Gateway` is the only part of edgelab that calls out (besides the
one-time Whisper model download). It talks OpenAI-style to the 5U gateway with
`Authorization: Bearer $TEAM_API_KEY` and never logs the key. Two operations, both never throw:

```java
Gateway gw = HttpGateway.fromSettings();                 // or Gateway.unavailable("...") / a fake in tests
Gateway.Answer a = gw.transcribe(wavBytes, "wav");       // voice fallback
Gateway.Answer b = gw.copyText(jpegBytes, "image/jpeg"); // photos: copy all visible text, low detail
// a.ok(), a.text(), a.selfRating() (0..1 or null), a.model(), a.problem()
```

The key comes from the environment, else from the nearest `.env` (working folder or up to
3 above, or `EDGELAB_ENV_FILE`). Without a key every call answers `TEAM_API_KEY not set`.

| Setting (system property / env var) | Default |
| --- | --- |
| `edgelab.gateway.url` / `EDGELAB_GATEWAY_URL` | `https://api.start-hack.5u.ai/v1/gateway` |
| `edgelab.audio.model` / `EDGELAB_AUDIO_MODEL` | `gpt-4o-mini-audio-preview` |
| `edgelab.audio.method` / `EDGELAB_AUDIO_METHOD` | `chat` (input_audio part) or `transcriptions` (`/audio/transcriptions`, e.g. with `gpt-4o-mini-transcribe`) |
| `edgelab.vision.model` / `EDGELAB_VISION_MODEL` | `gpt-4o-mini` |
| `edgelab.gateway.timeout` / `EDGELAB_GATEWAY_TIMEOUT` | `15` seconds |
| `edgelab.gateway.enabled` / `EDGELAB_GATEWAY_ENABLED` | `true`; the test task sets `false` |

**Voice fallback:** when local Whisper fails, hears nothing, or hears almost nothing (fewer
than 3 words for 3+ s of audio), the voice reader sends the decoded audio (16 kHz wav, max
5 min) to the gateway audio model and notes `gateway fallback used (<model>)`. If that fails
too, the Reading is `UNREADABLE` (or `PARTIAL` with Whisper's few words) with a note such as
`gateway fallback failed: TEAM_API_KEY not set`.

Tests never call the real gateway. The one real test (`RealGatewayTest`, tag `real-gateway`)
runs only with `./gradlew test -PrealGateway --tests '*RealGatewayTest'` and costs points.
`NoOutboundCallsTest` fails if main code mentions mailbox, portal or booking endpoints, or
opens HTTP outside the gateway client and the model download.

## Photo reader (`ai.aeiou.edgelab.photo`, ticket 05)

`image/*` (jpg, png, gif, bmp, tiff, webp, heic/heif) becomes one small JPEG and goes to the
gateway vision model (`gpt-4o-mini`, low detail) with the fixed prompt
`Gateway.COPY_TEXT_PROMPT`: copy all visible text, one table row per line, do not interpret.

- Decoding: ImageIO plus TwelveMonkeys (WebP, TIFF, CMYK JPEG), pure Java. HEIC/HEIF goes
  through the bundled ffmpeg (JavaCV); iPhone tile grids are stitched. First HEIC in a JVM
  takes a few seconds (ffmpeg natives load), then under a second.
- Before sending: EXIF orientation applied, longest edge cut to 1024 px (low detail looks at
  512 px anyway, ~85 tokens per photo), transparency flattened onto white, JPEG q=0.85.
- Confidence: the model's own `CONFIDENCE:` rating (0.6 if it gives none), lowered when fewer
  than ~40 letters/digits come back. Empty answer, broken image or no key: `UNREADABLE` with a
  note, e.g. `photo not read: TEAM_API_KEY not set`.
- Scanned PDFs: `ScanAwareOfficeReader` wraps the office reader. Pages it flags as
  "looks scanned" are rendered at 150 dpi (PDFBox) and read by the photo reader, at most 10
  per file; blank pages are not sent. Their text is merged into the PDF's Reading in page order.
- Fixtures in `src/test/resources/photo/`: the two Danube Wings tariff scans (start kit),
  `example.heic` (libheif's example image), `rfq.webp` (made with `PhotoFixtures.main`). The
  phone photo, EXIF-rotated photo, png/gif/bmp/tiff and the PDFs are drawn in the tests.

```bash
./gradlew -q read --args="../start-kit/danube-wings-cargo-tariff-2026-11-p1.jpg"   # needs TEAM_API_KEY
./gradlew test -PrealGateway --tests '*PhotoRealGatewayTest'                       # real call, costs points
```
