import { Plane } from "lucide-react";
import { PHASE_COLOR, STATUS_COLOR } from "./format";

function Line({ color }: { color: string }) {
  return (
    <span
      className="h-[3px] w-6 rounded-full"
      style={{ background: `linear-gradient(90deg, ${color}22, ${color})`, boxShadow: `0 0 8px ${color}88` }}
    />
  );
}

function Dot({ color, ring }: { color: string; ring?: boolean }) {
  return (
    <span className="grid w-6 place-items-center">
      <span
        className={`size-2.5 rounded-full ${ring ? "border-2 bg-transparent" : ""}`}
        style={ring ? { borderColor: color } : { background: color, boxShadow: `0 0 10px ${color}` }}
      />
    </span>
  );
}

export function Legend({ showTraffic }: { showTraffic: boolean }) {
  const rows = [
    { key: "c", el: <Line color={STATUS_COLOR.confirmed} />, label: "Confirmed booking" },
    { key: "b", el: <Line color={STATUS_COLOR.booked} />, label: "Booked, awaiting confirmation" },
    {
      key: "p",
      el: (
        <span className="grid w-6 place-items-center">
          <Plane className="size-3.5 rotate-45 fill-white text-white" />
        </span>
      ),
      label: "Shipment in the air",
    },
    { key: "a", el: <Dot color={PHASE_COLOR.awaiting} />, label: "Awaiting departure (pulsing)" },
    { key: "l", el: <Dot color={PHASE_COLOR.landed} ring />, label: "Landed at destination" },
    { key: "h", el: <Dot color="#ffb547" />, label: "German hub" },
  ];
  return (
    <div className="rounded-2xl border border-white/[0.07] bg-slate-950/45 px-4 py-3 text-[11px] text-slate-400 shadow-[inset_0_1px_0_rgba(255,255,255,0.04)] backdrop-blur-xl">
      <div className="mb-2 text-[10px] font-semibold tracking-[0.18em] text-slate-500 uppercase">Legend</div>
      <ul className="grid gap-1.5">
        {rows.map((r) => (
          <li key={r.key} className="flex items-center gap-2.5">
            {r.el}
            {r.label}
          </li>
        ))}
        {showTraffic && (
          <li className="flex items-center gap-2.5">
            <span className="grid w-6 place-items-center">
              <span className="size-1 rounded-full bg-[#9fb4d9]/60" />
            </span>
            Live global traffic (OpenSky), not our shipments
          </li>
        )}
      </ul>
    </div>
  );
}
