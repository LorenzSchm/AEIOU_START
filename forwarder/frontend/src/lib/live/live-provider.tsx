"use client";

import { useQueryClient } from "@tanstack/react-query";
import { createContext, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import type { EventItem, SessionSummary } from "@/api/generated/model";
import { getStreamUrl } from "@/api/generated/stream/stream";
import { byPath } from "@/lib/query-keys";
import { applyEvent, applySession, invalidateGlobe, invalidatePaths, invalidationsFor } from "./cache";

export type LiveStatus = "connecting" | "live" | "reconnecting";

interface LiveState {
  status: LiveStatus;
  /** Wall-clock ms of the last message (including pings). */
  lastMessageAt: number | null;
  /** Seconds until the next reconnect attempt, when reconnecting. */
  retryInMs: number | null;
  eventsReceived: number;
}

const LiveContext = createContext<LiveState>({
  status: "connecting",
  lastMessageAt: null,
  retryInMs: null,
  eventsReceived: 0,
});

const STATS_THROTTLE_MS = 5000;
const BACKOFF_BASE_MS = 1000;
const BACKOFF_MAX_MS = 30_000;
// Server pings every 15 s; if nothing arrives for 45 s the socket is dead.
const STALE_AFTER_MS = 45_000;

/**
 * Owns the app's single EventSource on /api/stream and turns SSE messages into
 * TanStack Query cache writes. No component polls or refetches by hand.
 */
export function LiveProvider({ children }: { children: ReactNode }) {
  const qc = useQueryClient();
  const [state, setState] = useState<LiveState>({
    status: "connecting",
    lastMessageAt: null,
    retryInMs: null,
    eventsReceived: 0,
  });
  const statsTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const statsDirty = useRef(false);

  useEffect(() => {
    let source: EventSource | null = null;
    let attempt = 0;
    let retryTimer: ReturnType<typeof setTimeout> | null = null;
    let watchdog: ReturnType<typeof setInterval> | null = null;
    let lastMessage = Date.now();
    let disposed = false;
    let pending = 0;
    let flushTimer: ReturnType<typeof setTimeout> | null = null;

    const touch = () => {
      lastMessage = Date.now();
    };

    // Batch the counter so a burst of events causes one re-render.
    const bump = () => {
      pending++;
      if (flushTimer) return;
      flushTimer = setTimeout(() => {
        const n = pending;
        pending = 0;
        flushTimer = null;
        setState((s) => ({ ...s, eventsReceived: s.eventsReceived + n, lastMessageAt: lastMessage }));
      }, 250);
    };

    const scheduleStats = () => {
      statsDirty.current = true;
      if (statsTimer.current) return;
      const fire = () => {
        if (statsDirty.current) {
          statsDirty.current = false;
          void qc.invalidateQueries({ predicate: byPath("/api/stats") });
          statsTimer.current = setTimeout(fire, STATS_THROTTLE_MS);
        } else {
          statsTimer.current = null;
        }
      };
      fire();
    };

    const connect = () => {
      if (disposed) return;
      source = new EventSource(getStreamUrl(), { withCredentials: true });

      source.onopen = () => {
        const wasReconnect = attempt > 0;
        attempt = 0;
        touch();
        setState((s) => ({ ...s, status: "live", retryInMs: null, lastMessageAt: Date.now() }));
        // Anything could have happened while we were away.
        if (wasReconnect) void qc.invalidateQueries();
      };

      source.addEventListener("session", (e) => {
        touch();
        const session = JSON.parse((e as MessageEvent<string>).data) as SessionSummary;
        applySession(qc, session);
        scheduleStats();
      });

      source.addEventListener("event", (e) => {
        touch();
        const event = JSON.parse((e as MessageEvent<string>).data) as EventItem;
        applyEvent(qc, event);
        invalidatePaths(qc, invalidationsFor(event));
        invalidateGlobe(qc, event);
        scheduleStats();
        bump();
      });

      source.addEventListener("ping", () => {
        touch();
        setState((s) => ({ ...s, lastMessageAt: Date.now() }));
      });

      source.onerror = () => {
        source?.close();
        source = null;
        reconnect();
      };
    };

    const reconnect = () => {
      if (disposed || retryTimer) return;
      const delay = Math.min(BACKOFF_MAX_MS, BACKOFF_BASE_MS * 2 ** attempt) * (0.8 + Math.random() * 0.4);
      attempt++;
      setState((s) => ({ ...s, status: "reconnecting", retryInMs: Math.round(delay) }));
      retryTimer = setTimeout(() => {
        retryTimer = null;
        connect();
      }, delay);
    };

    connect();
    watchdog = setInterval(() => {
      if (source && Date.now() - lastMessage > STALE_AFTER_MS) {
        source.close();
        source = null;
        reconnect();
      }
    }, 5000);

    return () => {
      disposed = true;
      source?.close();
      if (retryTimer) clearTimeout(retryTimer);
      if (watchdog) clearInterval(watchdog);
      if (flushTimer) clearTimeout(flushTimer);
      if (statsTimer.current) clearTimeout(statsTimer.current);
      statsTimer.current = null;
    };
  }, [qc]);

  const value = useMemo(() => state, [state]);
  return <LiveContext.Provider value={value}>{children}</LiveContext.Provider>;
}

export function useLive(): LiveState {
  return useContext(LiveContext);
}
