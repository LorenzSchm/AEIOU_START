"use client";

import { useRef, useSyncExternalStore } from "react";
import { WINDOW_MS } from "./format";

// One shared 1 s ticker. Only components that actually display time subscribe, and the interval
// stops when nobody listens — so the clock never re-renders the whole app (it used to live in Page).
let now = Date.now();
let timer = null;
const listeners = new Set();

function subscribe(listener) {
  listeners.add(listener);
  if (!timer) {
    now = Date.now();
    timer = setInterval(() => {
      now = Date.now();
      listeners.forEach((l) => l());
    }, 1000);
  }
  return () => {
    listeners.delete(listener);
    if (!listeners.size) {
      clearInterval(timer);
      timer = null;
    }
  };
}
const noSubscribe = () => () => {};

/**
 * Current time, re-rendering every second while `live` is true. When not live the value is frozen
 * at the last render, so callers can turn ticking off once nothing on screen changes with time.
 */
export function useNow(live = true) {
  const frozen = useRef(0);
  const value = useSyncExternalStore(
    live ? subscribe : noSubscribe,
    () => (live ? now : frozen.current || (frozen.current = Date.now())),
    () => 0,
  );
  if (live) frozen.current = 0;
  return value || Date.now();
}

/** True while a request is still inside its 6-minute quote window and not yet answered. */
export const inWindow = (m, t = Date.now()) =>
  m.kind === "request" && !m.delivery && Boolean(m.received) && Date.parse(m.received) + WINDOW_MS > t;
