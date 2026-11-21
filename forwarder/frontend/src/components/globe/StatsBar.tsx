import { Hourglass, PlaneLanding, PlaneTakeoff } from "lucide-react";
import type { ComponentType } from "react";
import { PHASE_COLOR } from "./format";
import type { PhaseCounts } from "./GlobeView";

const ITEMS: { key: keyof PhaseCounts; label: string; Icon: ComponentType<{ className?: string }> }[] = [
  { key: "air", label: "In the air", Icon: PlaneTakeoff },
  { key: "landed", label: "Landed", Icon: PlaneLanding },
  { key: "awaiting", label: "Awaiting departure", Icon: Hourglass },
];

export function StatsBar({ counts, total }: { counts: PhaseCounts; total: number }) {
  return (
    <div className="pointer-events-auto flex flex-wrap gap-2">
      {ITEMS.map(({ key, label, Icon }) => (
        <div
          key={key}
          className="flex items-center gap-3 rounded-2xl md:min-w-[124px] border border-white/[0.07] bg-slate-950/45 px-3.5 py-2.5 shadow-[inset_0_1px_0_rgba(255,255,255,0.04)] backdrop-blur-xl"
        >
          <span
            className="grid size-8 place-items-center rounded-xl"
            style={{ background: `${PHASE_COLOR[key]}1a`, color: PHASE_COLOR[key] }}
          >
            <Icon className="size-4" />
          </span>
          <div className="leading-tight">
            <div className="text-xl font-semibold text-white tabular-nums">
              {counts[key]}
              <span className="ml-1 text-xs font-normal text-slate-500">/ {total}</span>
            </div>
            <div className="text-[11px] whitespace-nowrap text-slate-400">{label}</div>
          </div>
        </div>
      ))}
    </div>
  );
}
