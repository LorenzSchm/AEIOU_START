"use client";

import { useEffect, useMemo, useState } from "react";
import { AnimatePresence, motion } from "motion/react";
import Icon from "../ui/Icon";
import { Chip, CountdownRing, RouteBadge, Segmented } from "../ui/primitives";
import ProcessStrip from "../case/ProcessStrip";
import { deriveProcess } from "../../lib/process";
import { clock, kindLabel, needsAttention, statusOf } from "../../lib/format";
import { spring } from "../../lib/motion";

export default function Queue({ messages, selectedId, onSelect, now, scope, setScope }) {
  const [filter, setFilter] = useState("");
  const top = useMemo(() => messages.filter((m) => !m.root || m.root === m.id), [messages]);
  const exceptions = top.filter(needsAttention);
  const requests = top.filter((m) => m.kind === "request");
  const base = scope === "attention" ? exceptions : scope === "requests" ? requests : messages;
  const q = filter.toLowerCase();
  const list = base.filter((m) =>
    !q || (m.subject + " " + m.sender + " " + m.id + " " + (m.body || "") + " " + (m.fields?.origin || "") + " " + (m.fields?.destination || "")).toLowerCase().includes(q),
  );
  // ↑/↓ move through the visible list (Mail-style). Ignored while typing or when an overlay handled the key.
  useEffect(() => {
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
  }, [list, selectedId, onSelect]);

  return (
    <section className="queue glass" aria-label="Queue">
      <div className="queue-head">
        <Segmented
          label="Queue scope"
          value={scope}
          onChange={setScope}
          options={[
            { value: "attention", label: "Attention", count: exceptions.length },
            { value: "requests", label: "Requests", count: requests.length },
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
        <AnimatePresence initial={false}>
          {list.map((m) => (
            <motion.li key={m.id} layout="position" initial={{ opacity: 0, y: 6 }} animate={{ opacity: 1, y: 0 }}
                       exit={{ opacity: 0, transition: { duration: 0.12 } }} transition={spring}>
              <Row m={m} messages={messages} now={now} active={m.id === selectedId} onSelect={onSelect} />
            </motion.li>
          ))}
        </AnimatePresence>
      </ul>
      {!list.length && (
        <div className="empty-state">
          <Icon name={scope === "attention" ? "check" : "mail"} size={28} />
          <strong>{filter ? "No matches" : scope === "attention" ? "Nothing needs you" : "Inbox empty"}</strong>
          <span>{filter ? "Try another search." : scope === "attention" ? "The worker is handling every request." : "Mail appears as it arrives."}</span>
        </div>
      )}
    </section>
  );
}

function Row({ m, messages, now, active, onSelect }) {
  const status = statusOf(m, now);
  const request = m.kind === "request";
  const process = useMemo(() => (request ? deriveProcess(m, messages) : null), [m, messages, request]);
  return (
    <button className={"row" + (active ? " active" : "")} data-id={m.id} aria-current={active || undefined} onClick={() => onSelect(m.id)}>
      {active && <motion.span layoutId="row-highlight" className="row-highlight" transition={spring} />}
      <span className="row-lead">
        {request && m.received ? (
          <CountdownRing received={m.received} now={now} size={30} done={Boolean(m.delivery)} />
        ) : (
          <span className="row-icon"><Icon name={m.kind === "tracking" ? "pin" : "mail"} size={15} /></span>
        )}
      </span>
      <span className="row-main">
        <span className="row-top">
          {request && m.fields?.origin ? (
            <RouteBadge origin={m.fields.origin} destination={m.fields.destination} size="sm" />
          ) : (
            <strong className="row-subject">{m.subject || "(No subject)"}</strong>
          )}
          <Chip tone={status.tone}>{status.label}</Chip>
        </span>
        <span className="row-meta">
          {kindLabel[m.kind] || m.kind} · #{m.id} · {clock(m.received)}
          {request && m.fields?.weight_kg ? " · " + m.fields.weight_kg + " kg" : ""}
        </span>
        {process && <ProcessStrip steps={process.steps} />}
      </span>
    </button>
  );
}
