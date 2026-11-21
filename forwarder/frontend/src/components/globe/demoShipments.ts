import type { ActiveShipment, BookingStatus, ShipmentPoint } from "./types";

/** Real airport coordinates (WGS84) for the German origins and kit destinations. */
export const AIRPORTS: Record<string, ShipmentPoint> = {
  FRA: { iata: "FRA", city: "Frankfurt", lat: 50.0379, lon: 8.5622 },
  MUC: { iata: "MUC", city: "Munich", lat: 48.3538, lon: 11.7861 },
  DUS: { iata: "DUS", city: "Düsseldorf", lat: 51.2895, lon: 6.7668 },
  STR: { iata: "STR", city: "Stuttgart", lat: 48.6899, lon: 9.222 },
  CGN: { iata: "CGN", city: "Cologne", lat: 50.8659, lon: 7.1427 },
  HAM: { iata: "HAM", city: "Hamburg", lat: 53.6304, lon: 9.9882 },
  SIN: { iata: "SIN", city: "Singapore", lat: 1.3644, lon: 103.9915 },
  JFK: { iata: "JFK", city: "New York", lat: 40.6413, lon: -73.7781 },
  GRU: { iata: "GRU", city: "São Paulo", lat: -23.4356, lon: -46.4731 },
  NBO: { iata: "NBO", city: "Nairobi", lat: -1.3192, lon: 36.9278 },
  SYD: { iata: "SYD", city: "Sydney", lat: -33.9399, lon: 151.1753 },
  PVG: { iata: "PVG", city: "Shanghai", lat: 31.1443, lon: 121.8083 },
  DXB: { iata: "DXB", city: "Dubai", lat: 25.2532, lon: 55.3657 },
  LAX: { iata: "LAX", city: "Los Angeles", lat: 33.9416, lon: -118.4085 },
  HKG: { iata: "HKG", city: "Hong Kong", lat: 22.308, lon: 113.9185 },
  JNB: { iata: "JNB", city: "Johannesburg", lat: -26.1337, lon: 28.242 },
  MEX: { iata: "MEX", city: "Mexico City", lat: 19.4361, lon: -99.0719 },
  NRT: { iata: "NRT", city: "Tokyo", lat: 35.772, lon: 140.3929 },
  ORD: { iata: "ORD", city: "Chicago", lat: 41.9742, lon: -87.9073 },
  DEL: { iata: "DEL", city: "Delhi", lat: 28.5562, lon: 77.1 },
};

export const GERMAN_HUBS = new Set(["FRA", "MUC", "DUS", "STR", "CGN", "HAM"]);

interface Seed {
  o: string;
  d: string;
  airline: string;
  code: string;
  status: BookingStatus;
  transitDays: number;
  /** Departure offset from "now" in hours (negative = already departed). */
  departedHoursAgo: number | null;
  price: number;
}

// Airlines are taken from start-kit/carrier-list.csv and actually serve the destination.
const SEEDS: Seed[] = [
  { o: "FRA", d: "SIN", airline: "Cobalt Skylift", code: "CS", status: "confirmed", transitDays: 2, departedHoursAgo: 20, price: 6840 },
  { o: "MUC", d: "JFK", airline: "Fjordline Air Cargo", code: "FJ", status: "booked", transitDays: 1, departedHoursAgo: null, price: 3920 },
  { o: "DUS", d: "GRU", airline: "Ibisair Cargo", code: "IB", status: "confirmed", transitDays: 2, departedHoursAgo: 31, price: 5410 },
  { o: "STR", d: "NBO", airline: "Aurelia Air Cargo", code: "AU", status: "confirmed", transitDays: 2, departedHoursAgo: 52, price: 4275 },
  { o: "CGN", d: "SYD", airline: "Danube Wings Cargo", code: "DW", status: "booked", transitDays: 3, departedHoursAgo: 9, price: 9180 },
  { o: "HAM", d: "PVG", airline: "Elbflug Air Freight", code: "EF", status: "confirmed", transitDays: 2, departedHoursAgo: 40, price: 7350 },
  { o: "FRA", d: "DXB", airline: "Lumen Air Cargo", code: "LU", status: "confirmed", transitDays: 1, departedHoursAgo: 30, price: 2860 },
  { o: "MUC", d: "LAX", airline: "Polaris Air Logistics", code: "PA", status: "booked", transitDays: 2, departedHoursAgo: 14, price: 6120 },
  { o: "FRA", d: "HKG", airline: "Kestrel Airways", code: "KA", status: "confirmed", transitDays: 2, departedHoursAgo: 4, price: 5980 },
  { o: "DUS", d: "JNB", airline: "Tamarind Airways", code: "TA", status: "booked", transitDays: 2, departedHoursAgo: -6, price: 4630 },
  { o: "STR", d: "MEX", airline: "Riverbend Airlines Cargo", code: "RB", status: "confirmed", transitDays: 2, departedHoursAgo: 26, price: 5545 },
  { o: "HAM", d: "NRT", airline: "Lumen Air Cargo", code: "LU", status: "booked", transitDays: 2, departedHoursAgo: null, price: 7790 },
  { o: "FRA", d: "ORD", airline: "Helix Air Freight", code: "HX", status: "confirmed", transitDays: 1, departedHoursAgo: 11, price: 3480 },
  { o: "CGN", d: "DEL", airline: "Vega Skyfreight", code: "VS", status: "confirmed", transitDays: 1, departedHoursAgo: 15, price: 3215 },
];

const HOUR = 3_600_000;

/**
 * Builds ~14 realistic shipments relative to `nowMs`, so that in "real time" mode a mix of
 * planes is in the air, landed and awaiting departure.
 */
export function buildDemoShipments(nowMs: number): ActiveShipment[] {
  return SEEDS.map((s, i) => {
    const departure =
      s.departedHoursAgo === null ? null : new Date(nowMs - s.departedHoursAgo * HOUR);
    const eta = departure ? new Date(departure.getTime() + s.transitDays * 24 * HOUR) : null;
    return {
      bookingId: 9000 + i,
      sessionId: 4100 + i * 3,
      publicRef: `FWD-2611-${String(42 + i * 7).padStart(4, "0")}`,
      bookingReference: `5U-${s.code}-${28411 + i * 137}`,
      airlineName: s.airline,
      status: s.status,
      origin: AIRPORTS[s.o],
      destination: AIRPORTS[s.d],
      departureAt: departure?.toISOString() ?? null,
      etaAt: eta?.toISOString() ?? null,
      transitDays: s.transitDays,
      price: s.price.toFixed(2),
      currency: "EUR",
    };
  });
}
