"use client";

import { useEffect, useState } from "react";

/**
 * Optional ambience layer: anonymous OpenSky Network state vectors, shown as faint dots.
 * Free & rate-limited (anonymous: ~100 calls/day for /states/all), so we poll at most every
 * 60 s, back off hard on 429, and never let a failure affect the rest of the page.
 */
const URL = "https://opensky-network.org/api/states/all";
const POLL_MS = 60_000;
const BACKOFF_MS = 5 * 60_000;
const MAX_POINTS = 12_000;

export type OpenSkyStatus = "off" | "loading" | "ok" | "error" | "rate-limited";

export interface OpenSkyTraffic {
  status: OpenSkyStatus;
  /** Flat [lat, lng, lat, lng, ...] for airborne aircraft. */
  positions: Float32Array | null;
  count: number;
  updatedAt: number | null;
}

interface State {
  status: Exclude<OpenSkyStatus, "off">;
  positions: Float32Array | null;
  count: number;
  updatedAt: number | null;
}

const INITIAL: State = { status: "loading", positions: null, count: 0, updatedAt: null };

export function useOpenSky(enabled: boolean): OpenSkyTraffic {
  const [state, setState] = useState<State>(INITIAL);

  useEffect(() => {
    if (!enabled) return;
    const ctrl = new AbortController();
    let timer: ReturnType<typeof setTimeout> | undefined;
    let cancelled = false;

    const poll = async () => {
      let next = POLL_MS;
      try {
        const res = await fetch(URL, { signal: ctrl.signal, cache: "no-store" });
        if (res.status === 429) {
          next = BACKOFF_MS;
          setState((s) => ({ ...s, status: "rate-limited" }));
        } else if (!res.ok) {
          throw new Error(String(res.status));
        } else {
          const json = (await res.json()) as { states?: unknown[][] | null };
          const states = json.states ?? [];
          const buf = new Float32Array(Math.min(states.length, MAX_POINTS) * 2);
          let n = 0;
          for (const st of states) {
            if (n >= MAX_POINTS) break;
            const lon = st[5];
            const lat = st[6];
            const onGround = st[8];
            if (typeof lat !== "number" || typeof lon !== "number" || onGround === true) continue;
            buf[n * 2] = lat;
            buf[n * 2 + 1] = lon;
            n++;
          }
          setState({ status: "ok", positions: buf.subarray(0, n * 2), count: n, updatedAt: Date.now() });
        }
      } catch {
        if (cancelled) return;
        // Network/CORS/5xx: keep the last good snapshot, retry later.
        setState((s) => ({ ...s, status: s.positions ? "ok" : "error" }));
        next = POLL_MS;
      }
      if (!cancelled) timer = setTimeout(poll, next);
    };
    void poll();

    return () => {
      cancelled = true;
      ctrl.abort();
      if (timer) clearTimeout(timer);
    };
  }, [enabled]);

  if (!enabled) return { status: "off", positions: null, count: 0, updatedAt: null };
  return state;
}
