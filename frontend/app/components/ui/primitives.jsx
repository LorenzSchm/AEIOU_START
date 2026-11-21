"use client";

import { useId, useState } from "react";
import { AnimatePresence, motion, useReducedMotion } from "motion/react";
import Icon from "./Icon";
import { spring } from "../../lib/motion";
import { WINDOW_MS } from "../../lib/format";

const toneIcon = { ok: "check", warn: "alert", bad: "alert", info: "send", accent: "bolt", muted: null };

export function Chip({ tone = "muted", children, icon = true }) {
  return (
    <span className={"chip chip-" + tone}>
      {icon && toneIcon[tone] && <Icon name={toneIcon[tone]} size={12} />}
      {children}
    </span>
  );
}

/** Progressive disclosure: closed by default, springs open. */
export function Section({ title, icon, summary, defaultOpen = false, children, badge }) {
  const [open, setOpen] = useState(defaultOpen);
  const id = useId();
  const reduce = useReducedMotion();
  return (
    <section className={"section" + (open ? " open" : "")}>
      <button className="section-head" aria-expanded={open} aria-controls={id} onClick={() => setOpen(!open)}>
        {icon && <span className="section-icon"><Icon name={icon} size={16} /></span>}
        <span className="section-title">{title}</span>
        {badge != null && <span className="section-badge">{badge}</span>}
        {!open && summary && <span className="section-summary">{summary}</span>}
        <motion.span className="section-chevron" animate={{ rotate: open ? 90 : 0 }} transition={spring}>
          <Icon name="chevron" size={16} />
        </motion.span>
      </button>
      <AnimatePresence initial={false}>
        {open && (
          <motion.div
            id={id}
            className="section-body-wrap"
            initial={reduce ? false : { height: 0, opacity: 0 }}
            animate={{ height: "auto", opacity: 1 }}
            exit={reduce ? undefined : { height: 0, opacity: 0, transition: { duration: 0.18 } }}
            transition={spring}
          >
            <div className="section-body">{children}</div>
          </motion.div>
        )}
      </AnimatePresence>
    </section>
  );
}

/** iOS-style segmented control with a sliding glass thumb. */
export function Segmented({ options, value, onChange, label }) {
  const group = useId();
  return (
    <div className="segmented" role="tablist" aria-label={label}>
      {options.map((o) => (
        <button key={o.value} role="tab" aria-selected={value === o.value} onClick={() => onChange(o.value)}>
          {value === o.value && <motion.span layoutId={group + "-thumb"} className="segmented-thumb" transition={spring} />}
          <span className="segmented-label">
            {o.label}
            {o.count != null && <b>{o.count}</b>}
          </span>
        </button>
      ))}
    </div>
  );
}

/** Six-minute quote window as a ring. */
export function CountdownRing({ received, now, size = 40, done }) {
  const start = Date.parse(received);
  const left = Math.max(0, start + WINDOW_MS - now);
  const frac = done ? 1 : left / WINDOW_MS;
  const r = size / 2 - 3;
  const c = 2 * Math.PI * r;
  const tone = done ? "var(--ok)" : left <= 0 ? "var(--bad)" : left < 90_000 ? "var(--warn)" : "var(--accent)";
  const secs = Math.ceil(left / 1000);
  const text = done ? "Done" : left <= 0 ? "0:00" : Math.floor(secs / 60) + ":" + String(secs % 60).padStart(2, "0");
  return (
    <span className="ring" style={{ width: size, height: size }} role="timer"
          aria-label={done ? "Quote sent" : left <= 0 ? "Quote window closed" : text + " left in quote window"}>
      <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`}>
        <circle cx={size / 2} cy={size / 2} r={r} stroke="var(--hairline)" strokeWidth="3" fill="none" />
        <circle
          cx={size / 2} cy={size / 2} r={r} stroke={tone} strokeWidth="3" fill="none" strokeLinecap="round"
          strokeDasharray={c} strokeDashoffset={c * (1 - frac)}
          style={{ transition: "stroke-dashoffset 1s linear, stroke var(--dur-2)", transform: "rotate(-90deg)", transformOrigin: "center" }}
        />
      </svg>
      {size >= 44 && <span className="ring-text">{text}</span>}
    </span>
  );
}

export function RouteBadge({ origin, destination, size = "md" }) {
  return (
    <span className={"route route-" + size}>
      <b>{origin || "???"}</b>
      <span className="route-line"><Icon name="plane" size={size === "lg" ? 18 : 13} /></span>
      <b>{destination || "???"}</b>
    </span>
  );
}
