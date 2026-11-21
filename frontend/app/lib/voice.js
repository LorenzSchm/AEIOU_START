// Voice messages: audio attachments the backend transcribes before parsing (mail.voice_transcript).
import { AIRPORTS } from "./airports";

export const isAudio = (file) =>
  Boolean(file) && (/^audio\//i.test(file.content_type || "") || /\.(mp3|m4a|wav|ogg|oga|opus|aac|webm)$/i.test(file.filename || ""));

export const isVoiceMessage = (m) => Boolean(m?.attachments?.some?.(isAudio));

/** "1:05" from seconds. */
export function formatDuration(seconds) {
  if (!Number.isFinite(seconds) || seconds < 0) return "";
  const s = Math.round(seconds);
  return Math.floor(s / 60) + ":" + String(s % 60).padStart(2, "0");
}

/** Rough spoken length (≈2.6 words per second) for a hint before the audio is loaded. */
export const estimateSeconds = (transcript) => {
  const words = (transcript || "").trim().split(/\s+/).filter(Boolean).length;
  return words ? words / 2.6 : null;
};

/**
 * Speech-shaped decorative peaks (0..1), deterministic per seed so a card never reshuffles on
 * re-render. Replaced by the decoded peaks once the recording is playable.
 */
export function decorativePeaks(seed, count) {
  let x = (Number(seed) || 1) * 2654435761 % 4294967296;
  const rand = () => ((x = (x * 1664525 + 1013904223) % 4294967296) / 4294967296);
  return Array.from({ length: count }, (_, i) => {
    const t = i / (count - 1);
    const envelope = Math.min(1, t * 8, (1 - t) * 8);
    const phrase = 0.55 + 0.45 * Math.sin(t * Math.PI * 5.3 + rand() * 0.6);
    return Math.max(0.12, Math.min(1, envelope * (0.25 + 0.75 * phrase * (0.45 + 0.55 * rand()))));
  });
}

/** Peaks (0..1) for `count` bars from decoded audio. */
export function audioPeaks(buffer, count) {
  const data = buffer.getChannelData(0);
  const step = Math.max(1, Math.floor(data.length / count));
  const peaks = [];
  for (let i = 0; i < count; i++) {
    let sum = 0;
    const start = i * step;
    const end = Math.min(data.length, start + step);
    for (let j = start; j < end; j++) sum += data[j] * data[j];
    peaks.push(Math.sqrt(sum / Math.max(1, end - start)));
  }
  const max = Math.max(...peaks, 1e-6);
  return peaks.map((p) => Math.max(0.08, Math.pow(p / max, 0.7)));
}

const escape = (s) => s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
const unaccent = (s) => s.normalize("NFD").replace(/[̀-ͯ]/g, "");
/** Matches the name with or without diacritics ("Düsseldorf" / "Dusseldorf"). */
const loose = (name) =>
  [...name].map((c) => (unaccent(c) !== c ? `(?:${c}|${escape(unaccent(c))})` : escape(c))).join("");

/** 1342.68 → matches "1342.68", "1,342.68", "1.342,68"-style groupings. */
function numberPattern(value) {
  const [int, dec] = String(value).split(".");
  const groups = int.replace(/\B(?=(\d{3})+(?!\d))/g, " ").split(" ");
  const intPart = groups.map(escape).join("[,.\\u202f\\u00a0 ]?");
  return intPart + (dec ? `[.,]${escape(dec)}0*` : "(?:[.,]0+)?");
}

const NUM = "\\d{1,3}(?:[,.]\\d{3})*(?:[.,]\\d+)?|\\d+(?:[.,]\\d+)?";
const MONEY = `(?:€\\s?(?:${NUM})|(?:${NUM})\\s?(?:€|EUR\\b|euros?(?:\\s+and\\s+\\d{1,2}\\s+cents?)?))`;
const PRIORITY = {
  fastest: "fastest(?:\\s+option)?|as fast as possible|quickest|express|urgent(?:ly)?|as soon as possible|asap",
  cheapest: "cheapest(?:\\s+option)?|lowest (?:price|cost|rate)|most economical|economy",
};

export const HIGHLIGHT_LABELS = {
  airport: "Airport",
  weight: "Weight",
  volume: "Volume",
  priority: "Priority",
  pickup: "Pickup",
  price: "Price",
  timing: "Collection time",
};

/**
 * What to mark in a transcript. Request values come from the parser's `fields`; carrier recordings
 * also mark the spoken price and collection time the worker reads as a bid.
 */
function rules(fields = {}, kind) {
  const out = [];
  for (const code of [fields.origin, fields.destination]) {
    if (!code) continue;
    const city = AIRPORTS[code]?.city;
    const names = [code, city, AIRPORTS[code]?.name].filter(Boolean).map((n, i) => (i ? loose(n) : `\\b${escape(n)}\\b`));
    out.push({ type: "airport", value: code, re: `(?:${names.join("|")})(?:\\s+airport)?` });
  }
  if (fields.weight_kg) out.push({ type: "weight", value: fields.weight_kg + " kg", re: `${numberPattern(fields.weight_kg)}\\s*(?:kilos?|kilograms?|kgs?)\\b` });
  else if (kind === "carrier") out.push({ type: "weight", re: `(?:${NUM})\\s*(?:kilos?|kilograms?|kgs?)\\b` });
  if (fields.volume_cbm) out.push({ type: "volume", value: fields.volume_cbm + " m³", re: `${numberPattern(fields.volume_cbm)}\\s*(?:cubic met(?:er|re)s?|cbm|m3|m³)` });
  if (PRIORITY[fields.preference]) out.push({ type: "priority", value: fields.preference, re: PRIORITY[fields.preference] });
  if (fields.pickup_zone) out.push({ type: "pickup", value: "Zone " + fields.pickup_zone, re: `\\b${escape(String(fields.pickup_zone))}\\d{${Math.max(0, 5 - String(fields.pickup_zone).length)}}\\b` });
  else if (kind === "carrier") out.push({ type: "pickup", re: "\\b\\d{5}\\b" });
  if (kind === "carrier") {
    out.push({ type: "price", re: MONEY });
    out.push({ type: "timing", re: "(?:in\\s+)?(?:\\d+|one|two|three|four|five|six|seven|eight|nine|ten|twelve)\\s+hours?(?:\\s+(?:after|from)\\s+your\\s+order)?" });
  }
  return out;
}

/**
 * Splits the transcript into plain and highlighted segments. Overlaps keep the earliest, longest match.
 * Returns { segments: [{text, type?}], found: [{type, label, value}] }.
 */
export function highlightTranscript(text, fields, kind) {
  if (!text) return { segments: [], found: [] };
  const hits = [];
  for (const rule of rules(fields || {}, kind)) {
    const re = new RegExp(rule.re, "giu");
    for (const m of text.matchAll(re)) if (m[0].trim()) hits.push({ start: m.index, end: m.index + m[0].length, rule });
  }
  hits.sort((a, b) => a.start - b.start || b.end - a.end);
  const segments = [];
  const found = new Map();
  let at = 0;
  for (const hit of hits) {
    if (hit.start < at) continue;
    if (hit.start > at) segments.push({ text: text.slice(at, hit.start) });
    const value = text.slice(hit.start, hit.end);
    segments.push({ text: value, type: hit.rule.type });
    const key = hit.rule.type + ":" + (hit.rule.value ?? value.toLowerCase());
    if (!found.has(key)) found.set(key, { type: hit.rule.type, label: HIGHLIGHT_LABELS[hit.rule.type], value: hit.rule.value ?? value });
    at = hit.end;
  }
  if (at < text.length) segments.push({ text: text.slice(at) });
  return { segments, found: [...found.values()] };
}
