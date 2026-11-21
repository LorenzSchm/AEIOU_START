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
(everything else, the Tika catch-all). Plain text and office are wired; the others answer
"no ... reader yet". Each later reader replaces its own line in `Router.standard()`.

## Office reader (`ai.aeiou.edgelab.office`)

`OfficeReader` runs Apache Tika on Word, Excel, PowerPoint (old and new), OpenOffice, RTF,
HTML, CSV, markdown, text in UTF-8 / Latin-1 / Windows-1252, PDF with a text layer, and the
body of eml/msg mails. Table rows come out one per line, cells joined with ` | `. Numbers keep
English decimals (2.85). Attachments and embedded files are not opened here; a note lists
them. A PDF page with almost no text gets the note `page N looks scanned`, and the Reading is
`partial`; `OfficeReader.extract(...)` returns the pages so the photo reader can render them.
eml/msg are routed to the container slot; until the container walker exists they only read
when the office reader is called directly.

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
