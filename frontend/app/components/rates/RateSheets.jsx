"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { AnimatePresence, motion, useReducedMotion } from "motion/react";
import { api } from "../../lib/api";
import { fadeUp, stagger } from "../../lib/motion";
import ConfirmDialog from "../ui/ConfirmDialog";
import Icon from "../ui/Icon";
import { RouteBadge, Section } from "../ui/primitives";
import "./rates.css";

/* ---------- Formatting ---------- */

const BERLIN = "Europe/Berlin";
const receivedFmt = new Intl.DateTimeFormat("en-GB", { day: "numeric", month: "short", hour: "2-digit", minute: "2-digit", hour12: false, timeZone: BERLIN });
// Validity dates are calendar days (YYYY-MM-DD), so they are formatted in UTC to avoid a day shift.
const dayFmt = new Intl.DateTimeFormat("en-GB", { day: "numeric", month: "short", year: "numeric", timeZone: "UTC" });
const DAY_MS = 86_400_000;
const day = (iso) => Date.parse(iso + "T00:00:00Z");
const daysBetween = (a, b) => Math.round((day(b) - day(a)) / DAY_MS);
const plural = (n, word) => n + " " + word + (n === 1 ? "" : "s");
const money = (n) => (n == null ? "—" : Number(n).toFixed(2));

function range(from, to) {
  if (!from || !to) return "—";
  try {
    return dayFmt.formatRange(new Date(day(from)), new Date(day(to)));
  } catch {
    return dayFmt.format(new Date(day(from))) + " – " + dayFmt.format(new Date(day(to)));
  }
}

function ago(iso, now) {
  const m = Math.max(0, Math.round((now - Date.parse(iso)) / 60_000));
  if (m < 1) return "just now";
  if (m < 60) return m + " min ago";
  const h = Math.round(m / 60);
  return h < 24 ? h + " h ago" : plural(Math.round(h / 24), "day") + " ago";
}

/** Where today sits inside the validity window: expired, upcoming or current (with days left). */
function validity(sheet, today) {
  const { valid_from: from, valid_to: to } = sheet;
  if (!from || !to) return null;
  const span = Math.max(1, daysBetween(from, to));
  if (to < today) {
    const n = daysBetween(to, today);
    return { phase: "expired", label: n === 0 ? "Expired today" : "Expired " + plural(n, "day") + " ago", progress: 1 };
  }
  if (from > today) {
    const n = daysBetween(today, from);
    return { phase: "upcoming", label: n === 1 ? "Starts tomorrow" : "Starts in " + plural(n, "day"), progress: 0 };
  }
  const left = daysBetween(today, to);
  return {
    phase: left <= 7 ? "ending" : "current",
    label: left === 0 ? "Ends today" : left === 1 ? "Ends tomorrow" : plural(left, "day") + " left",
    progress: Math.min(1, daysBetween(from, today) / span),
  };
}

/* ---------- Status (same rules as the backend's state machine) ---------- */

const EXPIRED = { key: "expired", label: "Expired", tone: "muted", icon: "clock" };

function statusOf(sheet, today) {
  const expired = sheet.valid_to && sheet.valid_to < today;
  switch (sheet.state) {
    case "active": return expired ? EXPIRED : { key: "preferred", label: "Preferred", tone: "ok", icon: "check" };
    case "approved":
      if (sheet.valid_from > today) return { key: "scheduled", label: "Scheduled", tone: "info", icon: "calendar" };
      return expired ? EXPIRED : { key: "approved", label: "Approved", tone: "info", icon: "check" };
    case "paused": return expired ? EXPIRED : { key: "paused", label: "Paused", tone: "muted", icon: "pause" };
    case "pending": return { key: "pending", label: "Awaiting approval", tone: "warn", icon: "alert" };
    case "blocked": return { key: "blocked", label: "Blocked", tone: "bad", icon: "alert" };
    case "rejected": return { key: "rejected", label: "Rejected", tone: "muted", icon: "close" };
    default: return { key: "reading", label: "Reading attachment", tone: "info", icon: "clock" };
  }
}

const DECIDED = { active: "Put in use", approved: "Approved", paused: "Paused", rejected: "Rejected" };

const ACTIONS = {
  approve: {
    label: "Approve", title: "Approve this rate sheet?", style: "primary", tone: "primary",
    message: (s) => s.carrier + " prices become eligible for quotes from " + range(s.valid_from, s.valid_to) + ".",
  },
  reject: {
    label: "Reject", title: "Reject this rate sheet?", style: "destructive", tone: "destructive",
    message: (s) => s.filename + " will be marked rejected. Its prices will never be used, and this can’t be undone.",
  },
  activate: {
    label: "Use now", title: "Use this rate sheet now?", style: "primary", tone: "primary",
    message: (s) => "New quotes will prefer this sheet for the " + s.carrier + " lanes it covers. Any other preferred sheet goes back to approved.",
  },
  pause: {
    label: "Pause", title: "Pause this rate sheet?", style: "primary", tone: "plain",
    message: (s) => "New quotes will use other eligible " + s.carrier + " rates where available. You can restore it later.",
  },
  resume: {
    label: "Restore schedule", title: "Restore this rate sheet’s schedule?", style: "primary", tone: "plain",
    message: (s) => "This sheet becomes eligible again on " + dayFmt.format(new Date(day(s.valid_from))) + ".",
  },
};

function decisions(sheet, today) {
  if (sheet.state === "pending") return ["reject", "approve"];
  if (["blocked", "queued"].includes(sheet.state)) return ["reject"];
  if (!sheet.valid_to || sheet.valid_to < today) return [];
  if (sheet.state === "active") return ["pause"];
  if (sheet.state === "approved") return sheet.valid_from <= today ? ["pause", "activate"] : ["pause"];
  if (sheet.state === "paused") return [sheet.valid_from <= today ? "activate" : "resume"];
  return [];
}

/* ---------- Pieces ---------- */

function useEnter(i) {
  const reduce = useReducedMotion();
  return reduce ? { initial: false } : { initial: fadeUp.initial, animate: fadeUp.animate, transition: stagger(i, 0.05) };
}

function Tile({ i, icon, label, value, sub, tone }) {
  const enter = useEnter(i);
  return (
    <motion.div className={"tile glass rs-tile" + (tone ? " tile-" + tone : "")} animate={fadeUp.animate} {...enter}>
      <span className="tile-label"><Icon name={icon} size={14} />{label}</span>
      <span className="tile-value">{value}</span>
      <span className="tile-sub">{sub}</span>
    </motion.div>
  );
}

function StatusChip({ status }) {
  return (
    <span className={"chip chip-" + status.tone + " rs-status"}>
      <Icon name={status.icon} size={12} />{status.label}
    </span>
  );
}

function Summary({ sheets, today }) {
  const statuses = sheets.map((s) => statusOf(s, today));
  const count = (key) => statuses.filter((s) => s.key === key).length;
  const usable = sheets.filter((s, i) => ["preferred", "approved"].includes(statuses[i].key));
  const lanes = new Set(usable.flatMap((s) => s.rates.map((r) => r.origin + "-" + r.destination)));
  const carriers = new Set(usable.map((s) => s.carrier));
  const pending = count("pending");
  const blocked = count("blocked");
  const rejected = count("rejected");
  const expired = sheets.filter((s) => s.valid_to && s.valid_to < today).length;
  return (
    <div className="tiles rs-tiles" role="group" aria-label="Rate sheet summary">
      <Tile i={0} icon="check" label="Preferred" value={count("preferred")} tone={count("preferred") ? "ok" : undefined}
            sub={count("approved") ? count("approved") + " more approved" : "used first for new quotes"} />
      <Tile i={1} icon="alert" label="Awaiting approval" value={pending} tone={pending ? "warn" : undefined}
            sub={pending ? "review prices before use" : "nothing to review"} />
      <Tile i={2} icon="lock" label="Blocked" value={blocked} tone={blocked ? "bad" : undefined}
            sub={rejected ? rejected + " rejected" : blocked ? "can’t be used as sent" : "none blocked"} />
      <Tile i={3} icon="clock" label="Expired" value={expired}
            sub={expired ? "past their validity" : "all within validity"} />
      <Tile i={4} icon="plane" label="Lanes in use" value={lanes.size}
            sub={carriers.size ? "from " + plural(carriers.size, "carrier") : "no sheet in use"} />
    </div>
  );
}

function ValidityFact({ sheet, today }) {
  const v = validity(sheet, today);
  const reduce = useReducedMotion();
  return (
    <div className="rs-fact rs-fact-validity">
      <dt><Icon name="calendar" size={13} />Valid</dt>
      <dd>
        <span className="rs-fact-main">{range(sheet.valid_from, sheet.valid_to)}</span>
        {v && <span className={"rs-fact-sub rs-phase-" + v.phase}>{v.label}</span>}
        {v && (
          <span className={"rs-period rs-phase-" + v.phase} aria-hidden="true">
            <motion.span className="rs-period-fill" initial={reduce ? false : { scaleX: 0 }}
                         animate={{ scaleX: v.progress }} transition={stagger(3)} />
          </span>
        )}
      </dd>
    </div>
  );
}

function laneSummary(rates) {
  const origins = [...new Set(rates.map((r) => r.origin))].sort();
  const destinations = new Set(rates.map((r) => r.destination));
  const days = rates.map((r) => r.days).filter((d) => d != null);
  const transit = days.length ? (Math.min(...days) === Math.max(...days) ? plural(Math.min(...days), "day") : Math.min(...days) + "–" + Math.max(...days) + " days") : null;
  return [origins.join(", ") + " to " + plural(destinations.size, "destination"), transit].filter(Boolean).join(" · ");
}

function breakLabel(weight, next) {
  return Number(weight) === 0 ? (next ? "Under " + next + " kg" : "Any weight") : weight + " kg+";
}

function RateTable({ rates, carrier, id }) {
  const breaks = [...new Set(rates.flatMap((r) => Object.keys(r.breaks)))].sort((a, b) => a - b);
  const rows = [...rates].sort((a, b) => (a.origin + a.destination).localeCompare(b.origin + b.destination));
  return (
    <>
    <div className="rs-table-wrap" tabIndex={0} role="region" aria-label={carrier + " lane prices"} aria-describedby={id + "-note"}>
      <table className="rs-table">
        <thead>
          <tr>
            <th scope="col" className="rs-lane">Lane</th>
            <th scope="col" className="end">Transit</th>
            <th scope="col" className="end">Minimum</th>
            {breaks.map((w, i) => <th scope="col" className="end" key={w}>{breakLabel(w, breaks[i + 1])}</th>)}
            <th scope="col" className="end">Fuel</th>
            <th scope="col" className="end">Security</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => (
            <tr key={r.origin + r.destination}>
              <th scope="row" className="rs-lane">
                <RouteBadge origin={r.origin} destination={r.destination} size="sm" />
              </th>
              <td className="end">{r.days == null ? "—" : plural(r.days, "day")}</td>
              <td className="end mono">{money(r.minimum)}</td>
              {breaks.map((w) => <td className="end mono" key={w}>{money(r.breaks[w])}</td>)}
              <td className="end mono">{money(r.fuel)}</td>
              <td className="end mono">{money(r.security)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
    <p className="rs-table-note" id={id + "-note"}>Prices in EUR. Weight breaks, fuel and security per kg; minimum per shipment.</p>
    </>
  );
}

function SheetCard({ i, sheet, today, canDecide, saving, now, onDecide }) {
  const status = statusOf(sheet, today);
  const options = decisions(sheet, today);
  const enter = useEnter(i + 5);
  const issueTone = sheet.state === "blocked" ? "bad" : "warn";
  return (
    <motion.article className={"rs-card glass rs-" + status.key} aria-labelledby={"rs-title-" + sheet.id}
                    animate={fadeUp.animate} {...enter}>
      <header className="rs-head">
        <span className="rs-mark" aria-hidden="true"><Icon name="euro" size={18} /></span>
        <div className="rs-title">
          <h2 id={"rs-title-" + sheet.id}>{sheet.carrier}</h2>
          <a className="rs-file" href={`/api/v1/messages/${sheet.message_id}/attachments/${sheet.attachment_id}`}
             download={sheet.filename} aria-label={"Download " + sheet.filename}>
            <Icon name="paperclip" size={14} /><span>{sheet.filename}</span>
          </a>
        </div>
        <StatusChip status={status} />
      </header>

      <dl className="rs-facts">
        {sheet.valid_from && <ValidityFact sheet={sheet} today={today} />}
        {sheet.received && (
          <div className="rs-fact">
            <dt><Icon name="mail" size={13} />Received</dt>
            <dd>
              <time className="rs-fact-main" dateTime={sheet.received}>{receivedFmt.format(new Date(sheet.received))}</time>
              <span className="rs-fact-sub">{ago(sheet.received, now)} · Berlin time</span>
            </dd>
          </div>
        )}
        {sheet.decided_at && DECIDED[sheet.state] && (
          <div className="rs-fact">
            <dt><Icon name="check" size={13} />{DECIDED[sheet.state]}</dt>
            <dd>
              <time className="rs-fact-main" dateTime={sheet.decided_at}>{receivedFmt.format(new Date(sheet.decided_at))}</time>
              <span className="rs-fact-sub">{ago(sheet.decided_at, now)}</span>
            </dd>
          </div>
        )}
      </dl>

      {sheet.issue && (
        <p className={"rs-issue rs-issue-" + issueTone} role="note">
          <Icon name="alert" size={15} /><span>{sheet.issue}</span>
        </p>
      )}

      {sheet.rates.length > 0 && (
        <Section title="Lane prices" icon="plane" badge={sheet.rates.length} summary={laneSummary(sheet.rates)}>
          <RateTable rates={sheet.rates} carrier={sheet.carrier} id={"rs-rates-" + sheet.id} />
        </Section>
      )}

      {options.length > 0 && (canDecide ? (
        <div className="rs-actions">
          {options.map((d) => (
            <button key={d} className={"btn rs-btn rs-btn-" + ACTIONS[d].tone} disabled={saving}
                    onClick={() => onDecide({ sheet, decision: d })}>
              {ACTIONS[d].label}
            </button>
          ))}
        </div>
      ) : (
        <p className="rs-readonly-note"><Icon name="lock" size={14} />
          {options.map((d) => ACTIONS[d].label).join(" or ")} from the desk that runs the live worker.
        </p>
      ))}
    </motion.article>
  );
}

function Skeleton() {
  return (
    <div className="rs-loading" aria-busy="true" aria-label="Loading rate sheets">
      <div className="tiles rs-tiles">{Array.from({ length: 5 }).map((_, i) => <div key={i} className="skeleton glass rs-skel-tile" />)}</div>
      {Array.from({ length: 2 }).map((_, i) => <div key={i} className="skeleton glass rs-skel-card" />)}
    </div>
  );
}

function Empty() {
  return (
    <motion.div className="glass rs-empty" {...fadeUp}>
      <div className="empty-state">
        <span className="rs-empty-icon"><Icon name="euro" size={26} /></span>
        <strong>No rate sheets yet</strong>
        <span>When a carrier emails a rate sheet (PDF, XLSX or text), it appears here with its validity period and lane prices for review.</span>
      </div>
    </motion.div>
  );
}

function Failed({ message, onRetry, busy }) {
  return (
    <motion.div className="glass rs-empty" role="alert" {...fadeUp}>
      <div className="empty-state rs-failed">
        <span className="rs-empty-icon rs-empty-bad"><Icon name="alert" size={26} /></span>
        <strong>Rate sheets couldn’t be loaded</strong>
        <span>{message}</span>
        <button className="btn rs-btn" onClick={onRetry} disabled={busy}>Try again</button>
      </div>
    </motion.div>
  );
}

/* ---------- View ---------- */

export default function RateSheets() {
  const [queue, setQueue] = useState(null);
  const [pollError, setPollError] = useState("");
  const [actionError, setActionError] = useState("");
  const [action, setAction] = useState(null);
  const [saving, setSaving] = useState(false);
  const [retrying, setRetrying] = useState(false);
  const latestFetch = useRef(0);

  const refresh = useCallback(async () => {
    const request = ++latestFetch.current;
    try {
      const next = await api("/tariff-sheets");
      if (request === latestFetch.current) {
        setQueue(next);
        setPollError("");
      }
    } catch (error) {
      if (request === latestFetch.current) setPollError(error.message);
    }
  }, []);

  useEffect(() => {
    let active = true;
    let timer;
    async function poll() {
      await refresh();
      if (active) timer = setTimeout(poll, 5000);
    }
    poll();
    return () => {
      active = false;
      latestFetch.current++;
      clearTimeout(timer);
    };
  }, [refresh]);

  const cancel = useCallback(() => setAction(null), []);
  async function confirm() {
    const { sheet, decision } = action;
    setAction(null);
    setSaving(true);
    setActionError("");
    try {
      await api("/tariff-sheets/" + sheet.id + "/decision", { decision });
    } catch (error) {
      setActionError(error.message);
    } finally {
      await refresh();
      setSaving(false);
    }
  }
  async function retry() {
    setRetrying(true);
    await refresh();
    setRetrying(false);
  }

  const selected = action && ACTIONS[action.decision];
  const today = queue?.today ?? new Date().toISOString().slice(0, 10);
  const sheets = queue?.sheets ?? [];
  const now = Date.now();
  const bannerError = queue ? actionError || pollError : actionError;

  return (
    <div className="rates">
      <AnimatePresence>
        {bannerError && (
          <motion.p key="error" className="banner banner-bad" role="alert" {...fadeUp}>
            <Icon name="alert" size={16} />
            <span>{actionError ? "That change wasn’t saved. " + actionError : "Couldn’t refresh. Showing the last update. " + pollError}</span>
          </motion.p>
        )}
      </AnimatePresence>

      {!queue ? (
        pollError ? <Failed message={pollError} onRetry={retry} busy={retrying} /> : <Skeleton />
      ) : (
        <>
          {!queue.can_decide && (
            <motion.p className="rs-notice glass" {...fadeUp}>
              <Icon name="lock" size={16} />
              <span><b>View only.</b> This desk can’t approve, pause or reject rate sheets. Decisions are made on the desk that runs the live worker.</span>
            </motion.p>
          )}
          {sheets.length > 0 && <Summary sheets={sheets} today={today} />}
          {sheets.length === 0 ? <Empty /> : (
            <div className="rs-list">
              {sheets.map((sheet, i) => (
                <SheetCard key={sheet.id} i={i} sheet={sheet} today={today} now={now}
                           canDecide={queue.can_decide} saving={saving} onDecide={setAction} />
              ))}
            </div>
          )}
        </>
      )}

      <ConfirmDialog open={Boolean(action)}
        title={selected?.title}
        message={selected?.message(action.sheet)}
        confirmLabel={selected?.label}
        confirmStyle={selected?.style ?? "primary"}
        onConfirm={confirm} onCancel={cancel} />
    </div>
  );
}
