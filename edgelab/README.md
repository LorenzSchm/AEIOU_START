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
(everything else, the Tika catch-all). A slot without a reader answers "no ... reader yet".
Each reader has its own line in `Router.standard()`.

Every top-level `read` has a **20 s budget** (`EdgeReader.DEFAULT_BUDGET`; tests pass their own
with `new EdgeReader(router, maxBytes, budget)`), shared by all parts inside the file. At the
limit the call returns what it has, status `PARTIAL`, note `timed out after 20 s`.

## Using it from the bot

For Lorenz. Nothing in `backend/` has to be restructured; three small edits, all yours to make.

**1. Dependency (composite build, no publishing).** Both builds use Gradle 9.3.1 and Java 21,
and edgelab's coordinates are `ai.aeiou:edgelab` (group from `build.gradle`, name from
`rootProject.name`). Add one line to `backend/settings.gradle` and one to `backend/app/build.gradle`:

```groovy
// backend/settings.gradle
includeBuild '../edgelab'

// backend/app/build.gradle, in dependencies { }
implementation('ai.aeiou:edgelab') {
    exclude group: 'org.slf4j', module: 'slf4j-nop'   // else it can swallow Spring's logback output
}
```

Gradle swaps in the local `edgelab/` project automatically; `./gradlew bootJar` packs it in.
(Alternative: `./gradlew publishToMavenLocal` would need the `maven-publish` plugin in edgelab,
so the composite build is the simpler route.) Spring's BOM may lift shared libraries
(Jackson, PostgreSQL, PDFBox via Tika); run your tests once after adding it.

**2. The one call: `ShipmentDocuments.read(message)`**, where `PackingSafety.extract(filename,
content)` runs today. Every attachment path goes through it: the quotation-request attachment
(`withAttachedRequest`), trucker-offer attachments (`attachedOffer`, the "bid unreadable"
path), and the packing / DG check (`inspect`, original and follow-ups). Trucker bid *mail
bodies* (`Ledger.parseCarrierBid`) are plain text and need nothing. Safest form: keep your
extractor, use edgelab only when it throws (today that adds `null`):

```java
private static final EdgeReader EDGE = new EdgeReader();   // reuse one; voice reads queue one at a time

String name = Objects.toString(file.get("filename"), "");
byte[] bytes = upstream.bytes(key.messageId(), key.attachmentId());
String text;
try {
  text = PackingSafety.extract(name, bytes);
} catch (IOException | XMLStreamException ex) {
  Reading r = EDGE.read(name, bytes);                      // never throws, max 20 s
  if (r.status() == Reading.Status.UNREADABLE || r.confidence() < 0.5)
    throw new IOException("edgelab: " + String.join("; ", r.notes()));
  text = r.text();
}
doc = new PackingSafety.Document(text);
```

Your `extracted` cache then also stops repeat calls. Note: photos, zips, msg etc. only reach
`read()` where you don't filter by name; `hasRequestAttachment` (only `quotation-request.pdf/
docx/txt`) and `attachedOffer` (`.txt|pdf|docx`) still filter them out until you widen those.
edgelab's tables come out one row per line with cells joined by ` | ` (yours: spaces), so check
your regexes if you ever let edgelab read xlsx first.

**3. Settings** (env var, or the same name as system property, e.g. `-Dedgelab.vision.model=`):

| Setting | Default | What |
| --- | --- | --- |
| `TEAM_API_KEY` | none (env, else nearest `.env` up to 3 folders up) | needed for photos, video frames, scanned PDF pages, voice fallback |
| `EDGELAB_ENV_FILE` | search as above | explicit `.env` path |
| `EDGELAB_GATEWAY_URL` | `https://api.start-hack.5u.ai/v1/gateway` | 5U gateway |
| `EDGELAB_VISION_MODEL` | `gpt-4o-mini` (low detail) | photo text |
| `EDGELAB_AUDIO_MODEL` | `gpt-4o-mini-audio-preview` | voice fallback only |
| `EDGELAB_AUDIO_METHOD` | `chat` (or `transcriptions` with e.g. `gpt-4o-mini-transcribe`) | voice fallback only |
| `EDGELAB_GATEWAY_TIMEOUT` | `15` s per call | |
| `EDGELAB_GATEWAY_ENABLED` | `true` | `false` = no outside calls at all |
| `EDGELAB_WHISPER_MODEL` | `base-q5_1` | local speech model |
| `EDGELAB_WHISPER_DIR` | `~/.cache/edgelab/whisper` | model cache |
| Gradle `ffmpegPlatforms` | `macosx-arm64,windows-x86_64` | Intel Mac: `ffmpegPlatforms=macosx-x86_64` in `~/.gradle/gradle.properties` (applies to the included build too) |

**First run:** the Whisper model (~57 MB) downloads once from Hugging Face. It counts against
the 20 s budget, so the first voice read can come back `PARTIAL`. Pre-load at startup with
`WhisperModelCache.fromSettings().ensure();`.

**Status and confidence.** `OK` = whole file read. `PARTIAL` = some text, something missing
(scanned page, broken part inside a zip, cut audio, 20 s budget hit); `notes` say what.
`UNREADABLE` = no usable text, `text` is `""`. Confidence 0..1: text files ~0.95, Whisper
0.85 for normal speech, photos use the model's own rating (0.6 if none, capped at 0.95).
**Suggested rule:** `OK` and ≥ 0.7 → use as is; `PARTIAL` or 0.5–0.7 → use, but send the mail
to `review`; `UNREADABLE` or < 0.5 → treat like today's failure (`null`, "bid unreadable").

**Cost.** Text, office, PDF with text, and voice (local Whisper): free. Photo: one gateway
call, image shrunk to ≤ 1024 px at low detail, roughly 0.05 US cent at OpenAI list prices
for `gpt-4o-mini` (not yet measured in gateway points). Video: up to 5 frame calls
(duplicates and blanks skipped; a still video costs one). Scanned PDF: one call per scanned
page, max 10. Voice fallback (only when Whisper hears nothing): one audio call, max 5 min.

**Formats.** Text/CSV (UTF-8, Latin-1, Windows-1252), Word, Excel, PowerPoint (old and new),
OpenOffice, RTF, HTML, markdown, PDF (text and scanned); photos jpg, png, gif, bmp, tiff, webp,
heic; voice mp3, wav, m4a, ogg, opus, flac, aac; video mp4, mov, webm, avi, mkv; zip, tar, gz,
7z, rar, eml, msg and files embedded in office files (3 levels, 20 files). Max 50 MB. Every
`read` has a **20 s** budget for the whole file, including everything inside.

**Known gaps / open manual checks.**
- m4a voice files with the index at the end (typical phone voice notes) yield no audio yet;
  a fix is in progress (ticket 02b). Videos are not affected.
- Not yet run with the real gateway: which audio method it accepts (`RealGatewayTest`), photo
  quality on the Danube Wings scans (small digits at low detail), a pptx with a photo, a video's
  "What is shown" part.
- Not yet tried: first run on an Apple-chip Mac (natives + model download), a real rotated
  phone `.mov`, a real trucker-offer docx and round 68's `quotation-request.docx`.

## Office reader (`ai.aeiou.edgelab.office`)

`OfficeReader` runs Apache Tika on Word, Excel, PowerPoint (old and new), OpenOffice, RTF,
HTML, CSV, markdown, text in UTF-8 / Latin-1 / Windows-1252, PDF with a text layer, and the
body of eml/msg mails. Table rows come out one per line, cells joined with ` | `. Numbers keep
English decimals (2.85). Attachments and embedded files are not opened here; a note lists
them. A PDF page with almost no text gets the note `page N looks scanned`, and the Reading is
`partial`; `OfficeReader.extract(...)` returns the pages so the photo reader can render them.
eml/msg are routed to the container slot, which reads the body with the office reader.

## Container walker (`ai.aeiou.edgelab.container`, ticket 07)

Files inside files: zip, tar, gz, 7z, rar, eml and msg (attachments and forwarded mails), and
pictures or objects embedded in Word, PowerPoint, Excel or OpenOffice files. Tika lists the
files inside; each one goes back through `EdgeReader.read`, so a photo in a pptx reaches the
photo reader and a zip in a mail is opened again.

- Each part's text starts with a header naming its path inside the top file:
  `=== part: docs.zip > packing-list.xlsx ===`. A mail's own body (or a slide's own text)
  comes first, without header.
- `parts` lists every part at every depth with its own status and confidence. Notes of a part
  are prefixed with its path (`bad.zip: archive is empty or broken`).
- Limits per top-level read: 3 levels deep, 20 files in total, the 20 s budget. What is left
  out is listed in notes and makes the Reading `PARTIAL`.
- Pictures under 2 KB and emf/wmf vector images are not sent to the gateway (icons, logos);
  a note counts them.
- An encrypted or broken archive is `UNREADABLE` with a note. Office files without embedded
  files are read exactly as before.

```bash
./gradlew -q read --args="src/test/resources/container/shipment-docs.zip"
./gradlew -q read --args="src/test/resources/container/fwd-rfq.eml"
./gradlew -q read --args="src/test/resources/container/rfq-photo.pptx"   # photo part needs TEAM_API_KEY
```

Demo fixtures in `src/test/resources/container/` come from `ContainerFixtures.main` (test sources).

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

- Model: `small-q5_1` on macOS for spoken freight amounts, `base-q5_1` elsewhere. Downloaded once from Hugging Face into
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
  512 px anyway, so the cost is the same), transparency flattened onto white, JPEG q=0.85.
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

## Video reader (`ai.aeiou.edgelab.video`, ticket 06)

`video/*` (mp4, mov, webm, avi, mkv) is split with the bundled ffmpeg (JavaCV), nothing to install:

- **Sound:** the audio track is decoded to 16 kHz mono and handed to the voice reader as a wav
  (local Whisper, gateway fallback as usual). No sound track: note `video has no sound track`.
- **Frames:** at most 5 evenly spread stills. Near-duplicates (a slide held still) and blank
  frames are skipped before the gateway, so a still video costs one photo call. Phone rotation
  flags are applied. Same text on several frames is written once.

The text has two labelled sections, `What is shown:` (frame text) and `What is said:` (speech),
and the Reading has two parts, `video frames` and `sound track`. If one half failed (gateway
error, broken sound) the other half is kept and the status is `partial`. A broken video is
`unreadable` with a note.

```bash
./gradlew -q videoFixtures                                   # writes build/video-fixtures/*.mp4
./gradlew -q read --args="build/video-fixtures/rfq-video.mp4"   # one frame call on the gateway
```

Test videos are generated in memory (`VideoFixtures`: the skewed RFQ photo plus the
Windows-voice RFQ, mpeg4 + aac in mp4, and a silent copy); tests use a fake gateway.
