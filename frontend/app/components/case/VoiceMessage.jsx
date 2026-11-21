"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { motion, useReducedMotion } from "motion/react";
import Icon from "../ui/Icon";
import { fadeUp } from "../../lib/motion";
import { HIGHLIGHT_LABELS, audioPeaks, decorativePeaks, estimateSeconds, formatDuration, highlightTranscript } from "../../lib/voice";
import "./voice.css";

const BARS = 48;

/**
 * Audio card for a voice-message attachment: play button, waveform, duration and the stored
 * transcript with the values the parser extracted. The recording is fetched only on first play;
 * read-only desks answer attachment downloads with 403, so the card falls back to the transcript.
 */
export default function VoiceMessage({ messageId, file, transcript, fields, kind }) {
  const reduce = useReducedMotion();
  const [status, setStatus] = useState("idle"); // idle | loading | ready | playing | unavailable
  const [duration, setDuration] = useState(null);
  const [position, setPosition] = useState(0);
  const [peaks, setPeaks] = useState(null);
  const audioRef = useRef(null);
  const urlRef = useRef(null);
  const frameRef = useRef(0);
  const decorative = useMemo(() => decorativePeaks(file.id * 31 + (file.size || 0), BARS), [file.id, file.size]);
  const { segments, found } = useMemo(() => highlightTranscript(transcript, fields, kind), [transcript, fields, kind]);
  const estimate = estimateSeconds(transcript);
  const src = `/api/v1/messages/${messageId}/attachments/${file.id}`;

  useEffect(() => () => {
    cancelAnimationFrame(frameRef.current);
    audioRef.current?.pause();
    if (urlRef.current) URL.revokeObjectURL(urlRef.current);
  }, []);

  const tick = useCallback(() => {
    const audio = audioRef.current;
    if (!audio) return;
    setPosition(audio.currentTime);
    if (!audio.paused) frameRef.current = requestAnimationFrame(tick);
  }, []);

  const load = useCallback(async () => {
    setStatus("loading");
    try {
      const res = await fetch(src);
      if (!res.ok) throw new Error(String(res.status));
      const bytes = await res.arrayBuffer();
      const type = file.content_type?.startsWith("audio/") ? file.content_type : "audio/mpeg";
      urlRef.current = URL.createObjectURL(new Blob([bytes], { type }));
      const audio = new Audio(urlRef.current);
      audio.preload = "auto";
      audio.addEventListener("loadedmetadata", () => Number.isFinite(audio.duration) && setDuration(audio.duration));
      audio.addEventListener("play", () => { setStatus("playing"); frameRef.current = requestAnimationFrame(tick); });
      audio.addEventListener("pause", () => { setStatus("ready"); tick(); });
      audio.addEventListener("ended", () => { setStatus("ready"); setPosition(0); audio.currentTime = 0; });
      audio.addEventListener("error", () => setStatus("unavailable"));
      audioRef.current = audio;
      // Real waveform when the browser can decode it; the decorative one stays otherwise.
      const Ctx = window.AudioContext || window.webkitAudioContext;
      if (Ctx) {
        const ctx = new Ctx();
        ctx.decodeAudioData(bytes.slice(0))
          .then((buffer) => { setPeaks(audioPeaks(buffer, BARS)); setDuration((d) => d ?? buffer.duration); })
          .catch(() => {})
          .finally(() => ctx.close?.());
      }
      return audio;
    } catch {
      setStatus("unavailable");
      return null;
    }
  }, [src, file.content_type, tick]);

  const toggle = async () => {
    if (status === "loading" || status === "unavailable") return;
    const audio = audioRef.current ?? (await load());
    if (!audio) return;
    if (audio.paused) audio.play().catch(() => setStatus("unavailable"));
    else audio.pause();
  };

  const seekTo = (fraction) => {
    const audio = audioRef.current;
    if (!audio || !duration) return;
    audio.currentTime = Math.max(0, Math.min(duration, fraction * duration));
    tick();
  };

  const playable = Boolean(audioRef.current) && status !== "unavailable" && Boolean(duration);
  const progress = duration ? position / duration : 0;
  const bars = peaks ?? decorative;
  const playing = status === "playing";
  const timeLabel = duration
    ? (position > 0 || playing ? formatDuration(position) + " / " : "") + formatDuration(duration)
    : estimate ? "~" + formatDuration(estimate) : Math.round((file.size || 0) / 1024) + " KB";

  return (
    <motion.figure className={"vm" + (status === "unavailable" ? " vm-off" : "")}
                   initial={reduce ? false : fadeUp.initial} animate={fadeUp.animate} transition={fadeUp.transition}>
      <div className="vm-player">
        <button type="button" className={"vm-play" + (playing ? " is-playing" : "")} onClick={toggle}
                disabled={status === "unavailable"} aria-busy={status === "loading" || undefined}
                aria-label={status === "unavailable" ? "Recording unavailable" : playing ? "Pause voice message" : "Play voice message"}>
          {status === "loading" ? <span className="vm-spinner" aria-hidden="true" />
            : <Icon name={status === "unavailable" ? "mic-off" : playing ? "pause" : "play"} size={16} />}
        </button>
        <div
          className={"vm-wave" + (peaks ? " is-real" : "") + (playing ? " is-playing" : "")}
          role={playable ? "slider" : "img"}
          tabIndex={playable ? 0 : undefined}
          aria-label={playable ? "Playback position" : "Voice message waveform"}
          aria-valuemin={playable ? 0 : undefined}
          aria-valuemax={playable ? Math.round(duration) : undefined}
          aria-valuenow={playable ? Math.round(position) : undefined}
          aria-valuetext={playable ? `${formatDuration(position)} of ${formatDuration(duration)}` : undefined}
          onPointerDown={(e) => {
            if (!playable) return;
            const box = e.currentTarget.getBoundingClientRect();
            seekTo((e.clientX - box.left) / box.width);
          }}
          onKeyDown={(e) => {
            if (!playable) return;
            const step = e.key === "ArrowRight" ? 5 : e.key === "ArrowLeft" ? -5 : 0;
            if (!step) return;
            e.preventDefault();
            e.stopPropagation();
            seekTo((position + step) / duration);
          }}
        >
          {bars.map((p, i) => (
            <span key={i} className={"vm-bar" + ((i + 0.5) / bars.length <= progress ? " is-played" : "")}
                  style={{ "--h": p, "--i": i }} />
          ))}
        </div>
        <span className="vm-time" title={duration ? "Duration" : estimate ? "Estimated from the transcript" : "File size"}>{timeLabel}</span>
      </div>
      {status === "unavailable" && (
        <p className="vm-note" role="status">The recording can’t be loaded on this desk. The stored transcript is below.</p>
      )}
      <figcaption className="vm-transcript">
        <span className="vm-label"><Icon name="mic" size={12} /> Transcribed</span>
        {transcript ? (
          <p className="vm-text">
            {segments.map((s, i) => s.type
              ? <mark key={i} className={"vm-hl vm-hl-" + s.type} title={HIGHLIGHT_LABELS[s.type]}>{s.text}</mark>
              : <span key={i}>{s.text}</span>)}
          </p>
        ) : (
          <p className="vm-text vm-pending">No transcript recorded yet. The worker transcribes the recording before parsing it.</p>
        )}
        {found.length > 0 && (
          <ul className="vm-found" aria-label="Values the parser read from the recording">
            {found.map((f) => (
              <li key={f.type + f.value} className={"vm-tag vm-hl-" + f.type}>
                <span className="vm-tag-key">{f.label}</span> {f.value}
              </li>
            ))}
          </ul>
        )}
      </figcaption>
    </motion.figure>
  );
}
