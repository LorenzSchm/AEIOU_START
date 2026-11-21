"use client";

import { motion } from "motion/react";
import { eur, transitLabel } from "../../lib/format";
import { stagger } from "../../lib/motion";

/** Horizontal bars: price per offer, winner highlighted, transit as secondary label. */
export default function OfferBars({ offers, winner }) {
  if (!offers?.length) return <p className="empty-line">No offers compared yet.</p>;
  const sorted = [...offers].sort((a, b) => a.total_eur - b.total_eur);
  const max = Math.max(...sorted.map((o) => Number(o.total_eur) || 0)) || 1;
  const isWinner = (o) => winner && o.airline === winner.airline && o.total_eur === winner.total_eur && o.source === winner.source;
  return (
    <ol className="offers" aria-label="Compared offers, cheapest first">
      {sorted.map((o, i) => (
        <li key={i} className={isWinner(o) ? "win" : ""}>
          <span className="offer-name">
            {o.airline}
            <small>{o.source}</small>
          </span>
          <span className="offer-track">
            <motion.span
              className="offer-fill"
              initial={{ scaleX: 0 }}
              animate={{ scaleX: (Number(o.total_eur) || 0) / max }}
              transition={stagger(i)}
            />
          </span>
          <span className="offer-val">
            <b>{eur(o.total_eur)}</b>
            <small>{transitLabel(o.days)}</small>
          </span>
        </li>
      ))}
    </ol>
  );
}
