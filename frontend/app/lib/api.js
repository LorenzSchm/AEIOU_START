"use client";

import { useCallback, useEffect, useRef, useState } from "react";

const base = "/api/v1";
export const MOCK = process.env.NEXT_PUBLIC_MOCK === "1";

export async function api(path, data) {
  if (MOCK) {
    if (data !== undefined) throw Error("Mock mode is read-only. Start the backend to run actions.");
    if (path === "/tariff-sheets") return { can_decide: false, sheets: [] };
    const { mockData } = await import("./mock");
    const d = mockData();
    return path === "/status" ? d.status : path === "/messages" ? d.messages : d.directory;
  }
  const r = await fetch(
    base + path,
    data === undefined
      ? { cache: "no-store" }
      : { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(data) },
  );
  if (!r.ok) {
    let detail;
    try {
      detail = (await r.json()).detail;
    } catch {}
    throw Error(
      Array.isArray(detail)
        ? detail.map((e) => e.loc.slice(1).join(".") + " — " + e.msg).join("\n")
        : detail || "Service returned HTTP " + r.status,
    );
  }
  return r.json();
}

/**
 * Structural sharing (like React Query): keep the previous object for every message whose content
 * didn't change, and the previous array when nothing changed at all. Memoised views then skip
 * re-rendering on polls that bring no news, instead of re-rendering ~400 rows every 2 s.
 */
export function shareMessages(prev, next) {
  if (!prev) return next;
  const byId = new Map(prev.map((m) => [m.id, m]));
  let same = prev.length === next.length;
  const out = next.map((m, i) => {
    const old = byId.get(m.id);
    if (old && JSON.stringify(old) === JSON.stringify(m)) {
      if (prev[i] !== old) same = false;
      return old;
    }
    same = false;
    return m;
  });
  return same ? prev : out;
}
export const shareValue = (prev, next) => (prev && JSON.stringify(prev) === JSON.stringify(next) ? prev : next);

/** Replaces or inserts one message, keeping the inbox order (newest id first) and every other object. */
function upsertMessage(messages, message) {
  const i = messages.findIndex((m) => m.id === message.id);
  if (i >= 0) {
    if (JSON.stringify(messages[i]) === JSON.stringify(message)) return messages;
    const next = messages.slice();
    next[i] = message;
    return next;
  }
  const at = messages.findIndex((m) => m.id < message.id);
  const next = messages.slice();
  next.splice(at < 0 ? next.length : at, 0, message);
  return next;
}

const STREAM_FAILURES_BEFORE_POLLING = 3;

/**
 * Live desk data: status, messages and the carrier directory.
 *
 * Connects to the backend's event stream (GET /events): one snapshot, then only the messages and status
 * that change. Unchanged messages keep their objects, so memoised rows don't re-render. If the stream
 * fails repeatedly (or in mock mode), it falls back to polling every 2 s. `refresh` re-reads everything
 * at once, e.g. right after an operator action.
 */
export function useDispatchData() {
  const [data, setData] = useState(null);
  const [error, setError] = useState("");
  const cache = useRef(null);
  const directory = useCallback(async () => {
    if (!cache.current) cache.current = await api("/directory");
    return cache.current;
  }, []);
  const refresh = useCallback(async () => {
    const [status, messages, dir, summary] = await Promise.all([
      api("/status"), api("/messages"), directory(), MOCK ? Promise.resolve(null) : api("/summary").catch(() => null),
    ]);
    setData((old) => {
      const next = {
        status: shareValue(old?.status, status),
        messages: shareMessages(old?.messages, messages),
        directory: dir,
        summary: shareValue(old?.summary, summary),
      };
      if (old && next.status === old.status && next.messages === old.messages && next.directory === old.directory
        && next.summary === old.summary) return old;
      // version: bumps when messages or summaries change, so paged views know to refetch.
      next.version = (old?.version ?? 0) + (old && next.messages === old.messages && next.summary === old.summary ? 0 : 1);
      return next;
    });
    setError("");
  }, [directory]);

  useEffect(() => {
    let active = true, timer, source;

    async function tick() {
      try {
        if (active) await refresh();
      } catch (e) {
        if (active) setError(e.message);
      }
      if (active) timer = setTimeout(tick, 2000);
    }

    if (MOCK || typeof EventSource === "undefined") {
      tick();
      return () => {
        active = false;
        clearTimeout(timer);
      };
    }

    let failures = 0;
    source = new EventSource(base + "/events");
    const on = (name, handle) => source.addEventListener(name, (e) => {
      if (!active) return;
      try {
        handle(JSON.parse(e.data));
      } catch (err) {
        setError("A live update could not be read: " + err.message);
      }
    });
    on("snapshot", async (s) => {
      failures = 0;
      const dir = await directory().catch(() => cache.current);
      if (!active) return;
      setData((old) => ({
        status: shareValue(old?.status, s.status),
        messages: shareMessages(old?.messages, s.messages),
        directory: dir,
        summary: s.summary ?? null,
        version: (old?.version ?? 0) + 1,
      }));
      setError("");
    });
    on("message", ({ message }) => setData((old) => old && { ...old, messages: upsertMessage(old.messages, message), version: old.version + 1 }));
    on("removed", ({ id }) => setData((old) => old && { ...old, messages: old.messages.filter((m) => m.id !== id), version: old.version + 1 }));
    on("status", ({ status }) => setData((old) => old && { ...old, status: shareValue(old.status, status) }));
    on("summary", ({ summary }) => setData((old) => old && { ...old, summary, version: old.version + 1 }));
    source.onerror = () => {
      if (!active) return;
      // EventSource reconnects by itself and gets a fresh snapshot. Only give up after repeated failures.
      if (++failures >= STREAM_FAILURES_BEFORE_POLLING) {
        source.close();
        tick();
      } else {
        setError("Live updates interrupted. Reconnecting…");
      }
    };
    return () => {
      active = false;
      source?.close();
      clearTimeout(timer);
    };
  }, [refresh, directory]);

  return { data, error, refresh };
}

/** One-second clock for countdowns. */
export function useNow(interval = 1000) {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), interval);
    return () => clearInterval(t);
  }, [interval]);
  return now;
}
