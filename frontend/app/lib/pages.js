"use client";

// Paged rows for the Queue and Shipments tables, served by the backend (GET /queue, GET /shipments/rows).
// Pages refetch when the live data version changes, so they stay current without polling. In mock mode
// the same pages are computed locally from the in-memory messages with the original rules.

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { api, MOCK, shareMessages, shareValue } from "./api";
import { needsAttention } from "./format";
import { crmModel } from "./crm";

export const PAGE_SIZE = 50;
const MAX_PAGE = 200;

/** Waits until a value has been stable for `ms`, so typing in a search box doesn't fetch per keystroke. */
export function useDebounced(value, ms = 250) {
  const [settled, setSettled] = useState(value);
  useEffect(() => {
    const t = setTimeout(() => setSettled(value), ms);
    return () => clearTimeout(t);
  }, [value, ms]);
  return settled;
}

const threadKey = (m) => m.thread_id ?? (m.root != null && m.root !== m.id ? m.root : m.id);
const query = (params) =>
  Object.entries(params).filter(([, v]) => v != null && v !== "").map(([k, v]) => k + "=" + encodeURIComponent(v)).join("&");

/* ---------------- Queue ---------------- */

/** The browser's original queue rules, used in mock mode and as the shape the server mirrors. */
function localQueue(messages, scope, q, before, limit) {
  const top = messages.filter((m) => !m.root || m.root === m.id);
  const attention = top.filter(needsAttention);
  const requests = top.filter((m) => m.kind === "request");
  const base = scope === "attention" ? attention : scope === "requests" ? requests : messages;
  const needle = (q || "").toLowerCase();
  const matching = base.filter((m) =>
    (!needle || (m.subject + " " + m.sender + " " + m.id + " " + (m.body || "") + " " + (m.voice_transcript || "") + " "
      + (m.fields?.origin || "") + " " + (m.fields?.destination || "")).toLowerCase().includes(needle))
    && (before == null || m.id < before));
  const rows = matching.slice(0, limit);
  const threads = {};
  for (const m of rows) threads[threadKey(m)] ??= messages.filter((x) => threadKey(x) === threadKey(m));
  return { messages: rows, threads, counts: { attention: attention.length, requests: requests.length },
    matches: matching.length, next_before: matching.length > limit ? rows.at(-1).id : null };
}

/** Reuse previous thread arrays whose messages are unchanged, so memoised rows skip re-rendering. */
function shareThreads(prev, next) {
  const out = {};
  for (const [k, list] of Object.entries(next)) out[k] = shareMessages(prev?.[k], list);
  return out;
}

/**
 * One growing window over the queue: the first page, plus every page loaded with `loadMore`.
 * Changing scope or search starts over; a new data version refetches the loaded window in one request.
 */
export function useQueuePage({ scope, search, version, messages }) {
  const q = useDebounced(search);
  const [state, setState] = useState({ rows: [], threads: {}, counts: null, nextBefore: null, matches: 0, loaded: false });
  const [error, setError] = useState("");
  const loading = useRef(false);
  const request = useRef(0);
  const windowSize = useRef(PAGE_SIZE);

  const fetchPage = useCallback(async (before, limit) =>
    MOCK ? localQueue(messages || [], scope, q, before, limit)
      : api("/queue?" + query({ scope, q, before, limit })), [scope, q, messages]);

  // Scope or search changed: start again from the first page.
  useEffect(() => { windowSize.current = PAGE_SIZE; }, [scope, q]);

  useEffect(() => {
    const id = ++request.current;
    fetchPage(null, Math.min(MAX_PAGE, windowSize.current)).then((page) => {
      if (id !== request.current) return;
      setState((old) => ({
        rows: shareMessages(old.loaded ? old.rows : null, page.messages),
        threads: shareThreads(old.threads, page.threads),
        counts: shareValue(old.counts, page.counts),
        nextBefore: page.next_before, matches: page.matches, loaded: true,
      }));
      setError("");
    }).catch((e) => id === request.current && setError(e.message));
  }, [fetchPage, version]);

  const loadMore = useCallback(async () => {
    if (loading.current || state.nextBefore == null) return;
    loading.current = true;
    const id = request.current;
    try {
      const page = await fetchPage(state.nextBefore, PAGE_SIZE);
      if (id !== request.current) return;
      windowSize.current += PAGE_SIZE;
      setState((old) => ({
        ...old,
        rows: [...old.rows, ...page.messages.filter((m) => !old.rows.some((r) => r.id === m.id))],
        threads: { ...old.threads, ...shareThreads(old.threads, page.threads) },
        nextBefore: page.next_before,
      }));
    } catch (e) {
      setError(e.message);
    } finally {
      loading.current = false;
    }
  }, [fetchPage, state.nextBefore]);

  return { ...state, error, loadMore, hasMore: state.nextBefore != null };
}

/* ---------------- Shipments ---------------- */

const BOOKING_SORT = {
  ref: (d) => d.booking.booking_reference, customer: (d) => d.customer, lane: (d) => (d.origin || "") + (d.destination || ""),
  carrier: (d) => d.carrier, weight: (d) => d.weightKg, price: (d) => d.bookedAllIn, transit: (d) => d.days,
  booked: (d) => d.bookedAt ?? d.received, status: (d) => (d.board?.booked === false ? 0 : 1),
};
const PIPELINE_SORT = {
  id: (d) => d.id, customer: (d) => d.customer, lane: (d) => (d.origin || "") + (d.destination || ""),
  weight: (d) => d.weightKg, price: (d) => (d.quoted ? d.allIn : null), reason: (d) => d.result.label, received: (d) => d.received,
};

function localShipments(deals, { tab, customer, reason, q, sort, dir }, offset, limit) {
  const needle = (q || "").trim().toLowerCase();
  const value = (tab === "bookings" ? BOOKING_SORT : PIPELINE_SORT)[sort];
  const rows = deals
    .filter((d) => (tab === "bookings" ? d.booking : d.result.group !== "won"))
    .filter((d) => !customer || d.email === customer)
    .filter((d) => tab === "bookings" || !reason || d.result.group + ":" + d.result.key === reason)
    .filter((d) => !needle || [d.customer, d.email, d.origin, d.destination, d.carrier, d.booking?.booking_reference,
      d.booking?.team_reference, "#" + d.id, d.result.label].filter(Boolean).join(" ").toLowerCase().includes(needle))
    .sort((a, b) => {
      const va = value(a), vb = value(b);
      if (va == null && vb == null) return 0;
      if (va == null) return 1;
      if (vb == null) return -1;
      return (typeof va === "string" ? va.localeCompare(vb) : va - vb) * dir;
    });
  return { rows: rows.slice(offset, offset + limit), total: rows.length };
}

/** A growing, server-sorted window over the Bookings or Lost & open table. */
export function useShipmentRows({ tab, customer, reason, search, sort, version, messages }) {
  const q = useDebounced(search);
  const [state, setState] = useState({ rows: [], total: 0, loaded: false });
  const [error, setError] = useState("");
  const request = useRef(0);
  const windowSize = useRef(PAGE_SIZE);
  const localDeals = useMemo(() => (MOCK ? crmModel(messages || []).deals : null), [messages]);
  const params = useMemo(() => ({ tab, customer, reason: tab === "pipeline" ? reason : null, q, sort: sort.key, dir: sort.dir }),
    [tab, customer, reason, q, sort.key, sort.dir]);

  const fetchRows = useCallback(async (offset, limit) =>
    MOCK ? localShipments(localDeals, params, offset, limit)
      : api("/shipments/rows?" + query({ ...params, limit, offset })), [params, localDeals]);

  useEffect(() => { windowSize.current = PAGE_SIZE; }, [params]);

  useEffect(() => {
    const id = ++request.current;
    fetchRows(0, Math.min(MAX_PAGE, windowSize.current)).then((page) => {
      if (id !== request.current) return;
      setState((old) => ({ rows: shareMessages(old.loaded ? old.rows : null, page.rows), total: page.total, loaded: true }));
      setError("");
    }).catch((e) => id === request.current && setError(e.message));
  }, [fetchRows, version]);

  const loadMore = useCallback(async () => {
    const id = request.current;
    try {
      const page = await fetchRows(state.rows.length, PAGE_SIZE);
      if (id !== request.current) return;
      windowSize.current += PAGE_SIZE;
      setState((old) => ({ ...old, rows: [...old.rows, ...page.rows], total: page.total }));
    } catch (e) {
      setError(e.message);
    }
  }, [fetchRows, state.rows.length]);

  return { ...state, error, loadMore, hasMore: state.rows.length < state.total };
}
