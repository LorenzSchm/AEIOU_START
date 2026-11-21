// Airport reference points (decimal degrees, 4 dp) from OurAirports (airports.csv,
// https://ourairports.com/data/, public domain; snapshot 2026-11-21).
// `list` is the hand-checked set used by the kit tariffs/carriers/zones; WORLD is the fallback
// with every large airport that has scheduled service and an IATA code.
import WORLD from "./airports-world.json";
// ULN is mapped to the new Chinggis Khaan International Airport (ICAO ZMCK).
const list = [
  ["ASU", "Silvio Pettirossi International", "Asunción", "Paraguay", -25.2402, -57.5192],
  ["ATL", "Hartsfield–Jackson Atlanta International", "Atlanta", "United States", 33.6367, -84.4281],
  ["BKK", "Suvarnabhumi", "Bangkok", "Thailand", 13.6811, 100.747],
  ["BOG", "El Dorado International", "Bogotá", "Colombia", 4.7016, -74.1469],
  ["BOM", "Chhatrapati Shivaji Maharaj International", "Mumbai", "India", 19.0887, 72.8679],
  ["CAI", "Cairo International", "Cairo", "Egypt", 30.1115, 31.3967],
  ["CGN", "Cologne Bonn", "Cologne", "Germany", 50.8659, 7.1427],
  ["DEL", "Indira Gandhi International", "New Delhi", "India", 28.5556, 77.0952],
  ["DFW", "Dallas Fort Worth International", "Dallas–Fort Worth", "United States", 32.8968, -97.038],
  ["DOH", "Hamad International", "Doha", "Qatar", 25.2731, 51.6081],
  ["DUS", "Düsseldorf", "Düsseldorf", "Germany", 51.2895, 6.7668],
  ["DXB", "Dubai International", "Dubai", "United Arab Emirates", 25.2498, 55.371],
  ["EZE", "Ministro Pistarini (Ezeiza)", "Buenos Aires", "Argentina", -34.8222, -58.5358],
  ["FRA", "Frankfurt am Main", "Frankfurt", "Germany", 50.0267, 8.5584],
  ["GRU", "São Paulo/Guarulhos International", "São Paulo", "Brazil", -23.4313, -46.47],
  ["HAM", "Hamburg Helmut Schmidt", "Hamburg", "Germany", 53.6304, 9.9882],
  ["HKG", "Hong Kong International", "Hong Kong", "Hong Kong SAR", 22.3118, 113.9149],
  ["ICN", "Incheon International", "Seoul", "South Korea", 37.4691, 126.451],
  ["JFK", "John F. Kennedy International", "New York", "United States", 40.6394, -73.7793],
  ["JNB", "O.R. Tambo International", "Johannesburg", "South Africa", -26.1401, 28.2468],
  ["KTM", "Tribhuvan International", "Kathmandu", "Nepal", 27.6966, 85.3591],
  ["KUL", "Kuala Lumpur International", "Kuala Lumpur", "Malaysia", 2.7456, 101.71],
  ["LAX", "Los Angeles International", "Los Angeles", "United States", 33.9425, -118.408],
  ["LIM", "Jorge Chávez International", "Lima", "Peru", -12.0219, -77.1143],
  ["LOS", "Murtala Muhammed International", "Lagos", "Nigeria", 6.5774, 3.3212],
  ["MEL", "Melbourne (Tullamarine)", "Melbourne", "Australia", -37.6707, 144.8379],
  ["MEX", "Benito Juárez International", "Mexico City", "Mexico", 19.4358, -99.0703],
  ["MIA", "Miami International", "Miami", "United States", 25.796, -80.2898],
  ["MNL", "Ninoy Aquino International", "Manila", "Philippines", 14.5086, 121.02],
  ["MUC", "Munich", "Munich", "Germany", 48.3538, 11.7861],
  ["NBO", "Jomo Kenyatta International", "Nairobi", "Kenya", -1.3189, 36.9282],
  ["NRT", "Narita International", "Tokyo", "Japan", 35.7686, 140.3887],
  ["ORD", "O'Hare International", "Chicago", "United States", 41.9786, -87.9048],
  ["PEK", "Beijing Capital International", "Beijing", "China", 40.0773, 116.5967],
  ["PPT", "Faʻaʻā International", "Papeete", "French Polynesia", -17.5535, -149.6069],
  ["PVG", "Shanghai Pudong International", "Shanghai", "China", 31.1434, 121.805],
  ["RUH", "King Khalid International", "Riyadh", "Saudi Arabia", 24.9576, 46.6988],
  ["SCL", "Arturo Merino Benítez International", "Santiago", "Chile", -33.3903, -70.7947],
  ["SGN", "Tan Son Nhat International", "Ho Chi Minh City", "Vietnam", 10.8188, 106.652],
  ["SIN", "Singapore Changi", "Singapore", "Singapore", 1.3502, 103.994],
  ["STR", "Stuttgart", "Stuttgart", "Germany", 48.6899, 9.222],
  ["SYD", "Sydney Kingsford Smith", "Sydney", "Australia", -33.9461, 151.177],
  ["TLV", "Ben Gurion", "Tel Aviv", "Israel", 32.0114, 34.8867],
  ["TNR", "Ivato International", "Antananarivo", "Madagascar", -18.7969, 47.4788],
  ["TPE", "Taiwan Taoyuan International", "Taipei", "Taiwan", 25.0777, 121.233],
  ["ULN", "Chinggis Khaan International", "Ulaanbaatar", "Mongolia", 47.6469, 106.8198],
  ["YYZ", "Toronto Pearson International", "Toronto", "Canada", 43.6759, -79.6294],
];

export const AIRPORTS = Object.freeze(
  Object.fromEntries(
    list.map(([code, name, city, country, lat, lng]) => [code, Object.freeze({ code, name, city, country, lat, lng })])
  )
);

/** German export hubs (route origins). */
export const ORIGIN_CODES = ["FRA", "MUC", "DUS", "HAM", "CGN", "STR"];

let regionNames;
try {
  regionNames = new Intl.DisplayNames(["en"], { type: "region" });
} catch {}
const WORLD_INDEX = new Map(WORLD.map((row) => [row[0], row]));
const worldCache = new Map();

/** Look up an airport by IATA code (case-insensitive). Curated list first, then the world list. Null if unknown. */
export function airport(code) {
  if (!code) return null;
  const key = String(code).trim().toUpperCase();
  if (AIRPORTS[key]) return AIRPORTS[key];
  if (worldCache.has(key)) return worldCache.get(key);
  const row = WORLD_INDEX.get(key);
  if (!row) return null;
  const [, name, city, iso, lat, lng] = row;
  const entry = Object.freeze({ code: key, name, city, country: regionNames?.of(iso) ?? iso, lat, lng });
  worldCache.set(key, entry);
  return entry;
}

/** Number of airports the globe can place (curated + world fallback). */
export const AIRPORT_COUNT = new Set([...Object.keys(AIRPORTS), ...WORLD_INDEX.keys()]).size;
