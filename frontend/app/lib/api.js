"use client";

import { useCallback, useEffect, useRef, useState } from "react";

const base = "/api/v1";
const MOCK = process.env.NEXT_PUBLIC_MOCK === "1";

export async function api(path, data) {
  if (MOCK) {
    if (data !== undefined) throw Error("Mock mode is read-only. Start the backend to run actions.");
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

/** Polls status, messages and directory every 2 s. */
export function useDispatchData() {
  const [data, setData] = useState(null);
  const [error, setError] = useState("");
  const cache = useRef(null);
  const refresh = useCallback(async () => {
    const [status, messages, directory] = await Promise.all([
      api("/status"),
      api("/messages"),
      cache.current ? Promise.resolve(cache.current) : api("/directory"),
    ]);
    cache.current = directory;
    setData({ status, messages, directory });
    setError("");
  }, []);
  useEffect(() => {
    let active = true, timer;
    async function tick() {
      try {
        if (active) await refresh();
      } catch (e) {
        if (active) setError(e.message);
      }
      if (active) timer = setTimeout(tick, 2000);
    }
    tick();
    return () => {
      active = false;
      clearTimeout(timer);
    };
  }, [refresh]);
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
