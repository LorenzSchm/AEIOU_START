import { Clock, Satellite, Zap } from "lucide-react";
import type { TimeMode } from "./types";
import type { OpenSkyStatus } from "./useOpenSky";

const glass =
  "rounded-2xl border border-white/[0.07] bg-slate-950/55 shadow-[inset_0_1px_0_rgba(255,255,255,0.04)] backdrop-blur-xl";

export function TimeModeToggle({ value, onChange }: { value: TimeMode; onChange: (m: TimeMode) => void }) {
  const opts: { v: TimeMode; label: string; hint: string; Icon: typeof Clock }[] = [
    { v: "real", label: "Real time", hint: "True progress, transit takes days", Icon: Clock },
    { v: "demo", label: "Demo", hint: "Each flight loops in ~12 s", Icon: Zap },
  ];
  return (
    <div role="radiogroup" aria-label="Time mode" className={`${glass} flex p-1`}>
      {opts.map(({ v, label, hint, Icon }) => {
        const active = value === v;
        return (
          <button
            key={v}
            role="radio"
            aria-checked={active}
            title={hint}
            onClick={() => onChange(v)}
            className={`flex items-center gap-1.5 rounded-xl px-3 py-1.5 text-xs font-medium transition-all duration-200 ${
              active
                ? "bg-sky-400/15 text-sky-100 shadow-[inset_0_0_0_1px_rgba(125,190,255,0.28)]"
                : "text-slate-400 hover:text-slate-200"
            }`}
          >
            <Icon className="size-3.5" />
            {label}
          </button>
        );
      })}
    </div>
  );
}

const STATUS_TEXT: Record<OpenSkyStatus, string> = {
  off: "off",
  loading: "connecting…",
  ok: "",
  error: "unavailable",
  "rate-limited": "rate-limited, retrying",
};

export function TrafficToggle({
  on,
  onChange,
  status,
  count,
}: {
  on: boolean;
  onChange: (v: boolean) => void;
  status: OpenSkyStatus;
  count: number;
}) {
  const detail = status === "ok" ? `${count.toLocaleString("en-GB")} aircraft` : STATUS_TEXT[status];
  return (
    <button
      role="switch"
      aria-checked={on}
      onClick={() => onChange(!on)}
      className={`${glass} group flex items-center gap-2.5 px-3 py-2 text-left text-xs`}
    >
      <span
        className={`relative h-4 w-7 rounded-full transition-colors ${on ? "bg-sky-400/60" : "bg-slate-700/80"}`}
      >
        <span
          className={`absolute top-0.5 size-3 rounded-full bg-white shadow transition-all ${on ? "left-3.5" : "left-0.5"}`}
        />
      </span>
      <Satellite className="size-3.5 text-slate-400" />
      <span className="text-slate-300">
        Live global traffic <span className="text-slate-500">(OpenSky)</span>
        {on && (
          <span className={`ml-1.5 ${status === "error" || status === "rate-limited" ? "text-amber-300/80" : "text-slate-500"}`}>
            · {detail}
          </span>
        )}
      </span>
    </button>
  );
}
