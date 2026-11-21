import { format } from "date-fns";
import { etaMs } from "./flight";
import type { ActiveShipment, BookingStatus, FlightPhase } from "./types";

export const STATUS_COLOR: Record<BookingStatus, string> = {
  confirmed: "#3ee6c4",
  booked: "#ffb547",
  requested: "#8ea6d8",
  failed: "#ff6b6b",
};

export const STATUS_LABEL: Record<BookingStatus, string> = {
  confirmed: "Confirmed",
  booked: "Booked",
  requested: "Requested",
  failed: "Failed",
};

export const PHASE_COLOR: Record<FlightPhase, string> = {
  air: "#7cc4ff",
  landed: "#4ade80",
  awaiting: "#c4b5fd",
};

export const PHASE_LABEL: Record<FlightPhase, string> = {
  air: "In the air",
  landed: "Landed",
  awaiting: "Awaiting departure",
};

export function rgba(hex: string, a: number): string {
  const n = parseInt(hex.slice(1), 16);
  return `rgba(${(n >> 16) & 255},${(n >> 8) & 255},${n & 255},${a})`;
}

export function formatPrice(s: ActiveShipment): string {
  if (!s.price) return "n/a";
  const v = Number(s.price);
  if (!Number.isFinite(v)) return `${s.price} ${s.currency ?? ""}`.trim();
  try {
    return new Intl.NumberFormat("en-GB", {
      style: "currency",
      currency: s.currency ?? "EUR",
      maximumFractionDigits: 0,
    }).format(v);
  } catch {
    return `${v.toFixed(0)} ${s.currency ?? ""}`.trim();
  }
}

export function formatEta(s: ActiveShipment): string {
  const eta = etaMs(s);
  return eta == null ? "ETA pending" : `ETA ${format(eta, "EEE d MMM, HH:mm")}`;
}

export function routeLabel(s: ActiveShipment): string {
  return `${s.origin.iata} → ${s.destination.iata}`;
}

const esc = (v: string) =>
  v.replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]!);

/** HTML for the globe.gl hover tooltip (string, as react-globe.gl expects). */
export function tooltipHtml(s: ActiveShipment): string {
  const c = STATUS_COLOR[s.status];
  return `
  <div class="fwd-tt">
    <div class="fwd-tt-head">
      <span class="fwd-tt-ref">${esc(s.publicRef)}</span>
      <span class="fwd-tt-chip" style="color:${c};background:${rgba(c, 0.12)};border-color:${rgba(c, 0.35)}">${STATUS_LABEL[s.status]}</span>
    </div>
    <div class="fwd-tt-route">
      <b>${esc(s.origin.iata)}</b><span class="fwd-tt-city">${esc(s.origin.city ?? "")}</span>
      <span class="fwd-tt-arrow">→</span>
      <b>${esc(s.destination.iata)}</b><span class="fwd-tt-city">${esc(s.destination.city ?? "")}</span>
    </div>
    <dl class="fwd-tt-grid">
      <dt>Airline</dt><dd>${esc(s.airlineName ?? "n/a")}</dd>
      <dt>ETA</dt><dd>${esc(formatEta(s).replace(/^ETA /, ""))}</dd>
      <dt>Price</dt><dd>${esc(formatPrice(s))}</dd>
      ${s.bookingReference ? `<dt>Booking</dt><dd class="mono">${esc(s.bookingReference)}</dd>` : ""}
    </dl>
  </div>`;
}
