import type { ActiveShipment, FlightPhase, TimeMode } from "./types";

const DEG = Math.PI / 180;

/** Great-circle angular distance in radians. */
export function angularDistance(lat1: number, lon1: number, lat2: number, lon2: number): number {
  const p1 = lat1 * DEG;
  const p2 = lat2 * DEG;
  const dp = p2 - p1;
  const dl = (lon2 - lon1) * DEG;
  const a = Math.sin(dp / 2) ** 2 + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) ** 2;
  return 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
}

/** Point at fraction `t` along the great circle (spherical linear interpolation). */
export function greatCirclePoint(
  lat1: number,
  lon1: number,
  lat2: number,
  lon2: number,
  t: number,
): [lat: number, lon: number] {
  const d = angularDistance(lat1, lon1, lat2, lon2);
  if (d === 0) return [lat1, lon1];
  const p1 = lat1 * DEG;
  const l1 = lon1 * DEG;
  const p2 = lat2 * DEG;
  const l2 = lon2 * DEG;
  const a = Math.sin((1 - t) * d) / Math.sin(d);
  const b = Math.sin(t * d) / Math.sin(d);
  const x = a * Math.cos(p1) * Math.cos(l1) + b * Math.cos(p2) * Math.cos(l2);
  const y = a * Math.cos(p1) * Math.sin(l1) + b * Math.cos(p2) * Math.sin(l2);
  const z = a * Math.sin(p1) + b * Math.sin(p2);
  return [Math.atan2(z, Math.hypot(x, y)) / DEG, Math.atan2(y, x) / DEG];
}

/** Arc apex altitude (relative to globe radius). Shared by the arc layer and the plane path. */
export function arcAltitude(s: ActiveShipment): number {
  const d = angularDistance(s.origin.lat, s.origin.lon, s.destination.lat, s.destination.lon);
  return Math.min(0.42, (d / 2) * 0.32 + 0.01);
}

export function etaMs(s: ActiveShipment): number | null {
  if (s.etaAt) return Date.parse(s.etaAt);
  if (s.departureAt && s.transitDays != null) {
    return Date.parse(s.departureAt) + s.transitDays * 86_400_000;
  }
  return null;
}

export interface FlightState {
  phase: FlightPhase;
  /** 0..1 position along the route. */
  t: number;
  /** 0..1 within the current phase (used for pulsing / easing). */
  phaseT: number;
}

const DEMO_AWAIT = 0.14;
const DEMO_LAND = 0.86;

function demoPeriod(index: number): number {
  return 11_000 + ((index * 1_731) % 4_000);
}

function smooth(x: number): number {
  return x * x * (3 - 2 * x);
}

/**
 * Where a shipment is at `nowMs`.
 * - real: `progress = (now - departureAt) / (etaAt - departureAt)`, clamped 0..1.
 * - demo: each flight loops over ~11–15 s, staggered by index.
 */
export function flightState(
  s: ActiveShipment,
  mode: TimeMode,
  nowMs: number,
  index: number,
): FlightState {
  if (mode === "demo") {
    const period = demoPeriod(index);
    const c = (nowMs / period + index * 0.161) % 1;
    if (c < DEMO_AWAIT) return { phase: "awaiting", t: 0, phaseT: c / DEMO_AWAIT };
    if (c > DEMO_LAND) return { phase: "landed", t: 1, phaseT: (c - DEMO_LAND) / (1 - DEMO_LAND) };
    const p = (c - DEMO_AWAIT) / (DEMO_LAND - DEMO_AWAIT);
    // Gentle ease so take-off and landing feel physical, while cruising stays near-linear.
    const t = 0.15 * smooth(p) + 0.85 * p;
    return { phase: "air", t, phaseT: p };
  }

  const dep = s.departureAt ? Date.parse(s.departureAt) : NaN;
  const eta = etaMs(s);
  const pulse = (nowMs % 1_600) / 1_600;
  if (!Number.isFinite(dep) || nowMs < dep) return { phase: "awaiting", t: 0, phaseT: pulse };
  if (eta == null || !Number.isFinite(eta) || eta <= dep) return { phase: "air", t: 0.5, phaseT: 0.5 };
  const t = (nowMs - dep) / (eta - dep);
  if (t >= 1) return { phase: "landed", t: 1, phaseT: pulse };
  return { phase: "air", t: Math.max(0, t), phaseT: t };
}
