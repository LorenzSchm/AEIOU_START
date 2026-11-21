"use client";

import { useCallback, useSyncExternalStore } from "react";
import { api } from "./api";

/** The desk's reading language. Fixed for now; a setting can replace it later. */
export const APP_LANGUAGE = "en";

const names = new Intl.DisplayNames([APP_LANGUAGE], { type: "language" });
export function languageName(code) {
  try {
    return (code && names.of(code)) || code;
  } catch {
    return code;
  }
}

/** Offer a translation only when detection found a language other than the desk's. */
export const needsTranslation = (language) => Boolean(language) && language !== APP_LANGUAGE;

/**
 * Splits a body into paragraphs and the blank-line separators between them, so the translated
 * paragraphs can be put back with the original spacing: parts alternate text, separator, text…
 */
export function splitParagraphs(body) {
  return (body || "").split(/(\n[ \t]*\n\s*)/);
}

// One shared state per body text, so the inline thread and the full view stay in step and a
// translation is fetched at most once per page session (the backend caches it beyond that).
const states = new Map();
const listeners = new Set();
const idle = { status: "idle", showing: false };

function set(body, next) {
  states.set(body, { ...(states.get(body) || idle), ...next });
  listeners.forEach((listener) => listener());
}

function subscribe(listener) {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

async function request(body) {
  const parts = splitParagraphs(body);
  const texts = parts.filter((_, i) => i % 2 === 0);
  set(body, { status: "loading", error: "" });
  try {
    const result = await api("/translations", { paragraphs: texts, target: APP_LANGUAGE });
    if (result.paragraphs?.length !== texts.length) throw Error("The translation came back incomplete. Try again.");
    set(body, { status: "done", showing: true, source: result.source, paragraphs: result.paragraphs });
  } catch (error) {
    set(body, { status: "error", error: error.message || "Translation failed." });
  }
}

/**
 * Per-body translation state. `parts` holds the paragraphs to render: originals by default, the
 * translation once requested and shown. Each translated part keeps its original for the tooltip.
 */
export function useTranslation(body) {
  const state = useSyncExternalStore(subscribe, () => states.get(body) || idle, () => idle);
  const translate = useCallback(() => {
    const current = states.get(body);
    if (current?.status === "done") set(body, { showing: true });
    else if (current?.status !== "loading") request(body);
  }, [body]);
  const showOriginal = useCallback(() => set(body, { showing: false }), [body]);

  const original = splitParagraphs(body);
  const translated = state.status === "done" && state.showing;
  let t = 0;
  const parts = original.map((text, i) =>
    i % 2 === 1 || !translated
      ? { text }
      : { text: state.paragraphs[t++], original: text, changed: state.paragraphs[t - 1] !== text },
  );
  return {
    ...state,
    translated,
    parts,
    text: parts.map((part) => part.text).join(""),
    translate,
    showOriginal,
  };
}
