"use client";

import { ArrowUpRight, LocateFixed } from "lucide-react";
import Link from "next/link";
import { useEffect, useRef } from "react";
import type { FlightState } from "./flight";
import {
  PHASE_COLOR,
  PHASE_LABEL,
  STATUS_COLOR,
  STATUS_LABEL,
  formatEta,
  formatPrice,
} from "./format";
import type { ActiveShipment } from "./types";

interface Props {
  shipments: ActiveShipment[];
  states: FlightState[];
  selectedId: number | null;
  onToggle: (id: number) => void;
  onFocus: (id: number | null) => void;
  isDemo: boolean;
}

export function ShipmentPanel({ shipments, states, selectedId, onToggle, onFocus, isDemo }: Props) {
  const listRef = useRef<HTMLUListElement>(null);

  useEffect(() => {
    if (selectedId == null) return;
    listRef.current
      ?.querySelector(`[data-id="${selectedId}"]`)
      ?.scrollIntoView({ block: "nearest", behavior: "smooth" });
  }, [selectedId]);

  return (
    <aside className="absolute inset-x-3 bottom-3 z-20 flex h-[40vh] flex-col overflow-hidden rounded-3xl border border-white/[0.07] bg-slate-950/60 shadow-[0_30px_80px_-20px_rgba(0,0,0,0.8),inset_0_1px_0_rgba(255,255,255,0.05)] backdrop-blur-2xl md:inset-x-auto md:top-4 md:right-4 md:bottom-4 md:h-auto md:w-[360px]">
      <div className="flex items-end justify-between border-b border-white/[0.06] px-5 pt-5 pb-4">
        <div>
          <div className="text-[10px] font-semibold tracking-[0.2em] text-slate-500 uppercase">
            Active shipments
          </div>
          <div className="mt-1 text-lg font-semibold text-white">
            {shipments.length} <span className="text-sm font-normal text-slate-400">on the network</span>
          </div>
        </div>
        {isDemo && (
          <span className="mb-1 rounded-full border border-violet-300/20 bg-violet-400/10 px-2 py-0.5 text-[10px] text-violet-200">
            demo
          </span>
        )}
      </div>

      <ul ref={listRef} className="flex-1 space-y-1.5 overflow-y-auto p-2.5 [scrollbar-width:thin]">
        {shipments.map((s, i) => {
          const st = states[i];
          const selected = s.bookingId === selectedId;
          const sc = STATUS_COLOR[s.status];
          const pc = st ? PHASE_COLOR[st.phase] : PHASE_COLOR.awaiting;
          const pct = Math.round((st?.t ?? 0) * 100);
          return (
            <li key={s.bookingId} data-id={s.bookingId}>
              <div
                role="button"
                tabIndex={0}
                aria-pressed={selected}
                onClick={() => onToggle(s.bookingId)}
                onKeyDown={(e) => {
                  if (e.key === "Enter" || e.key === " ") {
                    e.preventDefault();
                    onToggle(s.bookingId);
                  }
                }}
                onMouseEnter={() => onFocus(s.bookingId)}
                onMouseLeave={() => onFocus(null)}
                className={`group relative cursor-pointer overflow-hidden rounded-2xl border px-3.5 py-3 transition-all duration-200 outline-none focus-visible:ring-2 focus-visible:ring-sky-400/50 ${
                  selected
                    ? "border-sky-300/25 bg-sky-400/[0.08]"
                    : "border-transparent hover:border-white/[0.06] hover:bg-white/[0.035]"
                }`}
              >
                <span
                  className="absolute top-3 bottom-3 left-0 w-[3px] rounded-r-full"
                  style={{ background: sc, boxShadow: `0 0 12px ${sc}` }}
                />
                <div className="flex items-center justify-between gap-2">
                  <span className="font-mono text-[10.5px] tracking-wider text-slate-500">{s.publicRef}</span>
                  <span
                    className="rounded-full px-1.5 py-px text-[9.5px] font-semibold tracking-wider uppercase"
                    style={{ color: sc, background: `${sc}17` }}
                  >
                    {STATUS_LABEL[s.status]}
                  </span>
                </div>
                <div className="mt-1 flex items-baseline gap-2">
                  <span className="text-[17px] font-semibold tracking-wide text-white">{s.origin.iata}</span>
                  <span className="text-slate-600">→</span>
                  <span className="text-[17px] font-semibold tracking-wide text-white">{s.destination.iata}</span>
                  <span className="ml-auto text-[13px] font-medium text-slate-200 tabular-nums">
                    {formatPrice(s)}
                  </span>
                </div>
                <div className="truncate text-[11.5px] text-slate-400">
                  {s.origin.city ?? s.origin.iata} to {s.destination.city ?? s.destination.iata}
                  {s.airlineName ? ` · ${s.airlineName}` : ""}
                </div>

                <div className="mt-2.5 h-1 overflow-hidden rounded-full bg-white/[0.06]">
                  <div
                    className="h-full rounded-full transition-[width] duration-200 ease-linear"
                    style={{
                      width: `${Math.max(2, pct)}%`,
                      background: `linear-gradient(90deg, ${pc}55, ${pc})`,
                      boxShadow: `0 0 10px ${pc}99`,
                    }}
                  />
                </div>
                <div className="mt-1.5 flex items-center justify-between text-[10.5px]">
                  <span style={{ color: pc }}>{st ? PHASE_LABEL[st.phase] : ""}</span>
                  <span className="text-slate-500 tabular-nums">{formatEta(s)}</span>
                </div>

                {selected && (
                  <div className="mt-3 flex items-center gap-2 border-t border-white/[0.06] pt-3 text-[11px]">
                    <span className="flex items-center gap-1 text-sky-200/80">
                      <LocateFixed className="size-3.5" /> Tracking on globe
                    </span>
                    {s.bookingReference && (
                      <span className="font-mono text-slate-500">{s.bookingReference}</span>
                    )}
                    <Link
                      href={`/sessions/${s.sessionId}`}
                      onClick={(e) => e.stopPropagation()}
                      className="ml-auto flex items-center gap-1 rounded-lg bg-white/[0.06] px-2 py-1 font-medium text-slate-100 transition-colors hover:bg-white/[0.12]"
                    >
                      Open session <ArrowUpRight className="size-3.5" />
                    </Link>
                  </div>
                )}
              </div>
            </li>
          );
        })}
        {shipments.length === 0 && (
          <li className="px-4 py-10 text-center text-sm text-slate-500">No active shipments yet.</li>
        )}
      </ul>
    </aside>
  );
}
