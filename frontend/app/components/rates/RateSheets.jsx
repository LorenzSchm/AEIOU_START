"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { AnimatePresence, motion, useReducedMotion } from "motion/react";
import { api } from "../../lib/api";
import { disclosure, fadeUp, stagger } from "../../lib/motion";
import ConfirmDialog from "../ui/ConfirmDialog";
import Icon from "../ui/Icon";
import { Chip, RouteBadge } from "../ui/primitives";
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
    <motion.div className={"tile glass" + (tone ? " tile-" + tone : "")} {...enter}>
      <span className="tile-label"><Icon name={icon} size={14} />{label}</span>
      <span className="tile-value">{value}</span>
      {sub && <span className="tile-sub">{sub}</span>}
    </motion.div>
  );
}

function Summary({ sheets, today }) {
  const statuses = sheets.map((s) => statusOf(s, today));
  const usable = sheets.filter((s, i) => ["preferred", "approved"].includes(statuses[i].key));
  const lanes = new Set(usable.flatMap((s) => s.rates.map((r) => r.origin + "-" + r.destination)));
  const carriers = new Set(usable.map((s) => s.carrier));
  const pending = statuses.filter((s) => s.key === "pending").length;
  const unusable = statuses.filter((s) => ["blocked", "expired", "rejected"].includes(s.key)).length;
  return (
    <div className="tiles" role="group" aria-label="Rate sheet summary">
      <Tile i={0} icon="check" label="In use" value={usable.length} tone={usable.length ? "ok" : undefined}
            sub={carriers.size ? plural(carriers.size, "carrier") : "no sheet in use"} />
      <Tile i={1} icon="plane" label="Lanes covered" value={lanes.size} sub="by sheets in use" />
      <Tile i={2} icon="clock" label="Awaiting approval" value={pending} tone={pending ? "warn" : undefined} />
      <Tile i={3} icon="alert" label="Not usable" value={unusable} sub="blocked, expired or rejected" />
    </div>
  );
}

function transitRange(rates) {
  const days = rates.map((r) => r.days).filter((d) => d != null);
  if (!days.length) return null;
  const lo = Math.min(...days), hi = Math.max(...days);
  return lo === hi ? plural(lo, "day") : lo + "–" + hi + " days";
}

function breakLabel(weight, next) {
  return Number(weight) === 0 ? (next ? "< " + next + " kg" : "Any weight") : weight + " kg+";
}

const sortLanes = (rates) => [...rates].sort((a, b) => (a.origin + a.destination).localeCompare(b.origin + b.destination));
const breakColumns = (rates) => [...new Set(rates.flatMap((r) => Object.keys(r.breaks)))].sort((a, b) => a - b);
const str = (v) => (v == null ? "" : String(v));
// Edit fields show prices the way the table does (two decimals), so nothing looks changed until it is.
const priceText = (v) => (v == null || v === "" ? "" : Number(v).toFixed(2));

/** A form-friendly copy of a sheet: every value as text, lanes in display order. */
function draftOf(sheet) {
  return {
    validFrom: sheet.valid_from || "", validTo: sheet.valid_to || "",
    breaks: breakColumns(sheet.rates),
    lanes: sortLanes(sheet.rates).map((r) => ({
      key: r.origin + r.destination, origin: r.origin, destination: r.destination, days: str(r.days),
      minimum: priceText(r.minimum), fuel: priceText(r.fuel), security: priceText(r.security),
      breaks: Object.fromEntries(Object.entries(r.breaks).map(([w, v]) => [w, priceText(v)])),
    })),
  };
}

const num = (v) => (String(v).trim() === "" ? null : Number(String(v).replace(",", ".")));

/** The revision payload the backend expects; it validates and explains anything wrong. */
function revisionOf(draft) {
  return {
    valid_from: draft.validFrom, valid_to: draft.validTo,
    rates: draft.lanes.map((l) => ({
      origin: l.origin, destination: l.destination, days: num(l.days),
      minimum: num(l.minimum), fuel: num(l.fuel), security: num(l.security),
      breaks: Object.fromEntries(draft.breaks.filter((w) => String(l.breaks[w] ?? "").trim() !== "")
        .map((w) => [w, num(l.breaks[w])])),
    })),
  };
}

function Cell({ value, original, onChange, label, kind = "number", wide }) {
  const changed = original !== undefined && String(value).trim() !== String(original ?? "").trim();
  return (
    <input className={"rs-input" + (wide ? " rs-input-code" : "") + (changed ? " changed" : "")} value={value}
           inputMode={kind === "number" ? "decimal" : undefined} maxLength={kind === "code" ? 3 : undefined}
           aria-label={label} title={changed ? "Was " + (original === "" || original == null ? "empty" : original) : undefined}
           onChange={(e) => onChange(kind === "code" ? e.target.value.toUpperCase() : e.target.value)} />
  );
}

function RateTable({ rates, carrier, draft, base, onDraft }) {
  const editing = Boolean(draft);
  const breaks = editing ? draft.breaks : breakColumns(rates);
  const rows = editing ? draft.lanes : sortLanes(rates);
  const was = (key) => base?.lanes.find((l) => l.key === key);
  const set = (key, patch) => onDraft({ ...draft, lanes: draft.lanes.map((l) => (l.key === key ? { ...l, ...patch } : l)) });
  const remove = (key) => onDraft({ ...draft, lanes: draft.lanes.filter((l) => l.key !== key) });
  return (
    <div className="rs-lanes" tabIndex={editing ? undefined : 0} role="region" aria-label={carrier + " lane prices"}>
      <table className={"rs-lane-table" + (editing ? " editing" : "")}>
        <thead>
          <tr>
            <th scope="col">Lane</th>
            <th scope="col" className="end">Transit</th>
            <th scope="col" className="end">Min</th>
            {breaks.map((w, i) => <th scope="col" className="end" key={w}>{breakLabel(w, breaks[i + 1])}</th>)}
            <th scope="col" className="end">Fuel</th>
            <th scope="col" className="end">Security</th>
            {editing && <th scope="col"><span className="sr-only">Remove</span></th>}
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => {
            if (!editing) return (
              <tr key={r.origin + r.destination}>
                <th scope="row"><RouteBadge origin={r.origin} destination={r.destination} size="sm" /></th>
                <td className="end">{r.days == null ? "—" : plural(r.days, "day")}</td>
                <td className="end mono">{money(r.minimum)}</td>
                {breaks.map((w) => <td className="end mono" key={w}>{money(r.breaks[w])}</td>)}
                <td className="end mono">{money(r.fuel)}</td>
                <td className="end mono">{money(r.security)}</td>
              </tr>
            );
            const o = was(r.key);
            const name = r.origin + " to " + r.destination;
            return (
              <tr key={r.key}>
                <th scope="row" className="rs-lane-edit">
                  <Cell kind="code" wide value={r.origin} original={o?.origin} label={name + " origin"} onChange={(v) => set(r.key, { origin: v })} />
                  <span aria-hidden="true">→</span>
                  <Cell kind="code" wide value={r.destination} original={o?.destination} label={name + " destination"} onChange={(v) => set(r.key, { destination: v })} />
                </th>
                <td className="end"><Cell value={r.days} original={o?.days} label={name + " transit days"} onChange={(v) => set(r.key, { days: v })} /></td>
                <td className="end"><Cell value={r.minimum} original={o?.minimum} label={name + " minimum"} onChange={(v) => set(r.key, { minimum: v })} /></td>
                {breaks.map((w, i) => (
                  <td className="end" key={w}>
                    <Cell value={r.breaks[w] ?? ""} original={o?.breaks[w] ?? ""} label={name + " " + breakLabel(w, breaks[i + 1])}
                          onChange={(v) => set(r.key, { breaks: { ...r.breaks, [w]: v } })} />
                  </td>
                ))}
                <td className="end"><Cell value={r.fuel} original={o?.fuel} label={name + " fuel"} onChange={(v) => set(r.key, { fuel: v })} /></td>
                <td className="end"><Cell value={r.security} original={o?.security} label={name + " security"} onChange={(v) => set(r.key, { security: v })} /></td>
                <td>
                  <button type="button" className="icon-btn rs-remove" aria-label={"Remove lane " + name} title="Remove lane"
                          onClick={() => remove(r.key)}><Icon name="close" size={14} /></button>
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

const EDITABLE = new Set(["pending", "blocked"]);

/** Everything about one sheet that is only needed on demand: its issue, prices, file and decisions. */
function SheetDetail({ sheet, today, canDecide, saving, onDecide, onSaved }) {
  const options = decisions(sheet, today);
  const [base, setBase] = useState(null);
  const [draft, setDraft] = useState(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const editable = canDecide && EDITABLE.has(sheet.state);
  const startEdit = () => { const d = draftOf(sheet); setBase(d); setDraft(d); setError(""); };
  const cancel = () => { setDraft(null); setBase(null); setError(""); };
  async function save() {
    setBusy(true);
    setError("");
    try {
      await api("/tariff-sheets/" + sheet.id + "/revision", revisionOf(draft));
      setDraft(null);
      setBase(null);
      await onSaved();
    } catch (e) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  }
  const datesChanged = draft && (draft.validFrom !== base.validFrom || draft.validTo !== base.validTo);
  const edited = sheet.edited_at && sheet.original;
  return (
    <div className="rs-detail">
      {sheet.issue && !draft && (
        <p className={"rs-issue " + (sheet.state === "blocked" ? "tone-bad" : "tone-warn")}>
          <Icon name="alert" size={14} />{sheet.issue}
        </p>
      )}
      {draft && (
        <div className="rs-validity-edit">
          <label>Valid from<input type="date" className={"rs-input rs-date" + (draft.validFrom !== base.validFrom ? " changed" : "")}
                                   value={draft.validFrom} onChange={(e) => setDraft({ ...draft, validFrom: e.target.value })} /></label>
          <label>to<input type="date" className={"rs-input rs-date" + (draft.validTo !== base.validTo ? " changed" : "")}
                          value={draft.validTo} onChange={(e) => setDraft({ ...draft, validTo: e.target.value })} /></label>
          {datesChanged && <span className="rs-muted">Carrier sent {range(base.validFrom, base.validTo)}</span>}
        </div>
      )}
      {sheet.rates.length > 0 || draft ? (
        <RateTable rates={sheet.rates} carrier={sheet.carrier} draft={draft} base={base} onDraft={setDraft} />
      ) : <p className="rs-muted">No lane prices could be read from this sheet.</p>}
      {error && <p className="rs-issue tone-bad" role="alert"><Icon name="alert" size={14} />{error}</p>}
      <div className="rs-detail-foot">
        {draft ? (
          <>
            <span className="rs-muted">Saved changes go back to review. Approve the sheet to use them for quotes.</span>
            <span className="rs-actions">
              <button type="button" className="btn rs-btn" onClick={cancel} disabled={busy}>Cancel</button>
              <button type="button" className="btn rs-btn rs-btn-primary" onClick={save} disabled={busy || !draft.lanes.length}>
                {busy ? "Saving…" : "Save changes"}
              </button>
            </span>
          </>
        ) : (
          <>
            <span className="rs-muted">
              {edited ? "Corrected " + receivedFmt.format(new Date(sheet.edited_at)) + " · " : ""}
              EUR · breaks, fuel and security per kg · minimum per shipment
            </span>
            <a className="cv-file" href={`/api/v1/messages/${sheet.message_id}/attachments/${sheet.attachment_id}`}
               download={sheet.filename}>
              <Icon name="paperclip" size={13} />{sheet.filename}
            </a>
            {canDecide && (options.length > 0 || editable) && (
              <span className="rs-actions">
                {editable && (
                  <button type="button" className="btn rs-btn" disabled={saving} onClick={startEdit}>Edit values</button>
                )}
                {options.map((d) => (
                  <button key={d} type="button" className={"btn rs-btn rs-btn-" + ACTIONS[d].tone} disabled={saving}
                          onClick={() => onDecide({ sheet, decision: d })}>
                    {ACTIONS[d].label}
                  </button>
                ))}
              </span>
            )}
          </>
        )}
      </div>
    </div>
  );
}

const COLUMNS = 5;

function SheetRow({ sheet, today, now, open, onToggle, ...detail }) {
  const status = statusOf(sheet, today);
  const v = validity(sheet, today);
  const reduce = useReducedMotion();
  const detailId = "rs-detail-" + sheet.id;
  const toggle = () => onToggle(sheet.id);
  return (
    <>
      <tr className={"rs-row" + (open ? " open" : "")} onClick={toggle}>
        <td>
          <button type="button" className="rs-toggle" aria-expanded={open} aria-controls={detailId}
                  onClick={(e) => { e.stopPropagation(); toggle(); }}>
            <Icon name="chevron" size={13} className="rs-caret" />
            <span className="cell-stack">
              <b>{sheet.carrier}</b>
              <small>{sheet.filename}</small>
            </span>
          </button>
        </td>
        <td><Chip tone={status.tone}>{status.label}</Chip></td>
        <td>
          <span className="cell-stack">
            <span>{range(sheet.valid_from, sheet.valid_to)}</span>
            {v && <small className={v.phase === "expired" ? "tone-bad" : v.phase === "ending" ? "tone-warn" : ""}>{v.label}</small>}
          </span>
        </td>
        <td className="end">
          <span className="cell-stack end">
            <span className="mono">{sheet.rates.length}</span>
            <small>{transitRange(sheet.rates) || "—"}</small>
          </span>
        </td>
        <td className="end opt">
          {sheet.received && (
            <span className="cell-stack end">
              <time dateTime={sheet.received}>{receivedFmt.format(new Date(sheet.received))}</time>
              <small>{ago(sheet.received, now)}</small>
            </span>
          )}
        </td>
      </tr>
      <tr className="rs-detail-row" id={detailId}>
        <td colSpan={COLUMNS}>
          <AnimatePresence initial={false}>
            {open && (
              <motion.div key="detail" className="rs-detail-wrap"
                          initial={reduce ? false : disclosure.initial} animate={disclosure.animate}
                          exit={reduce ? undefined : disclosure.exit}>
                <SheetDetail sheet={sheet} today={today} {...detail} />
              </motion.div>
            )}
          </AnimatePresence>
        </td>
      </tr>
    </>
  );
}

function Ledger({ sheets, today, now, canDecide, saving, onDecide, onSaved }) {
  // Sheets that need a decision open by default; everything else stays one line.
  const [open, setOpen] = useState(() => new Set(sheets.filter((s) => s.state === "pending").map((s) => s.id)));
  const toggle = useCallback((id) => setOpen((prev) => {
    const next = new Set(prev);
    next.has(id) ? next.delete(id) : next.add(id);
    return next;
  }), []);
  const enter = useEnter(4);
  return (
    <motion.section className="glass rs-ledger" aria-label="Rate sheets" {...enter}>
      <div className="rs-ledger-head">
        <h2>Sheets <span className="rs-count">{sheets.length}</span></h2>
        {!canDecide && <span className="rs-muted"><Icon name="lock" size={13} />View only · decisions are made on the live desk</span>}
      </div>
      <div className="rs-scroll">
        <table className="rs-table">
          <thead>
            <tr>
              <th scope="col">Carrier</th>
              <th scope="col">Status</th>
              <th scope="col">Valid</th>
              <th scope="col" className="end">Lanes</th>
              <th scope="col" className="end opt">Received</th>
            </tr>
          </thead>
          <tbody>
            {sheets.map((sheet) => (
              <SheetRow key={sheet.id} sheet={sheet} today={today} now={now} open={open.has(sheet.id)} onToggle={toggle}
                        canDecide={canDecide} saving={saving} onDecide={onDecide} onSaved={onSaved} />
            ))}
          </tbody>
        </table>
      </div>
    </motion.section>
  );
}

function Skeleton() {
  return (
    <div className="rates" aria-busy="true" aria-label="Loading rate sheets">
      <div className="tiles">{Array.from({ length: 4 }).map((_, i) => <div key={i} className="skeleton glass rs-skel-tile" />)}</div>
      <div className="skeleton glass rs-skel-ledger" />
    </div>
  );
}

function Empty() {
  return (
    <motion.section className="glass rs-ledger" {...fadeUp}>
      <div className="empty-state">
        <Icon name="euro" size={28} />
        <strong>No rate sheets yet</strong>
        <span>Rate sheets carriers send by email appear here with their validity and lane prices.</span>
      </div>
    </motion.section>
  );
}

function Failed({ message, onRetry, busy }) {
  return (
    <motion.section className="glass rs-ledger" role="alert" {...fadeUp}>
      <div className="empty-state rs-failed">
        <Icon name="alert" size={28} />
        <strong>Rate sheets couldn’t be loaded</strong>
        <span>{message}</span>
        <button type="button" className="btn rs-btn" onClick={onRetry} disabled={busy}>Try again</button>
      </div>
    </motion.section>
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
          {sheets.length > 0 && <Summary sheets={sheets} today={today} />}
          {sheets.length === 0 ? <Empty /> : (
            <Ledger sheets={sheets} today={today} now={now} canDecide={queue.can_decide}
                    saving={saving} onDecide={setAction} onSaved={refresh} />
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
