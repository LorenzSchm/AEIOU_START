"use client";

import { useEffect, useRef } from "react";

export function isTypingTarget(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) return false;
  const tag = target.tagName;
  return tag === "INPUT" || tag === "TEXTAREA" || tag === "SELECT" || target.isContentEditable;
}

type Handler = (e: KeyboardEvent) => void;

/**
 * Single-key shortcuts ("j", "k", "/", "Enter"). Ignored while typing and
 * when a modifier is held, so browser shortcuts keep working.
 */
export function useKeys(bindings: Record<string, Handler>, enabled = true): void {
  const ref = useRef(bindings);
  useEffect(() => {
    ref.current = bindings;
  });
  useEffect(() => {
    if (!enabled) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.metaKey || e.ctrlKey || e.altKey || isTypingTarget(e.target)) return;
      const handler = ref.current[e.key];
      if (handler) handler(e);
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [enabled]);
}

/** `g` followed by a key within 1.2 s: vim-style "go to". */
export function useGoSequence(targets: Record<string, () => void>): void {
  const ref = useRef(targets);
  useEffect(() => {
    ref.current = targets;
  });
  useEffect(() => {
    let armedUntil = 0;
    const onKey = (e: KeyboardEvent) => {
      if (e.metaKey || e.ctrlKey || e.altKey || isTypingTarget(e.target)) return;
      if (Date.now() < armedUntil) {
        armedUntil = 0;
        const go = ref.current[e.key];
        if (go) {
          e.preventDefault();
          go();
        }
        return;
      }
      if (e.key === "g") armedUntil = Date.now() + 1200;
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);
}

/** Focus the page's search field (any input marked data-search). */
export function focusSearch(): boolean {
  const el = document.querySelector<HTMLInputElement>("[data-search]");
  if (!el) return false;
  el.focus();
  el.select();
  return true;
}
