// Mirrors API_CONTRACT.md exactly (`ShipmentPoint`, `ActiveShipment`).
// TODO(integration): replace with the orval-generated types from `src/api/generated`.

export type BookingStatus = "requested" | "booked" | "confirmed" | "failed";

export interface ShipmentPoint {
  iata: string;
  city: string | null;
  lat: number;
  lon: number;
}

export interface ActiveShipment {
  bookingId: number;
  sessionId: number;
  publicRef: string;
  bookingReference: string | null;
  airlineName: string | null;
  status: BookingStatus;
  origin: ShipmentPoint;
  destination: ShipmentPoint;
  departureAt: string | null;
  etaAt: string | null;
  transitDays: number | null;
  price: string | null;
  currency: string | null;
}

export type TimeMode = "real" | "demo";

export type FlightPhase = "awaiting" | "air" | "landed";
