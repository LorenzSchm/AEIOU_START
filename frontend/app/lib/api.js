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

const STREAM_FAILURES_BEFORE_POLLING = 3;

/** Two summaries are the same figures when they differ only in when they were computed. */
const sameSummary = (a, b) => {
  if (!a || !b) return a === b;
  const { computed_at: _a, ...x } = a;
  const { computed_at: _b, ...y } = b;
  return JSON.stringify(x) === JSON.stringify(y);
};

/**
 * A changed row list sent as { $ids, $rows } (InboxFeed.rows): every id in order, plus only the new and
 * changed rows. Unchanged rows keep their objects.
 */
function mergeRows(old, value) {
  if (!value || !Array.isArray(value.$ids)) return value;
  const byId = new Map((Array.isArray(old) ? old : []).map((r) => [r.id, r]));
  for (const r of value.$rows) byId.set(r.id, r);
  return value.$ids.map((id) => byId.get(id));
}

/** Applies a summary event: only changed sections and, within them, only changed entries. */
function mergeSummary(old, patch) {
  if (!old) return patch;
  const next = { ...old };
  for (const [k, v] of Object.entries(patch)) {
    if (!(v && typeof v === "object" && !Array.isArray(v) && old[k] && typeof old[k] === "object")) {
      next[k] = v;
      continue;
    }
    next[k] = { ...old[k] };
    for (const [e, value] of Object.entries(v)) next[k][e] = mergeRows(old[k][e], value);
  }
  return next;
}

/**
 * Live desk data: status, the server summary (figures, attention badge and list, globe routes) and the
 * carrier directory. It holds no messages: views fetch the rows they show (Queue and Shipments pages,
 * the open case's thread) and refetch them when `version` moves. In mock mode it also holds the mock
 * messages, from which those views compute locally.
 *
 * Connects to the backend's event stream (GET /events): one snapshot with status and summary, then
 * compact notices: `changed` (ids of changed messages), `summary` (only the changed parts) and `status`. If the stream fails
 * repeatedly (or in mock mode), it falls back to polling every 2 s. `refresh` re-reads everything at
 * once and makes views refetch, e.g. right after an operator action.
 */
export function useDispatchData() {
  const [data, setData] = useState(null);
  const [error, setError] = useState("");
  const cache = useRef(null);
  const directory = useCallback(async () => {
    if (!cache.current) cache.current = await api("/directory");
    return cache.current;
  }, []);
  // force: an operator action changed data, so views refetch even if the summary looks the same.
  const load = useCallback(async (force) => {
    const [status, messages, dir, summary] = await Promise.all([
      api("/status"), MOCK ? api("/messages") : Promise.resolve(undefined), directory(),
      MOCK ? Promise.resolve(null) : api("/summary").catch(() => null),
    ]);
    setData((old) => {
      const next = {
        status: shareValue(old?.status, status),
        messages: MOCK ? shareMessages(old?.messages, messages) : undefined,
        directory: dir,
        summary: old && sameSummary(old.summary, summary) ? old.summary : summary,
        // The inbox's build stamp moves with any message change, even one the figures don't show.
        stamp: summary?.inbox_stamp,
      };
      const moved = !old || force || next.messages !== old.messages || next.summary !== old.summary || next.stamp !== old.stamp;
      if (!moved && next.status === old.status && next.directory === old.directory) return old;
      // version: bumps when messages or summaries change, so paged views and the case know to refetch.
      next.version = (old?.version ?? 0) + (moved ? 1 : 0);
      return next;
    });
    setError("");
  }, [directory]);
  const refresh = useCallback(() => load(true), [load]);

  useEffect(() => {
    let active = true, timer, source;

    async function tick() {
      try {
        if (active) await load(false);
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
        directory: dir,
        summary: s.summary ?? null,
        version: (old?.version ?? 0) + 1,
        seen: s.version,
      }));
      setError("");
    });
    // Messages or figures changed: views refetch the rows they show. One server tick can send `changed`
    // and `summary` with the same version; views then refetch once.
    const moved = (old, v) => (v === old.seen ? old.version : old.version + 1);
    on("changed", ({ version: v }) => setData((old) => old && { ...old, version: moved(old, v), seen: v }));
    on("status", ({ status }) => setData((old) => old && { ...old, status: shareValue(old.status, status) }));
    on("summary", ({ version: v, summary }) => setData((old) => old && { ...old, summary: mergeSummary(old.summary, summary), version: moved(old, v), seen: v }));
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
  }, [load, directory]);

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
