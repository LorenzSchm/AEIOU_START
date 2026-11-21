/** Builds three hours of history on the virtual clock, then keeps traffic flowing in real time. */
import type { TariffDetail, TariffLane } from "@/api/generated/model";
import { CARRIERS, CUSTOMERS, ORIGINS, SHEET_AIRLINES, airlineCode } from "./reference";
import { db, between, intBetween, money2, next, pick, rand, sleep } from "./store";
import { randomSpec, rfqStory, tariffStory, trackingStory, type RfqSpec } from "./stories";

const MIN = 60_000;
const byName = (n: string) => CUSTOMERS.find((c) => c.name.startsWith(n)) ?? CUSTOMERS[0];

function lanesFor(airline: string, scale = 1): TariffLane[] {
  const serves = CARRIERS.find((c) => c.name === airline)?.serves ?? [];
  const lanes: TariffLane[] = [];
  for (const dest of serves.slice(0, 8)) {
    for (const origin of ORIGINS.filter(() => rand() < 0.4).slice(0, 2)) {
      let rate = between(3.6, 6.2) * scale;
      const breaks = [0, 45, 100, 250, 500, 1000, 2000].map((fromKg, i) => {
        // Rates usually fall with weight, but not always: the start-kit trap.
        if (i > 0) rate = rand() < 0.12 ? rate : rate * between(0.74, 0.92);
        return { fromKg, rate: money2(rate) };
      });
      lanes.push({
        origin,
        destination: dest,
        transitDays: intBetween(2, 7),
        minimum: money2(between(68, 132)),
        breaks,
        fuelPerKg: money2(between(0.62, 1.15)),
        securityPerKg: money2(between(0.1, 0.22)),
      });
    }
  }
  return lanes;
}

function seedTariffs(): number {
  const now = db().clock.now();
  const created = new Date(now - 26 * 3600_000).toISOString();
  const kit = [...SHEET_AIRLINES].map((name): TariffDetail => {
    const lanes = lanesFor(name);
    return {
      id: next("tariff"),
      airlineName: name,
      airlineCode: airlineCode(name),
      version: "2026-11",
      source: "kit",
      validFrom: "2026-11-01",
      validTo: "2026-11-30",
      currency: "EUR",
      status: "active",
      laneCount: lanes.length,
      notes: name === "Danube Wings Cargo" ? "Parsed from two JPG pages via vision model, checked by hand." : null,
      createdAt: created,
      lanes,
    };
  });
  const helixOld = lanesFor("Helix Air Freight", 1.04);
  const superseded: TariffDetail = {
    id: next("tariff"),
    airlineName: "Helix Air Freight",
    airlineCode: airlineCode("Helix Air Freight"),
    version: "2026-10",
    source: "kit",
    validFrom: "2026-10-01",
    validTo: "2026-10-31",
    currency: "EUR",
    status: "superseded",
    laneCount: helixOld.length,
    notes: null,
    createdAt: new Date(now - 30 * 86_400_000).toISOString(),
    lanes: helixOld,
  };
  const kestrelDec = lanesFor("Kestrel Airways", 1.18);
  const pending: TariffDetail = {
    id: next("tariff"),
    airlineName: "Kestrel Airways",
    airlineCode: airlineCode("Kestrel Airways"),
    version: "2026-12",
    source: "mail",
    validFrom: "2026-12-01",
    validTo: "2026-12-31",
    currency: "EUR",
    status: "pending_review",
    laneCount: kestrelDec.length,
    notes: "3 lanes moved more than 50% against 2026-11 (FRA-HKG +62%, MUC-NRT −55%, DUS-DEL +71%).",
    createdAt: new Date(now - 95 * MIN).toISOString(),
    lanes: kestrelDec,
  };
  db().tariffs = [...kit, superseded, pending];
  return pending.id;
}

function seedSettings() {
  const at = new Date(db().clock.now() - 5 * 3600_000).toISOString();
  db().settings = [
    { key: "bidWaitSeconds", value: 90, updatedAt: at, updatedBy: "system" },
    { key: "routerConfidenceThreshold", value: 0.75, updatedAt: at, updatedBy: "system" },
    { key: "autopilot", value: true, updatedAt: at, updatedBy: "system" },
    { key: "bidPolicy", value: "balanced_50_50", updatedAt: at, updatedBy: "system" },
    { key: "writerPersonaName", value: "Lena Hartmann", updatedAt: at, updatedBy: "system" },
    { key: "writerCompany", value: "AEIOU Air Freight", updatedAt: at, updatedBy: "system" },
    { key: "models", value: { router: "gpt-4o-mini", writer: "gpt-4o-mini", tariff: "gpt-4.1-mini" }, updatedAt: at, updatedBy: "system" },
    { key: "portalRateLimitPerMinute", value: 30, updatedAt: at, updatedBy: "system" },
  ];
}

const HISTORY: [number, Partial<RfqSpec>][] = [
  [-180, { customer: byName("Katrin"), origin: "FRA", dest: "SIN", door: false, kg: 340, cbm: 1.8, pieces: 3, commodity: 0, mode: "cheapest", outcome: "booked", quoteAfterS: 74 }],
  [-166, { customer: byName("Thomas"), origin: "MUC", dest: "JFK", door: false, kg: 128, cbm: 0.6, pieces: 2, commodity: 1, mode: "fastest", outcome: "rejected", quoteAfterS: 98 }],
  [-152, { customer: byName("Lucía"), origin: "DUS", dest: "DXB", door: true, kg: 520, cbm: 2.9, pieces: 4, commodity: 3, mode: "cheapest", outcome: "booked", quoteAfterS: 88 }],
  [-128, { customer: byName("Marco"), origin: "HAM", dest: "HKG", door: false, kg: 860, cbm: 4.1, pieces: 6, commodity: 5, mode: "fastest", outcome: "quoted", quoteAfterS: 63 }],
  [-116, { customer: byName("Sabine"), origin: "STR", dest: "ORD", door: true, kg: 210, cbm: 1.6, pieces: 2, commodity: 2, mode: "cheapest", outcome: "booked", quoteAfterS: 101 }],
  [-104, { customer: byName("James"), origin: "CGN", dest: "LOS", door: false, kg: 95, cbm: 0.4, pieces: 1, commodity: 6, mode: "cheapest", outcome: "expired", quoteAfterS: 100, portal503: true }],
  [-88, { customer: byName("Priya"), origin: "FRA", dest: "GRU", door: false, kg: 1240, cbm: 6.2, pieces: 8, commodity: 1, mode: "fastest", outcome: "booked", quoteAfterS: 142 }],
  [-74, { customer: byName("Aylin"), origin: "MUC", dest: "SYD", door: false, kg: 310, cbm: 2.4, pieces: 5, commodity: 3, mode: "cheapest", outcome: "rejected", quoteAfterS: 79 }],
  [-62, { customer: byName("James"), origin: "DUS", dest: "NRT", door: false, kg: 64, cbm: 0.3, pieces: 1, commodity: 7, mode: "fastest", outcome: "booked", quoteAfterS: 57 }],
  [-44, { customer: byName("Katrin"), origin: "FRA", dest: "DEL", door: false, kg: 455, cbm: 2.2, pieces: 4, commodity: 4, mode: "cheapest", outcome: "quoted", quoteAfterS: 69 }],
  [-32, { customer: byName("Lucía"), origin: "HAM", dest: "TLV", door: false, kg: 180, cbm: 1.1, pieces: 2, commodity: 2, mode: "cheapest", outcome: "booked", quoteAfterS: 83 }],
  [-21, { customer: byName("James"), origin: "MUC", dest: "ICN", door: true, kg: 720, cbm: 3.6, pieces: 6, commodity: 4, mode: "cheapest", outcome: "booked", quoteAfterS: 90, ambiguous: true }],
  [-12, { customer: byName("Priya"), origin: "FRA", dest: "SYD", door: false, kg: 260, cbm: 1.5, pieces: 3, commodity: 1, mode: "cheapest", outcome: "booked", quoteAfterS: 70, manual: true }],
  [-6, { customer: byName("Marco"), origin: "MUC", dest: "PVG", door: false, kg: 980, cbm: 5.3, pieces: 7, commodity: 0, mode: "cheapest", outcome: "booked", quoteAfterS: 95 }],
  [-3.2, { customer: byName("Sabine"), origin: "STR", dest: "MEX", door: true, kg: 410, cbm: 2.7, pieces: 3, commodity: 5, mode: "cheapest", outcome: "booked", quoteAfterS: 255 }],
  [-0.7, { customer: byName("Aylin"), origin: "FRA", dest: "BKK", door: false, kg: 150, cbm: 0.9, pieces: 2, commodity: 3, mode: "fastest", outcome: "booked", quoteAfterS: 78 }],
];

async function at(offsetMin: number, start: number, run: () => Promise<void>) {
  await sleep(Date.now() + offsetMin * MIN - start);
  await run();
}

async function liveTraffic() {
  await sleep(25_000);
  for (let i = 0; ; i++) {
    void rfqStory(randomSpec({ ambiguous: rand() < 0.08 }));
    if (i % 4 === 3) {
      await sleep(between(20_000, 35_000));
      const confirmed = db().bookings.filter((b) => b.status === "confirmed");
      if (confirmed.length) void trackingStory(pick(confirmed).id);
    }
    await sleep(between(70_000, 110_000));
  }
}

export function ensureSeeded(): Promise<void> {
  const d = db();
  if (d.ready) return d.ready;
  d.ready = (async () => {
    const start = Date.now() - 185 * MIN;
    d.clock.startVirtual(start);
    d.carriers = CARRIERS;
    seedSettings();
    const pendingTariff = seedTariffs();

    for (const [offset, spec] of HISTORY) void at(offset, start, () => rfqStory(randomSpec(spec)));
    void at(-140, start, () => trackingStory(1));
    void at(-50, start, () => trackingStory());
    void at(-95, start, () => tariffStory(pendingTariff));

    await d.clock.catchUp();
    void liveTraffic();
  })();
  return d.ready;
}
