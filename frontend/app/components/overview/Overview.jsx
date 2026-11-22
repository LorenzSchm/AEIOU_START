"use client";

import { memo, useMemo } from "react";
import { motion } from "motion/react";
import Icon from "../ui/Icon";
import { Chip, RouteBadge } from "../ui/primitives";
import CargoGlobe from "../globe/CargoGlobe";
import { waveSummary } from "../../lib/process";
import { eur, statusOf } from "../../lib/format";
import { crmModel } from "../../lib/crm";
import { stagger } from "../../lib/motion";

function Tile({ i, icon, label, value, sub, tone, wide, children }) {
  return (
    <motion.div className={"tile glass" + (tone ? " tile-" + tone : "") + (wide ? " tile-wide" : "")}
                initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} transition={stagger(i, 0.05)}>
      <span className="tile-label"><Icon name={icon} size={14} />{label}</span>
      <span className="tile-value">{value}</span>
      {sub && <span className="tile-sub">{sub}</span>}
      {children}
    </motion.div>
  );
}

function Meter({ value }) {
  const pct = Math.round((value || 0) * 100);
  return (
    <span className="meter" role="meter" aria-valuenow={pct} aria-valuemin={0} aria-valuemax={100}>
      <motion.span className="meter-fill" initial={{ scaleX: 0 }} animate={{ scaleX: value || 0 }} transition={stagger(2)} />
    </span>
  );
}

const SOURCE_COLORS = { portal: "var(--blue)", tariff: "var(--accent)", "email bid": "var(--ok)" };

/**
 * Figures come from the server summary (GET /summary, live over /events); the local models are the
 * fallback for mock mode. `desk` carries the globe routes and the attention list (summary.desk).
 */
function Overview({ messages, summary, desk, onOpen, active = true }) {
  const local = useMemo(() => (summary?.overview ? null : waveSummary(messages)), [messages, summary]);
  const s = summary?.overview ?? local;
  const routes = desk.routes;
  const attention = desk.attention_items;
  // Business figures only; the 5U challenge's scoring lives on the Scoreboard view.
  const localCrm = useMemo(() => (summary?.shipments ? null : crmModel(messages)), [messages, summary]);
  const kpis = summary?.shipments?.kpis ?? localCrm.kpis;
  const bookedCount = summary?.shipments ? summary.shipments.funnel.find((f) => f.key === "booked").n : localCrm.bookings.length;
  const sources = Object.entries(s.bySource || {}).filter(([, n]) => n > 0);
  const sourceTotal = sources.reduce((a, [, n]) => a + n, 0) || 1;
  const topDest = (s.byDestination || []).slice(0, 5);
  const destMax = Math.max(1, ...topDest.map((d) => d.n));

  return (
    <div className="overview">
      <div className="tiles">
        <Tile i={0} wide icon="euro" label="Booked value" value={bookedCount ? eur(kpis.bookedValue) : "—"}
              tone={bookedCount ? "ok" : undefined}
              sub={bookedCount
                ? eur(kpis.quotedValue) + " quoted · " + Math.round((kpis.conversion ?? 0) * 100) + "% of requests booked"
                : "no bookings yet"} />
        <Tile i={1} icon="mail" label="Requests" value={s.requests ?? 0} sub={(s.inProgress ?? 0) + " in progress"} />
        <Tile i={2} icon="send" label="Quoted" value={s.quoted ?? 0}
              sub={s.onTimeRate != null ? Math.round(s.onTimeRate * 100) + "% inside 6 min" : "—"}>
          <Meter value={s.onTimeRate} />
        </Tile>
        <Tile i={3} icon="book" label="Booked" value={s.booked ?? 0} tone="ok" />
        <Tile i={4} icon="clock" label="Median to quote"
              value={s.medianQuoteSeconds != null ? Math.round(s.medianQuoteSeconds) + " s" : "—"} sub="from arrival" />
        <Tile i={5} icon="alert" label="Needs attention" value={s.attention ?? 0} tone={s.attention ? "warn" : undefined}
              sub={(s.expired ?? 0) + " expired"} />
        <Tile i={6} icon="euro" label="Saved vs next best" value={eur(s.savedVsNext)} sub="sum across quotes" />
      </div>

      <div className="overview-main">
        <motion.section className="globe-card glass" initial={{ opacity: 0 }} animate={{ opacity: 1 }} transition={{ duration: 0.5 }}>
          <div className="card-head">
            <h2><Icon name="globe" size={16} /> Cargo routes</h2>
            <span className="muted">{routes.length} shipments · drag to rotate · click a route</span>
          </div>
          <div className="globe-wrap">
            <CargoGlobe routes={routes} onSelect={onOpen} paused={!active} />
          </div>
        </motion.section>

        <div className="overview-side">
          <section className="card glass">
            <div className="card-head"><h2>Needs attention</h2>{attention.length > 0 && <Chip tone="warn">{attention.length}</Chip>}</div>
            {attention.length ? (
              <ul className="mini-list">
                {attention.map((m) => (
                  <li key={m.id}>
                    <button onClick={() => onOpen(m.id)}>
                      <RouteBadge origin={m.fields?.origin} destination={m.fields?.destination} size="sm" />
                      <span className="mini-reason" title={m.review_reason || ""}>{m.review_reason || statusOf(m).label}</span>
                      <Icon name="chevron" size={14} />
                    </button>
                  </li>
                ))}
              </ul>
            ) : (
              <p className="empty-line"><Icon name="check" size={14} /> All clear. The worker is handling everything.</p>
            )}
          </section>

          <section className="card glass">
            <div className="card-head"><h2>Winning rate source</h2></div>
            {sources.length ? (
              <>
                <div className="stack" role="img"
                     aria-label={sources.map(([k, n]) => k + " " + n).join(", ")}>
                  {sources.map(([k, n], i) => (
                    <motion.span key={k} style={{ background: SOURCE_COLORS[k] || "var(--muted)", flexGrow: n }}
                                 initial={{ scaleX: 0 }} animate={{ scaleX: 1 }} transition={stagger(i)} />
                  ))}
                </div>
                <ul className="legend">
                  {sources.map(([k, n]) => (
                    <li key={k}><i style={{ background: SOURCE_COLORS[k] || "var(--muted)" }} />{k}<b>{Math.round((n / sourceTotal) * 100)}%</b></li>
                  ))}
                </ul>
              </>
            ) : <p className="empty-line">No priced quotes yet.</p>}
          </section>

          <section className="card glass">
            <div className="card-head"><h2>Top destinations</h2></div>
            {topDest.length ? (
              <ol className="dest-bars">
                {topDest.map((d, i) => (
                  <li key={d.code}>
                    <b className="mono">{d.code}</b>
                    <span className="offer-track"><motion.span className="offer-fill" initial={{ scaleX: 0 }}
                      animate={{ scaleX: d.n / destMax }} transition={stagger(i)} /></span>
                    <span className="mono">{d.n}</span>
                  </li>
                ))}
              </ol>
            ) : <p className="empty-line">No requests yet.</p>}
          </section>
        </div>
      </div>
    </div>
  );
}

export default memo(Overview);
