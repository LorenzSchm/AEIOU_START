"use client";

import { memo, useMemo, useState } from "react";
import { AnimatePresence, motion } from "motion/react";
import Icon from "../ui/Icon";
import { Chip, RouteBadge, Segmented } from "../ui/primitives";
import { BoardChip } from "../case/BoardScore";
import { crmModel, initials } from "../../lib/crm";
import { eur, transitLabel } from "../../lib/format";
import { fadeUp, stagger } from "../../lib/motion";
import "./crm.css";

const hm = (t) =>
  t == null ? "—" : new Date(t).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", hour12: false });

function ago(t, now) {
  if (t == null) return "";
  const s = Math.max(0, Math.round((now - t) / 1000));
  if (s < 60) return "just now";
  const m = Math.round(s / 60);
  if (m < 60) return m + " min ago";
  const h = Math.round(m / 60);
  return h < 24 ? h + " h ago" : Math.round(h / 24) + " d ago";
}

const pct = (v) => (v == null ? "—" : Math.round(v * 100) + "%");
const kg = (v) => (v == null ? "—" : Math.round(v).toLocaleString() + " kg");
const secs = (s) => (s == null ? "—" : s < 60 ? Math.round(s) + " s" : Math.floor(s / 60) + " m " + String(Math.round(s % 60)).padStart(2, "0") + " s");

function Tile({ i, icon, label, value, sub, tone, children }) {
  return (
    <motion.div className={"tile glass" + (tone ? " tile-" + tone : "")}
                initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} transition={stagger(i, 0.05)}>
      <span className="tile-label"><Icon name={icon} size={14} />{label}</span>
      <span className="tile-value">{value}</span>
      {sub && <span className="tile-sub">{sub}</span>}
      {children}
    </motion.div>
  );
}

function Meter({ value, tone = "ok" }) {
  const v = Math.max(0, Math.min(1, value || 0));
  return (
    <span className="meter" role="meter" aria-valuenow={Math.round(v * 100)} aria-valuemin={0} aria-valuemax={100}>
      <motion.span className={"meter-fill crm-meter-" + tone} initial={{ scaleX: 0 }} animate={{ scaleX: v }} transition={stagger(2)} />
    </span>
  );
}

function Avatar({ name }) {
  return <span className="cv-avatar crm-avatar" aria-hidden="true">{initials(name)}</span>;
}

/* ---------- Funnel ---------- */

const FUNNEL_TONE = { rfq: "neutral", quoted: "info", accepted: "accent", booked: "ok" };

function Funnel({ steps }) {
  const max = Math.max(1, steps[0]?.n || 0);
  if (!steps[0]?.n) return <p className="empty-line">No requests yet. The funnel fills as RFQs arrive.</p>;
  return (
    <ol className="funnel" aria-label="Won and lost funnel">
      {steps.map((s, i) => {
        const prev = i ? steps[i - 1].n : null;
        const drop = prev != null ? prev - s.n : 0;
        return (
          <li key={s.key} className={"funnel-step funnel-" + FUNNEL_TONE[s.key]}>
            <span className="funnel-label">{s.label}</span>
            <span className="funnel-track">
              <motion.span className="funnel-fill" initial={{ scaleX: 0 }} animate={{ scaleX: s.n / max }} transition={stagger(i, 0.07)} />
            </span>
            <b className="funnel-n">{s.n}</b>
            <span className="funnel-rate">
              {i === 0 ? "100%" : pct(prev ? s.n / prev : null)}
              {drop > 0 && <small>−{drop}</small>}
            </span>
          </li>
        );
      })}
    </ol>
  );
}

/** Won / lost / open split of every RFQ, as one stacked bar with a legend. */
function Outcome({ won, lost, open, wonValue, lostValue }) {
  const total = won + lost + open;
  if (!total) return null;
  const parts = [
    { key: "won", label: "Won", n: won, sub: eur(wonValue) + " booked", color: "var(--ok)" },
    { key: "lost", label: "Lost", n: lost, sub: eur(lostValue) + " quoted", color: "var(--bad)" },
    { key: "open", label: "Open", n: open, sub: "still in play", color: "var(--warn)" },
  ];
  return (
    <div className="outcome">
      <div className="card-head outcome-head"><span className="eyebrow">Outcome of every RFQ</span><span className="mono muted">{total}</span></div>
      <div className="stack outcome-stack" role="img" aria-label={parts.map((p) => p.label + " " + p.n).join(", ")}>
        {parts.filter((p) => p.n).map((p, i) => (
          <motion.span key={p.key} style={{ background: p.color, flexGrow: p.n }}
                       initial={{ scaleX: 0 }} animate={{ scaleX: 1 }} transition={stagger(i + 4)} />
        ))}
      </div>
      <ul className="outcome-legend">
        {parts.map((p) => (
          <li key={p.key}>
            <i style={{ background: p.color }} />
            <span className="cell-stack">
              <b>{p.label} <span className="mono">{p.n}</span> <small className="mono">{pct(p.n / total)}</small></b>
              <small>{p.sub}</small>
            </span>
          </li>
        ))}
      </ul>
    </div>
  );
}

/* ---------- Loss reasons ---------- */

function Reasons({ reasons, selected, onPick }) {
  const groups = [
    { key: "lost", label: "Lost", items: reasons.filter((r) => r.group === "lost") },
    { key: "open", label: "Still open", items: reasons.filter((r) => r.group === "open") },
  ].filter((g) => g.items.length);
  if (!groups.length) return <p className="empty-line"><Icon name="check" size={14} /> Nothing lost so far.</p>;
  const max = Math.max(1, ...reasons.map((r) => r.n));
  let k = 0;
  return (
    <div className="reasons">
      {groups.map((g) => (
        <div key={g.key} className="reasons-group">
          <div className="reasons-head">
            <span className="eyebrow">{g.label}</span>
            <span className="reasons-total mono">{g.items.reduce((a, r) => a + r.n, 0)}</span>
          </div>
          <ul>
            {g.items.map((r) => (
              <li key={r.id}>
                <button className={"reason reason-" + r.tone + (selected === r.id ? " active" : "")}
                        aria-pressed={selected === r.id} onClick={() => onPick(selected === r.id ? null : r.id)}>
                  <span className="reason-label">{r.label}</span>
                  <span className="reason-track">
                    <motion.span className="reason-fill" initial={{ scaleX: 0 }} animate={{ scaleX: r.n / max }} transition={stagger(k++)} />
                  </span>
                  <b className="mono">{r.n}</b>
                </button>
              </li>
            ))}
          </ul>
        </div>
      ))}
    </div>
  );
}

/* ---------- Sortable table ---------- */

function useSort(initial) {
  const [sort, setSort] = useState(initial);
  const toggle = (key) => setSort((s) => (s.key === key ? { key, dir: -s.dir } : { key, dir: -1 }));
  return [sort, toggle];
}

function sortRows(rows, columns, sort) {
  const col = columns.find((c) => c.key === sort.key);
  if (!col?.value) return rows;
  return [...rows].sort((a, b) => {
    const va = col.value(a), vb = col.value(b);
    if (va == null && vb == null) return 0;
    if (va == null) return 1;
    if (vb == null) return -1;
    return (typeof va === "string" ? va.localeCompare(vb) : va - vb) * sort.dir;
  });
}

function Table({ label, columns, rows, sort, onSort, onRow, rowKey, rowLabel, empty }) {
  const sorted = useMemo(() => sortRows(rows, columns, sort), [rows, columns, sort]);
  if (!rows.length) return empty;
  return (
    <div className="crm-scroll">
      <table className="crm-table" aria-label={label}>
        <thead>
          <tr>
            {columns.map((c) => (
              <th key={c.key} className={(c.align === "end" ? "end " : "") + (c.optional ? "opt" : "")}
                  aria-sort={sort.key === c.key ? (sort.dir > 0 ? "ascending" : "descending") : undefined} scope="col">
                {c.value ? (
                  <button className={"th-sort" + (sort.key === c.key ? " on" : "")} onClick={() => onSort(c.key)}>
                    {c.label}
                    <Icon name={sort.key === c.key ? (sort.dir > 0 ? "up" : "down") : "sort"} size={12} />
                  </button>
                ) : c.label}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {sorted.map((r) => (
            <tr key={rowKey(r)} tabIndex={0} aria-label={rowLabel(r)} onClick={() => onRow(r)}
                onKeyDown={(e) => {
                  if (e.key === "Enter" || e.key === " ") {
                    e.preventDefault();
                    onRow(r);
                  }
                }}>
              {columns.map((c) => (
                <td key={c.key} className={(c.align === "end" ? "end " : "") + (c.optional ? "opt" : "")}>{c.render(r)}</td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function Empty({ icon, title, text }) {
  return (
    <div className="empty-state">
      <Icon name={icon} size={28} />
      <strong>{title}</strong>
      <span>{text}</span>
    </div>
  );
}

function bookingStatus(d) {
  if (d.board?.booked === true) return <Chip tone="ok">Confirmed</Chip>;
  if (d.board?.booked === false) return <Chip tone="warn">Unconfirmed</Chip>;
  return <Chip tone="ok" icon={false}>{d.booking?.status || "Booked"}</Chip>;
}

/* ---------- View ---------- */

function Crm({ messages, onOpen }) {
  const model = useMemo(() => crmModel(messages), [messages]);
  const now = useMemo(() => Date.now(), [messages]); // eslint-disable-line react-hooks/exhaustive-deps
  const { kpis, funnel, reasons, bookings, customers, deals } = model;
  const [tab, setTab] = useState("bookings");
  const [query, setQuery] = useState("");
  const [customer, setCustomer] = useState(null);
  const [reason, setReason] = useState(null);
  const [bookSort, sortBookings] = useSort({ key: "booked", dir: -1 });
  const [custSort, sortCustomers] = useSort({ key: "bookedValue", dir: -1 });
  const [lostSort, sortLost] = useSort({ key: "received", dir: -1 });

  const q = query.trim().toLowerCase();
  const match = (d) =>
    (!customer || d.email === customer) &&
    (!q || [d.customer, d.email, d.origin, d.destination, d.carrier, d.booking?.booking_reference, d.booking?.team_reference, "#" + d.id, d.result.label]
      .filter(Boolean).join(" ").toLowerCase().includes(q));

  const bookingRows = useMemo(() => bookings.filter(match), [bookings, customer, q]); // eslint-disable-line react-hooks/exhaustive-deps
  const pipeline = useMemo(() => deals.filter((d) => d.result.group !== "won"), [deals]);
  const pipelineRows = useMemo(
    () => pipeline.filter((d) => match(d) && (!reason || d.result.group + ":" + d.result.key === reason)),
    [pipeline, customer, q, reason], // eslint-disable-line react-hooks/exhaustive-deps
  );
  const customerRows = useMemo(
    () => customers.filter((c) => !q || (c.name + " " + c.email + " " + (c.topDestination || "")).toLowerCase().includes(q)),
    [customers, q],
  );
  const customerName = customers.find((c) => c.email === customer)?.name;
  const reasonLabel = reasons.find((r) => r.id === reason)?.label;

  const pickReason = (id) => {
    setReason(id);
    if (id) setTab("pipeline");
  };
  const pickCustomer = (c) => {
    setCustomer(c.email);
    setQuery("");
    setTab(c.won ? "bookings" : "pipeline");
  };
  const openDeal = (d) => onOpen(d.id);

  const bookingCols = useMemo(() => [
    { key: "ref", label: "Reference", value: (d) => d.booking.booking_reference,
      render: (d) => (
        <span className="cell-stack">
          <b className="mono">{d.booking.booking_reference}</b>
          <small className="mono">{d.booking.team_reference || "#" + d.id}</small>
        </span>
      ) },
    { key: "customer", label: "Customer", value: (d) => d.customer,
      render: (d) => <span className="cell-who"><Avatar name={d.customer} /><span title={d.customer}>{d.customer}</span></span> },
    { key: "lane", label: "Lane", value: (d) => (d.origin || "") + (d.destination || ""),
      render: (d) => <RouteBadge origin={d.origin} destination={d.destination} size="sm" /> },
    { key: "carrier", label: "Carrier", value: (d) => d.carrier, optional: true,
      render: (d) => (
        <span className="cell-carrier">
          {d.booking.airline_code && <i className="mono">{d.booking.airline_code}</i>}
          <span title={d.carrier || undefined}>{d.carrier || "—"}</span>
        </span>
      ) },
    { key: "weight", label: "Weight", align: "end", value: (d) => d.weightKg, optional: true,
      render: (d) => <span className="mono">{kg(d.weightKg)}</span> },
    { key: "price", label: "All-in", align: "end", value: (d) => d.bookedAllIn,
      render: (d) => {
        const delta = d.allIn != null && d.bookedAllIn != null ? d.bookedAllIn - d.allIn : 0;
        return (
          <span className="cell-stack end">
            <b className="mono">{eur(d.bookedAllIn)}</b>
            {Math.abs(delta) >= 1 && (
              <small className={"mono " + (delta > 0 ? "tone-bad" : "tone-ok")} title={"Quoted " + eur(d.allIn)}>
                {delta > 0 ? "+" : "−"}{eur(Math.abs(delta))} vs quote
              </small>
            )}
          </span>
        );
      } },
    { key: "transit", label: "Transit", align: "end", value: (d) => d.days, optional: true,
      render: (d) => <span className="mono">{transitLabel(d.days)}</span> },
    { key: "booked", label: "Booked", align: "end", value: (d) => d.bookedAt ?? d.received,
      render: (d) => (
        <span className="cell-stack end" title="Time of the customer's booking instruction">
          <span className="mono">{hm(d.bookedAt ?? d.received)}</span>
          <small>{ago(d.bookedAt ?? d.received, now)}</small>
        </span>
      ) },
    { key: "status", label: "Status", value: (d) => (d.board?.booked === false ? 0 : 1),
      render: (d) => bookingStatus(d) },
  ], [now]);

  const customerCols = useMemo(() => [
    { key: "name", label: "Customer", value: (c) => c.name,
      render: (c) => (
        <span className="cell-who">
          <Avatar name={c.name} />
          <span className="cell-stack" title={c.name + " · " + c.email}><b>{c.name}</b><small>{c.email}</small></span>
        </span>
      ) },
    { key: "requests", label: "RFQs", align: "end", value: (c) => c.requests, render: (c) => <span className="mono">{c.requests}</span> },
    { key: "quoted", label: "Quotes", align: "end", value: (c) => c.quoted, optional: true, render: (c) => <span className="mono">{c.quoted}</span> },
    { key: "won", label: "Won / lost", align: "end", value: (c) => c.won,
      render: (c) => (
        <span className="wl">
          <b className="tone-ok mono">{c.won}</b><span>/</span><b className={"mono" + (c.lost ? " tone-bad" : "")}>{c.lost}</b>
          {c.open > 0 && <small>+{c.open} open</small>}
        </span>
      ) },
    { key: "winRate", label: "Win rate", value: (c) => c.winRate, optional: true,
      render: (c) => (
        <span className="cell-rate">
          <span className="rate-track"><motion.span className="rate-fill" initial={{ scaleX: 0 }} animate={{ scaleX: c.winRate || 0 }} transition={stagger(1)} /></span>
          <span className="mono">{pct(c.winRate)}</span>
        </span>
      ) },
    { key: "quotedValue", label: "Quoted value", align: "end", value: (c) => c.quotedValue, optional: true,
      render: (c) => <span className="mono">{c.quoted ? eur(c.quotedValue) : "—"}</span> },
    { key: "bookedValue", label: "Booked value", align: "end", value: (c) => c.bookedValue,
      render: (c) => <b className="mono">{c.won ? eur(c.bookedValue) : "—"}</b> },
    { key: "last", label: "Last activity", align: "end", value: (c) => c.lastActivity,
      render: (c) => (
        <span className="cell-stack end"><span className="mono">{hm(c.lastActivity)}</span><small>{ago(c.lastActivity, now)}</small></span>
      ) },
  ], [now]);

  const pipelineCols = useMemo(() => [
    { key: "id", label: "Request", value: (d) => d.id,
      render: (d) => <span className="cell-stack"><b className="mono">#{d.id}</b><small>{d.quoted ? "quoted" : "not quoted"}</small></span> },
    { key: "customer", label: "Customer", value: (d) => d.customer,
      render: (d) => <span className="cell-who"><Avatar name={d.customer} /><span title={d.customer}>{d.customer}</span></span> },
    { key: "lane", label: "Lane", value: (d) => (d.origin || "") + (d.destination || ""),
      render: (d) => (d.origin || d.destination ? <RouteBadge origin={d.origin} destination={d.destination} size="sm" /> : <span className="muted">unknown</span>) },
    { key: "weight", label: "Weight", align: "end", value: (d) => d.weightKg, optional: true,
      render: (d) => <span className="mono">{kg(d.weightKg)}</span> },
    { key: "price", label: "Quoted", align: "end", value: (d) => (d.quoted ? d.allIn : null),
      render: (d) => <span className="mono">{d.quoted ? eur(d.allIn) : "—"}</span> },
    { key: "reason", label: "Reason", value: (d) => d.result.label,
      render: (d) => <span className="row-chips"><Chip tone={d.result.tone} icon={d.result.group === "lost"}>{d.result.label}</Chip><BoardChip result={d.board} /></span> },
    { key: "received", label: "Received", align: "end", value: (d) => d.received,
      render: (d) => <span className="cell-stack end"><span className="mono">{hm(d.received)}</span><small>{ago(d.received, now)}</small></span> },
  ], [now]);

  const filtered = customer || q || reason;

  return (
    <div className="crm">
      <div className="tiles crm-tiles">
        <Tile i={0} icon="funnel" label="Conversion" value={pct(kpis.conversion)} tone={kpis.conversion ? "ok" : undefined}
              sub={bookings.length + " of " + deals.length + " RFQs booked · " + pct(kpis.quoteToBook) + " of quotes"}>
          <Meter value={kpis.conversion} />
        </Tile>
        <Tile i={1} icon="euro" label="Booked value" value={eur(kpis.bookedValue)}
              sub={kpis.avgBooking != null ? "avg " + eur(kpis.avgBooking) + " per shipment" : "no bookings yet"} />
        <Tile i={2} icon="clock" label="Avg time to quote" value={secs(kpis.avgQuoteSec)}
              sub={kpis.quoteCount ? "median " + secs(kpis.medianQuoteSec) + " · " + kpis.fastQuotes + " of " + kpis.quoteCount + " under 2 min" : "no quotes yet"} />
        <Tile i={3} icon="send" label="Quoted value" value={eur(kpis.quotedValue)}
              sub={kpis.lostCount + " lost · " + kpis.openCount + " still open"} />
      </div>

      <div className="crm-main">
        <motion.section className="card glass" initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} transition={stagger(4, 0.05)}>
          <div className="card-head">
            <h2><Icon name="funnel" size={16} /> Won / lost funnel</h2>
            <span className="muted">step conversion · drop-off</span>
          </div>
          <Funnel steps={funnel} />
          <Outcome won={bookings.length} lost={kpis.lostCount} open={kpis.openCount}
                   wonValue={kpis.bookedValue} lostValue={kpis.lostValue} />
        </motion.section>
        <motion.section className="card glass" initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} transition={stagger(5, 0.05)}>
          <div className="card-head">
            <h2><Icon name="alert" size={16} /> Why deals didn't book</h2>
            {reason && <button className="crm-link" onClick={() => setReason(null)}>Clear</button>}
          </div>
          <Reasons reasons={reasons} selected={reason} onPick={pickReason} />
        </motion.section>
      </div>

      <motion.section className="crm-ledger glass" aria-label="Shipments ledger"
                      initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} transition={stagger(6, 0.05)}>
        <div className="queue-head crm-head">
          <Segmented
            label="Ledger"
            value={tab}
            onChange={setTab}
            options={[
              { value: "bookings", label: "Bookings", count: bookings.length },
              { value: "customers", label: "Customers", count: customers.length },
              { value: "pipeline", label: "Lost & open", count: pipeline.length },
            ]}
          />
          <label className="search">
            <Icon name="search" size={15} />
            <input type="search" placeholder="Search customer, BK ref, lane, carrier" aria-label="Search shipments"
                   value={query} onChange={(e) => setQuery(e.target.value)} />
          </label>
          {(customer || (reason && tab === "pipeline")) && (
            <div className="crm-filters">
              {customer && tab !== "customers" && (
                <button className="crm-filter" onClick={() => setCustomer(null)} aria-label={"Remove filter " + customerName}>
                  <Icon name="users" size={13} />{customerName}<Icon name="close" size={12} />
                </button>
              )}
              {reason && tab === "pipeline" && (
                <button className="crm-filter" onClick={() => setReason(null)} aria-label={"Remove filter " + reasonLabel}>
                  <Icon name="funnel" size={13} />{reasonLabel}<Icon name="close" size={12} />
                </button>
              )}
            </div>
          )}
        </div>

        <AnimatePresence mode="wait" initial={false}>
          <motion.div key={tab} className="crm-tab" {...fadeUp}>
            {tab === "bookings" && (
              <Table label="Bookings" columns={bookingCols} rows={bookingRows} sort={bookSort} onSort={sortBookings}
                     onRow={openDeal} rowKey={(d) => d.id}
                     rowLabel={(d) => "Booking " + d.booking.booking_reference + ", " + d.customer + ", " + (d.origin || "?") + " to " + (d.destination || "?")}
                     empty={filtered && bookings.length
                       ? <Empty icon="search" title="No matching bookings" text="Try another search or clear the filter." />
                       : <Empty icon="book" title="No bookings yet" text="Booked shipments land here once a customer accepts a quote." />} />
            )}
            {tab === "customers" && (
              <Table label="Customers" columns={customerCols} rows={customerRows} sort={custSort} onSort={sortCustomers}
                     onRow={pickCustomer} rowKey={(c) => c.email}
                     rowLabel={(c) => c.name + ": " + c.won + " won, " + c.lost + " lost. Show their shipments"}
                     empty={q && customers.length
                       ? <Empty icon="search" title="No matching customers" text="Try another search." />
                       : <Empty icon="users" title="No customers yet" text="Every sender of an RFQ becomes a customer here." />} />
            )}
            {tab === "pipeline" && (
              <Table label="Lost and open requests" columns={pipelineCols} rows={pipelineRows} sort={lostSort} onSort={sortLost}
                     onRow={openDeal} rowKey={(d) => d.id}
                     rowLabel={(d) => "Request " + d.id + ", " + d.customer + ", " + d.result.label}
                     empty={filtered && pipeline.length
                       ? <Empty icon="search" title="No matching requests" text="Try another search or clear the filter." />
                       : <Empty icon="check" title="Nothing lost" text="Every request so far turned into a booking." />} />
            )}
          </motion.div>
        </AnimatePresence>
      </motion.section>
    </div>
  );
}

export default memo(Crm);
