"use client";

import { useEffect, useRef } from "react";
import { motion, useReducedMotion } from "motion/react";
import { spring, softSpring } from "../../lib/motion";
import "./timeline.css";

// Lucide-style inline icons (24 viewBox, 1.5px stroke).
const PATHS = {
  received: <><rect x="3" y="5" width="18" height="14" rx="2" /><path d="m3 7 9 6 9-6" /></>,
  understood: <><path d="M9 4H7a2 2 0 0 0-2 2v13a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V6a2 2 0 0 0-2-2h-2" /><rect x="9" y="2.5" width="6" height="3" rx="1" /><path d="m9 13 2 2 4-4" /></>,
  clarification: <><path d="M21 12a8 8 0 0 1-11.6 7.1L4 20l1-4.6A8 8 0 1 1 21 12Z" /><path d="M9.8 9.5a2.3 2.3 0 0 1 4.4.8c0 1.5-2.2 2-2.2 2" /><path d="M12 15.5h.01" /></>,
  "bids-requested": <><path d="m22 2-7 20-4-9-9-4 20-7Z" /><path d="M22 2 11 13" /></>,
  "bids-received": <><path d="M22 12h-6l-2 3h-4l-2-3H2" /><path d="M5.5 5.1 2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.5-6.9A2 2 0 0 0 16.7 4H7.3a2 2 0 0 0-1.8 1.1Z" /></>,
  priced: <><path d="M12.6 2.6A2 2 0 0 0 11.2 2H4a2 2 0 0 0-2 2v7.2a2 2 0 0 0 .6 1.4l8.7 8.7a2.4 2.4 0 0 0 3.4 0l6.6-6.6a2.4 2.4 0 0 0 0-3.4Z" /><circle cx="7.5" cy="7.5" r="1.2" /></>,
  quoted: <><path d="M4 20 20 12 4 4l3 8-3 8Z" /><path d="M7 12h13" /></>,
  booked: <><path d="M17.8 19.2 16 11l3.5-3.5C21 6 21.5 4 21 3c-1-.5-3 0-4.5 1.5L13 8 4.8 6.2c-.5-.1-.9.1-1.1.5l-.3.5c-.2.5-.1 1 .3 1.3L9 12l-2 3H4l-1 1 3 2 2 3 1-1v-3l3-2 3.5 5.3c.3.4.8.5 1.3.3l.5-.2c.4-.3.6-.7.5-1.2Z" /></>,
};
const STATUS_GLYPH = {
  done: <path d="m5 12 5 5 9-10" />,
  failed: <><path d="M12 8v5" /><path d="M12 16.5h.01" /></>,
};
const STATUS_WORD = { done: "done", active: "in progress", pending: "pending", failed: "needs attention", skipped: "not needed" };

function Icon({ name, status, delay, reduce, still }) {
  let glyph;
  if (status === "done") {
    // Checkmark draws itself, like a confirmation stroke.
    glyph = (
      <motion.path d="m5 12 5 5 9-10" initial={reduce || still ? false : { pathLength: 0 }} animate={{ pathLength: 1 }}
                   transition={{ duration: 0.36, ease: [0.65, 0, 0.35, 1], delay: delay + 0.08 }} />
    );
  } else if (status === "failed") {
    glyph = STATUS_GLYPH.failed;
  } else {
    glyph = PATHS[name] || <circle cx="12" cy="12" r="4" />;
  }
  return (
    <svg className="tl-svg" viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor"
         strokeWidth={status === "done" ? 2 : 1.5} strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      {glyph}
    </svg>
  );
}

// Per-status entrance for the node when a step changes state.
const NODE_ENTER = {
  done: { initial: { scale: 0.6 }, animate: { scale: 1 } },
  failed: { initial: { scale: 0.85 }, animate: { scale: 1, x: [0, -3, 3, -2, 2, 0] } },
  active: { initial: { scale: 0.85 }, animate: { scale: 1 } },
  pending: { initial: false, animate: { scale: 1 } },
};

const absolute = new Intl.DateTimeFormat(undefined, {
  month: "short", day: "numeric", hour: "2-digit", minute: "2-digit", second: "2-digit", hour12: false,
});

function relative(time, origin) {
  const t = Date.parse(time);
  if (!Number.isFinite(t) || origin == null) return "";
  const sec = Math.max(0, Math.round((t - origin) / 1000));
  if (sec === 0) return "0 s";
  if (sec < 60) return "+" + sec + " s";
  const m = Math.floor(sec / 60);
  if (m < 60) return "+" + m + " m " + String(sec % 60).padStart(2, "0") + " s";
  return "+" + Math.floor(m / 60) + " h " + String(m % 60).padStart(2, "0") + " m";
}

/** Vertical case timeline. Props: { steps, onJump?(key) } */
export default function Timeline({ steps = [], onJump, className = "", intro: playIntro = true }) {
  const reduce = useReducedMotion();
  // First render staggers the whole list; later status changes animate immediately.
  const firstRun = useRef(true);
  useEffect(() => {
    firstRun.current = false;
  }, []);
  const intro = firstRun.current && playIntro;
  const origin = Date.parse(steps.find((s) => s.key === "received")?.time ?? steps[0]?.time);
  const originMs = Number.isFinite(origin) ? origin : null;
  const visible = steps.filter((s) => s.status !== "skipped");
  const skipped = steps.filter((s) => s.status === "skipped");

  // Progressive connector: link i is filled when the step after it has been reached.
  const reached = (s) => s && s.status !== "pending";

  const Tag = onJump ? "button" : "div";
  let n = 0;

  return (
    <div className={"tl " + className}>
      <ol className="tl-list" aria-label="Process timeline">
        {steps.map((s) => {
          if (s.status === "skipped") return null;
          const i = n++;
          const next = visible[i + 1];
          const time = s.time ? relative(s.time, originMs) : "";
          const abs = s.time && Number.isFinite(Date.parse(s.time)) ? absolute.format(new Date(s.time)) : undefined;
          return (
            <motion.li
              key={s.key}
              className={"tl-step tl-" + s.status}
              aria-current={s.status === "active" || s.status === "failed" ? "step" : undefined}
              initial={reduce || !intro ? false : { opacity: 0, y: 6 }}
              animate={{ opacity: 1, y: 0 }}
              transition={reduce ? { duration: 0 } : { ...spring, delay: Math.min(i, 12) * 0.04 }}
            >
              {next && (
                <span className="tl-link" aria-hidden="true">
                  <motion.span
                    className={"tl-link-fill" + (next.status === "failed" ? " tl-link-warn" : "")}
                    initial={reduce || !intro ? false : { scaleY: 0 }}
                    animate={{ scaleY: reached(next) ? 1 : 0 }}
                    transition={reduce ? { duration: 0 } : { ...softSpring, delay: intro ? 0.1 + i * 0.07 : 0 }}
                  />
                </span>
              )}
              <Tag
                className="tl-row"
                {...(onJump ? { type: "button", onClick: () => onJump(s.key) } : {})}
                aria-label={`${s.label}, ${STATUS_WORD[s.status]}${s.detail ? ", " + s.detail : ""}${abs ? ", " + abs : ""}`}
              >
                <motion.span
                  key={s.status}
                  className="tl-node"
                  aria-hidden="true"
                  initial={reduce || !intro && firstRun.current ? false : NODE_ENTER[s.status]?.initial ?? false}
                  animate={reduce ? { scale: 1 } : NODE_ENTER[s.status]?.animate ?? { scale: 1 }}
                  transition={{ ...spring, delay: intro ? Math.min(i, 12) * 0.04 : 0.22, x: { duration: 0.5, delay: intro ? 0.4 : 0.3 } }}
                >
                  <Icon name={s.key} status={s.status} reduce={reduce} still={!intro && firstRun.current} delay={intro ? Math.min(i, 12) * 0.04 : 0.22} />
                </motion.span>
                <span className="tl-body">
                  <span className="tl-head">
                    <span className="tl-label">
                      {s.label}
                      {s.count > 1 && <span className="tl-count">{s.count}</span>}
                    </span>
                    {time && <time className="tl-time" dateTime={s.time} title={abs}>{time}</time>}
                  </span>
                  {s.detail && <span className="tl-detail" title={s.detail}>{s.detail}</span>}
                </span>
              </Tag>
            </motion.li>
          );
        })}
      </ol>
      {skipped.length > 0 && (
        <p className="tl-skipped">
          {skipped.map((s) => (
            <span key={s.key} title={s.detail}>{s.label} · {s.detail && s.detail.startsWith("not") ? s.detail : "not needed"}</span>
          ))}
        </p>
      )}
    </div>
  );
}
