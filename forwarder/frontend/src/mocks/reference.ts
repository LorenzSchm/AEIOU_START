/**
 * Reference data for mock mode, modelled on the start kit: the airline and
 * trucker names are the real fictional carriers from carrier-list.csv.
 */
import type { Carrier, ShipmentPoint } from "@/api/generated/model";

export const AIRPORTS: Record<string, ShipmentPoint & { region: Region }> = {
  FRA: { iata: "FRA", city: "Frankfurt", lat: 50.0379, lon: 8.5622, region: "EU" },
  MUC: { iata: "MUC", city: "Munich", lat: 48.3538, lon: 11.7861, region: "EU" },
  DUS: { iata: "DUS", city: "Düsseldorf", lat: 51.2895, lon: 6.7668, region: "EU" },
  STR: { iata: "STR", city: "Stuttgart", lat: 48.6899, lon: 9.222, region: "EU" },
  CGN: { iata: "CGN", city: "Cologne", lat: 50.8659, lon: 7.1427, region: "EU" },
  HAM: { iata: "HAM", city: "Hamburg", lat: 53.6304, lon: 9.9882, region: "EU" },
  SIN: { iata: "SIN", city: "Singapore", lat: 1.3644, lon: 103.9915, region: "AS" },
  JFK: { iata: "JFK", city: "New York", lat: 40.6413, lon: -73.7781, region: "NA" },
  DXB: { iata: "DXB", city: "Dubai", lat: 25.2532, lon: 55.3657, region: "ME" },
  ORD: { iata: "ORD", city: "Chicago", lat: 41.9742, lon: -87.9073, region: "NA" },
  GRU: { iata: "GRU", city: "São Paulo", lat: -23.4356, lon: -46.4731, region: "SA" },
  HKG: { iata: "HKG", city: "Hong Kong", lat: 22.308, lon: 113.9185, region: "AS" },
  SYD: { iata: "SYD", city: "Sydney", lat: -33.9399, lon: 151.1753, region: "OC" },
  LOS: { iata: "LOS", city: "Lagos", lat: 6.5774, lon: 3.3212, region: "AF" },
  PVG: { iata: "PVG", city: "Shanghai", lat: 31.1443, lon: 121.8083, region: "AS" },
  LAX: { iata: "LAX", city: "Los Angeles", lat: 33.9416, lon: -118.4085, region: "NA" },
  MEX: { iata: "MEX", city: "Mexico City", lat: 19.4361, lon: -99.0719, region: "NA" },
  NRT: { iata: "NRT", city: "Tokyo", lat: 35.772, lon: 140.3929, region: "AS" },
  DEL: { iata: "DEL", city: "Delhi", lat: 28.5562, lon: 77.1, region: "AS" },
  JNB: { iata: "JNB", city: "Johannesburg", lat: -26.1367, lon: 28.2411, region: "AF" },
  ICN: { iata: "ICN", city: "Seoul", lat: 37.4602, lon: 126.4407, region: "AS" },
  YYZ: { iata: "YYZ", city: "Toronto", lat: 43.6777, lon: -79.6248, region: "NA" },
  TLV: { iata: "TLV", city: "Tel Aviv", lat: 32.0055, lon: 34.8854, region: "ME" },
  BKK: { iata: "BKK", city: "Bangkok", lat: 13.69, lon: 100.7501, region: "AS" },
  MIA: { iata: "MIA", city: "Miami", lat: 25.7959, lon: -80.287, region: "NA" },
  NBO: { iata: "NBO", city: "Nairobi", lat: -1.3192, lon: 36.9278, region: "AF" },
};

export type Region = "EU" | "AS" | "NA" | "ME" | "SA" | "AF" | "OC";

/** Typical sheet rate per kg (+100 break) by destination region, EUR. */
export const REGION_RATE: Record<Region, number> = { EU: 1.2, AS: 2.7, NA: 2.4, ME: 2.0, SA: 3.3, AF: 3.0, OC: 3.8 };
export const REGION_TRANSIT: Record<Region, [number, number]> = {
  EU: [1, 2], AS: [2, 5], NA: [2, 4], ME: [1, 3], SA: [3, 6], AF: [3, 7], OC: [4, 7],
};

export const ORIGINS = ["FRA", "MUC", "DUS", "STR", "CGN", "HAM"] as const;
export type Origin = (typeof ORIGINS)[number];

export const PICKUP: { zip: string; city: string; airport: Origin }[] = [
  { zip: "86150", city: "Augsburg", airport: "MUC" },
  { zip: "70173", city: "Stuttgart", airport: "STR" },
  { zip: "64283", city: "Darmstadt", airport: "FRA" },
  { zip: "44135", city: "Dortmund", airport: "DUS" },
  { zip: "20095", city: "Hamburg", airport: "HAM" },
  { zip: "50667", city: "Köln", airport: "CGN" },
  { zip: "93047", city: "Regensburg", airport: "MUC" },
];

/** The ten airlines with a rate sheet in the start kit. Everyone else is portal-only. */
export const SHEET_AIRLINES = new Set([
  "Cobalt Skylift", "Danube Wings Cargo", "Elbflug Air Freight", "Kestrel Airways", "Vega Skyfreight",
  "Wayzata Air Cargo", "Helix Air Freight", "Orion Cargo Air", "Quetzal Air Freight", "Yellowtail Air Freight",
]);

const AIRLINE_ROWS: [string, string][] = [
  ["Aurelia Air Cargo", "CAI EZE LAX LIM MEL MIA NBO ORD RUH SCL SIN TLV YYZ"],
  ["Borealis Freight Airways", "CAI DEL LIM NRT ORD PEK SCL SGN SYD YYZ"],
  ["Cobalt Skylift", "BOG BOM DEL EZE ICN JFK JNB LIM MEL MEX MIA RUH SGN SIN SYD TPE YYZ"],
  ["Danube Wings Cargo", "DXB EZE JNB MEX SGN SYD TPE YYZ"],
  ["Elbflug Air Freight", "BOG DFW DXB HKG LOS MEL MEX MIA ORD PVG SGN SIN SYD TLV"],
  ["Fjordline Air Cargo", "ATL BOM DEL EZE GRU JFK JNB KUL LAX NBO SIN TLV"],
  ["Gannet Airways", "ICN LAX PEK TLV"],
  ["Helix Air Freight", "HKG LAX LOS ORD PVG RUH SCL SGN SYD"],
  ["Ibisair Cargo", "ATL BKK BOG BOM DEL GRU HKG ICN JFK MEX MIA NBO ORD PEK SIN SYD YYZ"],
  ["Juniper Cargo Lines", "DEL EZE GRU MEL NBO RUH SCL SGN SYD TPE YYZ"],
  ["Kestrel Airways", "BOM CAI DEL DFW GRU HKG KUL LAX MEL MEX MIA NRT PEK SIN"],
  ["Lumen Air Cargo", "BOG BOM DFW DXB LOS MIA NRT PEK PVG RUH SGN YYZ"],
  ["Meridian Freightways", "ATL CAI DEL DFW EZE HKG LAX MIA MNL NBO ORD PVG SYD TLV TPE"],
  ["Nova Atlas Cargo", "BKK EZE LIM MEL NRT ORD PVG SGN SIN SYD TPE"],
  ["Orion Cargo Air", "DXB GRU HKG KUL MIA MNL NBO ORD PEK SGN TLV"],
  ["Polaris Air Logistics", "ATL BKK HKG ICN JFK JNB KUL LAX PEK SCL SGN SYD YYZ"],
  ["Quetzal Air Freight", "BOM DFW DXB EZE HKG JFK LIM LOS RUH SCL"],
  ["Riverbend Airlines Cargo", "ATL BOG DFW DOH GRU MEX MIA RUH TLV YYZ"],
  ["Solace Air Cargo", "ATL BOG DEL GRU JFK MEL MEX MIA NRT SYD TLV TPE"],
  ["Tamarind Airways", "BKK DXB EZE HKG JNB LOS MEX MIA MNL NBO PVG RUH SGN TLV YYZ"],
  ["Ultraviolet Air Freight", "BOM DEL DFW DOH EZE GRU JFK LAX MEX MNL NBO RUH SCL SIN SYD TLV YYZ"],
  ["Vega Skyfreight", "DEL DFW KUL LIM LOS MIA MNL NBO ORD PEK SYD YYZ"],
  ["Wayzata Air Cargo", "ATL BOG CAI DEL DOH DXB EZE HKG ICN JFK LAX LIM MEX MIA NBO NRT PEK SCL SGN TLV"],
  ["Xenon Cargo Airlines", "BOG BOM LIM MEX MIA NRT ORD PVG SCL"],
  ["Yellowtail Air Freight", "BKK BOG DFW JFK JNB LAX LIM MEL MEX MNL NBO ORD SGN SYD"],
];

const TRUCKER_ROWS: [string, string][] = [
  ["Rheinufer Spedition", "20 40 42 47 64 70 73"],
  ["Maintal Transport Logistik", "21 24 45 51 65 84 86"],
  ["Alpenblick Logistik", "24 64 72 81"],
  ["Hansekogge Trucking", "20 24 40 50 61 72 73"],
  ["Neckarstrom Kurier", "24 55 65 73 80 81 85 86 93"],
  ["Ruhrpott Express", "22 41 50 53 80 85"],
  ["Isarwelle Spedition", "60 70 80 84 93"],
  ["Elbkante Logistik", "20 35 47 51 55 84 85 93"],
  ["Domblick Kurierdienst", "21 36 40 45 47 60 63 73 85"],
  ["Spessart Spedition", "21 28 42 53 61 70 73"],
];

const slug = (name: string) => name.toLowerCase().replaceAll(" ", "-");
export const airlineCode = (name: string) =>
  name
    .split(" ")
    .slice(0, 2)
    .map((w) => w[0])
    .join("")
    .toUpperCase();

export const CARRIERS: Carrier[] = [
  ...AIRLINE_ROWS.map(([name, serves], i) => ({
    id: i + 1,
    name,
    code: airlineCode(name),
    kind: "airline" as const,
    email: `${slug(name)}@hackloop.mail`,
    serves: serves.split(" "),
  })),
  ...TRUCKER_ROWS.map(([name, serves], i) => ({
    id: 100 + i,
    name,
    code: null,
    kind: "trucker" as const,
    email: `${slug(name)}@hackloop.mail`,
    serves: serves.split(" "),
  })),
];

export interface Customer {
  name: string;
  email: string;
  company: string;
  role: string;
  lang: "en" | "de" | "es";
  register: "formal" | "casual";
}

export const CUSTOMERS: Customer[] = [
  { name: "Katrin Vogel", email: "katrin.vogel@vogel-praezision.de", company: "Vogel Präzisionstechnik GmbH", role: "Head of Logistics", lang: "en", register: "formal" },
  { name: "Thomas Brenner", email: "t.brenner@brenner-medtech.de", company: "Brenner Medizintechnik AG", role: "Versandleitung", lang: "de", register: "formal" },
  { name: "Lucía Fernández", email: "lucia.fernandez@fernandez-import.es", company: "Fernández Importaciones S.L.", role: "Compras", lang: "es", register: "casual" },
  { name: "James Whitfield", email: "james@whitfield-components.co.uk", company: "Whitfield Components Ltd", role: "Operations", lang: "en", register: "casual" },
  { name: "Sabine Krüger", email: "s.krueger@krueger-optik.de", company: "Krüger Optik GmbH", role: "Export", lang: "de", register: "formal" },
  { name: "Marco Rossi", email: "marco.rossi@rossimeccanica.it", company: "Rossi Meccanica S.r.l.", role: "Supply Chain Manager", lang: "en", register: "formal" },
  { name: "Aylin Demir", email: "aylin.demir@demir-textil.de", company: "Demir Textil GmbH", role: "Logistik", lang: "de", register: "casual" },
  { name: "Priya Nair", email: "priya.nair@nairpharma.com", company: "Nair Pharma Logistics", role: "Shipping Coordinator", lang: "en", register: "formal" },
];

export const COMMODITIES: { en: string; de: string; es: string; pkg: string }[] = [
  { en: "CNC machine spare parts", de: "CNC-Maschinenersatzteile", es: "repuestos de maquinaria CNC", pkg: "pallets" },
  { en: "medical devices (non-DG)", de: "Medizinprodukte (kein Gefahrgut)", es: "dispositivos médicos (no peligrosos)", pkg: "cartons" },
  { en: "optical lenses", de: "optische Linsen", es: "lentes ópticas", pkg: "cartons" },
  { en: "textile samples", de: "Textilmuster", es: "muestras textiles", pkg: "cartons" },
  { en: "automotive sensors", de: "Kfz-Sensoren", es: "sensores de automoción", pkg: "pallets" },
  { en: "industrial valves", de: "Industrieventile", es: "válvulas industriales", pkg: "crates" },
  { en: "laboratory equipment", de: "Laborgeräte", es: "equipos de laboratorio", pkg: "crates" },
  { en: "printed circuit boards", de: "Leiterplatten", es: "placas de circuito impreso", pkg: "cartons" },
];

export const PERSONA = { name: "Lena Hartmann", first: "Lena", company: "AEIOU Air Freight" };
export const MAILBOX = "aeiou@hackloop.mail";
export const TRACKING_ADDR = "tracking@hackloop.mail";
export const RATEDESK_ADDR = "ratedesk@hackloop.mail";
export const PORTAL_ADDR = "bookings@hackloop.mail";
