"use client";

import dynamic from "next/dynamic";
import { useCallback, useEffect, useMemo, useState, useSyncExternalStore } from "react";
import { flightState } from "./flight";
import { Legend } from "./Legend";
import { ShipmentPanel } from "./ShipmentPanel";
import { StatsBar } from "./StatsBar";
import { TimeModeToggle, TrafficToggle } from "./Controls";
import type { FlightPhase, TimeMode } from "./types";
import { useActiveShipments } from "./useActiveShipments";
import { useOpenSky } from "./useOpenSky";

const GlobeCanvas = dynamic(() => import("./GlobeCanvas"), {
  ssr: false,
  loading: () => <GlobeLoading />,
});

function GlobeLoading() {
  return (
    <div className="absolute inset-0 grid place-items-center">
      <div className="relative size-[min(56vh,56vw)] rounded-full border border-sky-300/10 bg-[radial-gradient(circle_at_35%_30%,rgba(77,124,255,0.18),transparent_60%)] shadow-[0_0_120px_-20px_rgba(77,124,255,0.45)]">
        <div className="absolute inset-0 animate-pulse rounded-full border border-sky-300/10" />
        <span className="absolute inset-x-0 bottom-[-2.5rem] text-center text-xs tracking-[0.2em] text-slate-400 uppercase">
          Spinning up the globe…
        </span>
      </div>
    </div>
  );
}

export interface PhaseCounts {
  air: number;
  landed: number;
  awaiting: number;
}

const noopSubscribe = () => () => {};

export function GlobeView() {
  const hydrated = useSyncExternalStore(
    noopSubscribe,
    () => true,
    () => false,
  );
  const { shipments, isDemo, isLoading } = useActiveShipments();
  const [modeOverride, setModeOverride] = useState<TimeMode | null>(null);
  const timeMode: TimeMode = modeOverride ?? (isDemo ? "demo" : "real");
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [focusId, setFocusId] = useState<number | null>(null);
  const [trafficOn, setTrafficOn] = useState(false);
  const traffic = useOpenSky(trafficOn);

  // UI clock. The globe animates itself via rAF; this only drives the panel's text & bars.
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), timeMode === "demo" ? 200 : 1000);
    return () => clearInterval(id);
  }, [timeMode]);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && setSelectedId(null);
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);

  const states = useMemo(
    () => shipments.map((s, i) => flightState(s, timeMode, now, i)),
    [shipments, timeMode, now],
  );
  const counts = useMemo<PhaseCounts>(() => {
    const c: Record<FlightPhase, number> = { air: 0, landed: 0, awaiting: 0 };
    for (const st of states) c[st.phase]++;
    return c;
  }, [states]);

  const onSelect = useCallback((id: number | null) => setSelectedId(id), []);
  const toggleSelect = useCallback(
    (id: number) => setSelectedId((cur) => (cur === id ? null : id)),
    [],
  );

  // Everything below depends on the wall clock, so render it on the client only
  // (avoids hydration mismatches from server-rendered progress/ETA text).
  if (!hydrated) {
    return (
      <div className="relative isolate h-dvh max-h-full min-h-[560px] w-full overflow-hidden bg-[radial-gradient(ellipse_at_42%_45%,#0d1a3d_0%,#060b1c_42%,#02040a_78%)]">
        <GlobeLoading />
      </div>
    );
  }

  return (
    <div className="relative isolate h-dvh max-h-full min-h-[560px] w-full overflow-hidden bg-[#03050d] text-slate-100">
      {/* Deep-space backdrop */}
      <div
        aria-hidden
        className="pointer-events-none absolute inset-0 -z-10 bg-[radial-gradient(ellipse_at_42%_45%,#0d1a3d_0%,#060b1c_42%,#02040a_78%)]"
      />
      <div
        aria-hidden
        className="pointer-events-none absolute inset-0 z-10 bg-[radial-gradient(ellipse_at_center,transparent_55%,rgba(1,3,8,0.75)_100%)]"
      />

      <div className="absolute inset-0 md:right-[380px]">
        <GlobeCanvas
          shipments={shipments}
          timeMode={timeMode}
          selectedId={selectedId}
          focusId={focusId}
          onSelect={onSelect}
          traffic={traffic.positions}
        />
      </div>

      {/* Header + stats */}
      <header className="pointer-events-none absolute top-0 left-0 z-20 flex max-w-[calc(100%-2rem)] flex-col gap-4 p-4 md:p-7">
        <div className="pointer-events-auto">
          <div className="flex items-center gap-2 text-[10.5px] font-semibold tracking-[0.22em] text-sky-200/60 uppercase">
            <span className="relative flex size-1.5">
              <span className="absolute inline-flex size-full animate-ping rounded-full bg-emerald-400 opacity-70" />
              <span className="relative inline-flex size-1.5 rounded-full bg-emerald-400" />
            </span>
            Live network
            <span
              className={`ml-1 rounded-full border px-2 py-0.5 text-[9.5px] tracking-[0.14em] ${
                isDemo
                  ? "border-violet-300/25 bg-violet-400/10 text-violet-200"
                  : "border-emerald-300/25 bg-emerald-400/10 text-emerald-200"
              }`}
            >
              {isLoading ? "connecting" : isDemo ? "demo data" : "live data"}
            </span>
          </div>
          <h1 className="mt-2 text-2xl font-semibold tracking-tight text-white md:text-[32px] md:leading-[1.1]">
            Air freight in motion
          </h1>

        </div>
        <StatsBar counts={counts} total={shipments.length} />
      </header>

      {/* Controls */}
      <div className="absolute bottom-[calc(40vh+1rem)] left-4 z-20 flex flex-col items-start gap-2 md:bottom-7 md:left-7">
        <TimeModeToggle value={timeMode} onChange={setModeOverride} />
        <TrafficToggle on={trafficOn} onChange={setTrafficOn} status={traffic.status} count={traffic.count} />
      </div>

      <div className="absolute right-4 bottom-7 z-20 hidden md:right-[404px] lg:block">
        <Legend showTraffic={trafficOn} />
      </div>

      <ShipmentPanel
        shipments={shipments}
        states={states}
        selectedId={selectedId}
        onToggle={toggleSelect}
        onFocus={setFocusId}
        isDemo={isDemo}
      />
    </div>
  );
}
