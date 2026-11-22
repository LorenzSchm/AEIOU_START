"use client";

import { memo, useEffect, useMemo, useRef, useState } from "react";
import Icon from "../ui/Icon";
import { Chip, CountdownRing, RouteBadge, Segmented } from "../ui/primitives";
import ProcessStrip from "../case/ProcessStrip";
import { deriveProcess } from "../../lib/process";
import { answered, clock, kindLabel, needsAttention, statusOf } from "../../lib/format";
import { inWindow, useNow } from "../../lib/clock";
import { useQueuePage } from "../../lib/pages";
import { isVoiceMessage } from "../../lib/voice";
import "../case/voice.css";

const threadKey = (m) => m.thread_id ?? (m.root != null && m.root !== m.id ? m.root : m.id);

/** Loads the next page when the end of the list scrolls into view. */
function useEndOfList(onEnd, enabled) {
  const ref = useRef(null);
  useEffect(() => {
    const el = ref.current;
    if (!el || !enabled) return;
    const observer = new IntersectionObserver((entries) => entries[0].isIntersecting && onEnd(), {
      root: el.closest(".queue-list"), rootMargin: "400px 0px",
    });
    observer.observe(el);
    return () => observer.disconnect();
  }, [onEnd, enabled]);
  return ref;
}

/** The queue, one server page at a time (GET /queue); it refetches when the live data changes. */
function Queue({ messages, version, selectedId, onSelect, scope, setScope, active = true }) {
  const [filter, setFilter] = useState("");
  const page = useQueuePage({ scope, search: filter, version, messages });
  const list = page.rows;
  const counts = page.counts ?? { attention: 0, requests: 0 };
  const endRef = useEndOfList(page.loadMore, page.hasMore);
  // ↑/↓ move through the visible list (Mail-style). Ignored while typing or when an overlay handled the key.
  useEffect(() => {
    if (!active) return;
    const onKey = (e) => {
      if (e.defaultPrevented || e.metaKey || e.ctrlKey || e.altKey) return;
      if (e.key !== "ArrowDown" && e.key !== "ArrowUp") return;
      const t = e.target;
      if (t instanceof HTMLElement && (t.isContentEditable || /^(INPUT|TEXTAREA|SELECT)$/.test(t.tagName))) return;
      if (t instanceof HTMLElement && t.closest(".sheet")) return; // keep normal scrolling inside the case panel
      if (!list.length) return;
      e.preventDefault();
      const i = list.findIndex((m) => m.id === selectedId);
      const next = i < 0 ? 0 : Math.min(list.length - 1, Math.max(0, i + (e.key === "ArrowDown" ? 1 : -1)));
      if (list[next].id === selectedId) return;
      onSelect(list[next].id, { replace: true });
      requestAnimationFrame(() => {
        const row = document.querySelector(`.queue-list [data-id="${list[next].id}"]`);
        row?.scrollIntoView({ block: "nearest", behavior: "smooth" });
        row?.focus({ preventScroll: true });
      });
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [list, selectedId, onSelect, active]);

  return (
    <section className="queue glass" aria-label="Queue">
      <div className="queue-head">
        <Segmented
          label="Queue scope"
          value={scope}
          onChange={setScope}
          options={[
            { value: "attention", label: "Attention", count: counts.attention },
            { value: "requests", label: "Requests", count: counts.requests },
            { value: "all", label: "All mail" },
          ]}
        />
        <label className="search">
          <Icon name="search" size={15} />
          <input type="search" placeholder="Search route, sender, #id" aria-label="Search messages"
                 value={filter} onChange={(e) => setFilter(e.target.value)} />
        </label>
      </div>
      <ul className="queue-list">
        {/* Plain list items: per-row Motion layout tracking measured all ~400 rows on every render.
            New rows still fade in via CSS (.queue-list > li). */}
        {list.map((m) => (
          <li key={m.id}>
            <Row m={m} thread={page.threads[threadKey(m)]} active={m.id === selectedId} onSelect={onSelect} />
          </li>
        ))}
        {page.hasMore && <li ref={endRef} className="queue-more" aria-hidden="true" />}
      </ul>
      {page.error && <p className="banner banner-bad" role="alert">{page.error}</p>}
      {page.loaded && !list.length && (
        <div className="empty-state">
          <Icon name={scope === "attention" ? "check" : "mail"} size={28} />
          <strong>{filter ? "No matches" : scope === "attention" ? "Nothing needs you" : "Inbox empty"}</strong>
          <span>{filter ? "Try another search." : scope === "attention" ? "The worker is handling every request." : "Mail appears as it arrives."}</span>
        </div>
      )}
    </section>
  );
}

export default memo(Queue);

const Row = memo(function Row({ m, thread, active, onSelect }) {
  // Only rows still inside their quote window tick (their status can flip to "Expired").
  const now = useNow(inWindow(m));
  const status = statusOf(m, now);
  const request = m.kind === "request";
  const process = useMemo(() => (request ? deriveProcess(m, thread) : null), [m, thread, request]);
  const voice = isVoiceMessage(m);
  return (
    <button className={"row" + (active ? " active" : "")} data-id={m.id} aria-current={active || undefined} onClick={() => onSelect(m.id)}>
      <span className="row-lead">
        {request && m.received ? (
          <CountdownRing received={m.received} size={30} done={Boolean(m.delivery) || answered(m)} />
        ) : (
          <span className={"row-icon" + (voice ? " row-icon-voice" : "")}>
            <Icon name={voice ? "mic" : m.kind === "tracking" ? "pin" : "mail"} size={15} title={voice ? "Voice message" : undefined} />
          </span>
        )}
      </span>
      <span className="row-main">
        <span className="row-top">
          {request && m.fields?.origin ? (
            <RouteBadge origin={m.fields.origin} destination={m.fields.destination} size="sm" />
          ) : (
            <strong className="row-subject">{m.subject || "(No subject)"}</strong>
          )}
          <span className="row-chips">
            {voice && request && <span className="row-voice"><Icon name="mic" size={12} title="Voice request" /></span>}
            <Chip tone={status.tone}>{status.label}</Chip>
          </span>
        </span>
        <span className="row-meta">
          {kindLabel[m.kind] || m.kind} · #{m.id} · {clock(m.received)}
          {request && m.fields?.weight_kg ? " · " + m.fields.weight_kg + " kg" : ""}
        </span>
        {process && <ProcessStrip steps={process.steps} />}
      </span>
    </button>
  );
});
