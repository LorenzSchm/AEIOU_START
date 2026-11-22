"use client";

import { motion } from "motion/react";
import { Chip } from "../ui/primitives";
import { stagger } from "../../lib/motion";

// 5U leaderboard verdict for one RFQ (backend BoardWatcher → message.board_result).
// Shape: { round_no, verdict, reason?, total?, points: { kind: n }, cost_pending? }

export function verdictTone(verdict = "") {
  const v = verdict.toLowerCase();
  if (/(accept|won|win|valid|success|ok|scored)/.test(v)) return "ok";
  if (/(reject|invalid|lost|late|miss|expired|fail|wrong)/.test(v)) return "bad";
  if (/(pending|open|wait)/.test(v)) return "muted";
  return "info";
}

const words = (s) => String(s || "").replaceAll("_", " ");

const pointsFormat = new Intl.NumberFormat(undefined, { maximumFractionDigits: 2 });
/** Board points with at most two decimals, e.g. 38.8 or 13.93. */
export const formatPoints = (n) => pointsFormat.format(n);

export function boardTotal(result) {
  if (!result) return null;
  if (result.total != null) return Number(result.total);
  const pts = Object.values(result.points ?? {}).map(Number).filter(Number.isFinite);
  return pts.length ? pts.reduce((a, b) => a + b, 0) : null;
}

/** Compact points chip for list rows. */
export function BoardChip({ result }) {
  if (!result) return null;
  const total = boardTotal(result);
  return (
    <Chip tone={verdictTone(result.verdict)} icon={false}>
      {total != null ? `${total > 0 ? "+" : ""}${formatPoints(total)} pts` : words(result.verdict)}
    </Chip>
  );
}

export default function BoardScore({ result }) {
  if (!result) return null;
  const total = boardTotal(result);
  const points = Object.entries(result.points ?? {}).map(([k, v]) => [k, Number(v)]).filter(([, v]) => Number.isFinite(v));
  const max = Math.max(1, ...points.map(([, v]) => Math.abs(v)));
  const tone = verdictTone(result.verdict);
  return (
    <section className={"board tone-card-" + tone} aria-label="5U board result">
      <div className="board-head">
        <div>
          <div className="eyebrow">5U board · Round {result.round_no ?? "—"}</div>
          <div className="board-verdict">
            <Chip tone={tone}>{words(result.verdict)}</Chip>
            {result.reason && <span className="board-reason">{words(result.reason)}</span>}
          </div>
        </div>
        <div className="board-total" title="Total points">
          {total != null || !(result.cost_pending || tone === "ok" || tone === "muted") ? (
            <>
              <b>{formatPoints(total ?? 0)}</b>
              <small>{result.cost_pending ? "pts · cost pending" : "points"}</small>
            </>
          ) : (
            <>
              <b className="board-pending">Pending</b>
              <small>{result.cost_pending ? "cost not scored yet" : "not scored yet"}</small>
            </>
          )}
        </div>
      </div>
      {points.length > 0 && (
        <ul className="board-points">
          {points.map(([kind, value], i) => (
            <li key={kind}>
              <span className="board-kind">{words(kind)}</span>
              <span className="board-track">
                <motion.span
                  className={"board-fill" + (value < 0 ? " neg" : "")}
                  initial={{ scaleX: 0 }}
                  animate={{ scaleX: Math.abs(value) / max }}
                  transition={stagger(i)}
                />
              </span>
              <span className="board-val">{value > 0 ? "+" : ""}{formatPoints(value)}</span>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
