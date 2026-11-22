"use client";

import { memo, useMemo } from "react";
import { motion } from "motion/react";
import Icon from "../ui/Icon";
import { formatPoints } from "../case/BoardScore";
import { scoreModel } from "../../lib/score";
import { clock } from "../../lib/format";
import { stagger } from "../../lib/motion";
import "./score.css";

function Tile({ i, icon, label, value, sub, tone }) {
  return (
    <motion.div className={"tile glass" + (tone ? " tile-" + tone : "")}
                initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} transition={stagger(i, 0.05)}>
      <span className="tile-label"><Icon name={icon} size={14} />{label}</span>
      <span className="tile-value">{value}</span>
      {sub && <span className="tile-sub">{sub}</span>}
    </motion.div>
  );
}

const pct = (n, of) => (of ? Math.round((n / of) * 100) + "%" : "—");

function Criteria({ criteria }) {
  return (
    <ul className="sc-criteria">
      {criteria.map((c, i) => (
        <li key={c.key}>
          <div className="sc-crit-head">
            <span className="sc-crit-label">{c.label}<small>{c.hint}</small></span>
            <span className="sc-crit-value"><b>{c.avg == null ? "—" : formatPoints(c.avg)}</b> / {c.max}</span>
          </div>
          <span className="sc-track" aria-hidden="true">
            <motion.span className="sc-fill" initial={{ scaleX: 0 }} animate={{ scaleX: c.avg == null ? 0 : c.avg / c.max }}
                         transition={stagger(i)} />
          </span>
          <span className="sc-crit-sub">Full marks on {c.full} of {c.n} accepted quotes</span>
        </li>
      ))}
    </ul>
  );
}

function Extras({ bonuses, adjustment }) {
  return (
    <ul className="sc-extras">
      {bonuses.map((b) => (
        <li key={b.key}>
          <span>{b.label}</span>
          <span className="mono">{b.n ? `${b.earned} of ${b.n}` : "—"}</span>
          <b className={"mono" + (b.total > 0 ? " tone-ok" : "")}>{b.total > 0 ? "+" : ""}{formatPoints(b.total)}</b>
        </li>
      ))}
      <li>
        <span>Adjustments</span>
        <span className="mono">{adjustment.n ? adjustment.n + " rounds" : "none"}</span>
        <b className={"mono" + (adjustment.total < 0 ? " tone-bad" : "")}>{formatPoints(adjustment.total)}</b>
      </li>
    </ul>
  );
}

function Losses({ losses, total, onOpen }) {
  if (!losses.length) return <p className="sc-empty"><Icon name="check" size={14} /> Every scored round was accepted.</p>;
  const max = Math.max(...losses.map((l) => l.n));
  return (
    <ul className="sc-losses">
      {losses.map((l) => (
        <li key={l.label}>
          <button type="button" onClick={() => onOpen(l.rounds.at(-1).id)} title={"Open the latest: request #" + l.rounds.at(-1).id}>
            <span className="sc-loss-label">{l.label}</span>
            <span className="sc-loss-track"><span className={"sc-loss-fill" + (l.unanswered ? " muted" : "")} style={{ width: (l.n / max) * 100 + "%" }} /></span>
            <b className="mono">{l.n}</b>
          </button>
        </li>
      ))}
      <li className="sc-loss-foot">{losses.reduce((a, l) => a + l.n, 0)} of {total} rounds scored no points</li>
    </ul>
  );
}

/** One bar per round, height = points. Bars share the card's width, so the strip never scrolls.
 *  Rejected and unanswered rounds sit on the baseline. */
function Rounds({ rounds, maxTotal, onOpen }) {
  return (
    <div className="sc-rounds" role="list" aria-label={`Points for each of ${rounds.length} rounds`}>
      {rounds.map((r, i) => {
        const v = String(r.result.verdict || "").toLowerCase();
        const accepted = v === "accepted" && r.total != null;
        const tone = v === "accepted" ? "ok" : v === "rejected" ? "bad" : "muted";
        const label = `Round ${r.result.round_no ?? i + 1} · ${clock(r.received)} · ${accepted ? formatPoints(r.total) + " pts" : r.result.verdict}`;
        return (
          <button key={r.id} type="button" role="listitem" className={"sc-bar " + tone} title={label} aria-label={label}
                  style={{ "--h": accepted ? Math.max(3, (r.total / maxTotal) * 100) + "%" : "4px" }}
                  onClick={() => onOpen(r.id)} />
        );
      })}
    </div>
  );
}

function Scoreboard({ messages, onOpen }) {
  const s = useMemo(() => scoreModel(messages), [messages]);
  const scored = s.rounds.length;
  return (
    <div className="score">
      <p className="sc-note">
        <Icon name="alert" size={14} />
        The 5U challenge's evaluation of our quotes, read from the board. It measures the challenge, not the product.
      </p>

      <div className="tiles">
        <Tile i={0} icon="bars" label="Board points" value={scored ? formatPoints(s.total) : "—"} tone={scored ? "ok" : undefined}
              sub={scored ? `${scored} rounds scored` : "No verdicts yet"} />
        <Tile i={1} icon="check" label="Accepted" value={pct(s.accepted, scored)}
              sub={`${s.accepted} of ${scored} rounds`} />
        <Tile i={2} icon="bolt" label="Per accepted quote" value={s.perAccepted == null ? "—" : formatPoints(s.perAccepted)}
              sub="points, bonuses included" />
        <Tile i={3} icon="alert" label="No points" value={s.rejected + s.unanswered} tone={s.rejected + s.unanswered ? "warn" : undefined}
              sub={`${s.rejected} rejected · ${s.unanswered} unanswered`} />
      </div>

      {!scored ? (
        <section className="card glass">
          <div className="empty-state">
            <Icon name="bars" size={28} />
            <strong>No board results yet</strong>
            <span>Scores appear here once the 5U board has judged the first quote.</span>
          </div>
        </section>
      ) : (
        <>
          <div className="sc-main">
            <motion.section className="card glass" aria-label="Average points per criterion"
                            initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} transition={stagger(4, 0.05)}>
              <div className="card-head"><h2><Icon name="bars" size={16} />Criteria</h2><span className="eyebrow">Average per accepted quote</span></div>
              <Criteria criteria={s.criteria} />
              <div className="sc-sub-head"><span className="eyebrow">Bonuses and adjustments</span></div>
              <Extras bonuses={s.bonuses} adjustment={s.adjustment} />
            </motion.section>
            <motion.section className="card glass" aria-label="Why rounds scored no points"
                            initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} transition={stagger(5, 0.05)}>
              <div className="card-head"><h2><Icon name="funnel" size={16} />No points</h2><span className="eyebrow">By board reason</span></div>
              <Losses losses={s.losses} total={scored} onOpen={onOpen} />
            </motion.section>
          </div>

          <motion.section className="card glass" aria-label="Points per round"
                          initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} transition={stagger(6, 0.05)}>
            <div className="card-head">
              <h2><Icon name="clock" size={16} />Rounds</h2>
              <span className="sc-legend">
                <i className="ok" />Accepted <i className="bad" />Rejected <i className="muted" />No answer
              </span>
            </div>
            <Rounds rounds={s.rounds} maxTotal={s.maxTotal} onOpen={onOpen} />
            <div className="sc-rounds-axis">
              <span>Round {s.rounds[0].result.round_no ?? 1} · {clock(s.rounds[0].received)}</span>
              <span>Round {s.rounds.at(-1).result.round_no ?? scored} · {clock(s.rounds.at(-1).received)}</span>
            </div>
          </motion.section>
        </>
      )}
    </div>
  );
}

export default memo(Scoreboard);
