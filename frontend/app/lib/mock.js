// Dev-only fixture shaped like the Spring Boot /api/v1 responses (see backend Ledger.inbox()).
// Enabled with NEXT_PUBLIC_MOCK=1. Never used in production builds without that flag.

const carriers = [
  { name: "Aurelia Air Cargo", kind: "airline", email: "aurelia-air-cargo@hackloop.mail", serves: "CAI EZE LAX LIM MEL MIA NBO ORD RUH SCL SIN TLV YYZ" },
  { name: "Borealis Freight Airways", kind: "airline", email: "borealis-freight-airways@hackloop.mail", serves: "CAI DEL LIM NRT ORD PEK SCL SGN SYD YYZ" },
  { name: "Cobalt Skylift", kind: "airline", email: "cobalt-skylift@hackloop.mail", serves: "BOG BOM DEL EZE ICN JFK JNB LIM MEL MEX MIA RUH SGN SIN SYD TPE YYZ" },
  { name: "Danube Wings Cargo", kind: "airline", email: "danube-wings-cargo@hackloop.mail", serves: "DXB EZE JNB MEX SGN SYD TPE" },
  { name: "Rhein Trucking", kind: "trucker", email: "rhein-trucking@hackloop.mail", serves: "60 61 63 64 80 81" },
];
const zones = { 60: "FRA", 61: "FRA", 63: "FRA", 64: "FRA", 80: "MUC", 81: "MUC", 40: "DUS", 20: "HAM", 50: "CGN", 70: "STR" };

const lanes = [
  ["FRA", "SIN", "cheapest", 420, 2.1, "Aurelia Air Cargo", "portal", "sent", true],
  ["MUC", "JFK", "fastest", 180, 0.9, "Cobalt Skylift", "email bid", "sent", false],
  ["FRA", "SYD", "cheapest", 650, 3.4, "Danube Wings Cargo", "tariff", "sent", false],
  ["DUS", "BOM", "fastest", 310, 1.6, "Cobalt Skylift", "portal", "sent", true],
  ["HAM", "ORD", "cheapest", 95, 0.4, "Aurelia Air Cargo", "email bid", null, false, "waiting for air price"],
  ["CGN", "EZE", "cheapest", 520, 2.8, "Cobalt Skylift", "tariff", "sent", false],
  ["STR", "DXB", "fastest", 240, 1.2, "Danube Wings Cargo", "portal", null, false, "review", "Packing list weight 240 kg differs from email 260 kg."],
  ["FRA", "NRT", "cheapest", 780, 4.1, "Borealis Freight Airways", "email bid", "sent", false],
  ["MUC", "MEX", "fastest", 130, 0.7, "Cobalt Skylift", "email bid", "uncertain", false],
  ["FRA", "JNB", "cheapest", 205, 1.1, "Danube Wings Cargo", "tariff", "sent", false],
  ["DUS", "YYZ", "cheapest", 360, 1.9, null, null, null, false, "waiting for pickup", "Truck rate for zone 40 not confirmed yet."],
  ["FRA", "LIM", "fastest", 145, 0.8, "Aurelia Air Cargo", "portal", null, false, "collecting"],
];

// One "live" request that walks through the whole process after page load (reload to replay):
// received → understood → bids → priced → needs attention → quoted → booked.
const T0 = Date.now();
function liveCase() {
  const t = (Date.now() - T0) / 1000;
  const at = (s) => new Date(T0 + s * 1000).toISOString();
  const id = 900;
  const lane = { origin: "FRA", destination: "TPE", weight_kg: 560, volume_cbm: 2.9, preference: "fastest", pickup_zone: "", pickup_eur: null, pickup_days: 0 };
  const invited = carriers.filter((c) => c.kind === "airline" && c.serves.split(" ").includes("TPE"));
  const out = [];
  const replies = [];
  const bidReq = {};
  if (t >= 7) invited.forEach((c, j) => {
    out.push({ action: "bid:" + id + ":" + c.email, kind: "bid", message_id: 99000 + j, recipient: c.email, state: "sent",
      subject: "RFQ FRA-TPE / AEIOU-900", body: `Dear ${c.name} team,\n\nplease quote 560 kg / 2.9 cbm FRA to TPE.\n\nAEIOU dispatch`, time: at(7 + j * 0.5) });
    bidReq[c.email] = "sent";
  });
  invited.forEach((c, j) => {
    const when = 15 + j * 5;
    if (t < when) return;
    bidReq[c.email] = "priced";
    replies.push({ id: id + 1 + j, root: id, thread_id: id, kind: "carrier", sender: c.email, subject: "Re: RFQ FRA-TPE / AEIOU-900",
      body: `All-in EUR ${1890 + j * 70}, transit ${2 + j} days.`, received: at(when), stage: "bid priced", review_reason: null, fields: null,
      attachments: [], proposal: null, booking: null, outgoing: [], delivery: null, reply_delivery: null, bid_requests: {} });
  });
  const offer = { airline: invited[0].name, source: "portal", days: 2, total_eur: 1890, rate_id: "RT-LIVE-900" };
  const priced = t >= 26;
  const review = t >= 30 && t < 44;
  const quoted = t >= 47;
  if (quoted) out.push({ action: "quote:" + id, kind: "quote", message_id: 99100, recipient: null, state: "sent",
    subject: "Re: Air export FRA-TPE", body: "Dear customer,\n\nall-in EUR 1,890 FRA to TPE, 2 days.\n\nAEIOU", time: at(47) });
  const booked = t >= 62;
  const request = {
    id, root: null, thread_id: id, kind: "request", sender: "ops@live-demo.example",
    subject: "LIVE · Air export FRA-TPE 560 kg (fastest)", body: "Please quote fastest FRA to TPE, 560 kg, 2.9 cbm.",
    received: at(0), stage: booked ? "booked" : quoted ? "sent" : review ? "review" : t >= 4 ? "collecting" : "new",
    review_reason: review ? "Packing list volume 3.4 m³ differs from email 2.9 m³." : null,
    fields: t >= 4 ? lane : null, attachments: [],
    proposal: priced ? { revision: "live", offer, notes: [], body: "Dear customer, all-in EUR 1,890.",
      alternatives: [offer, ...invited.slice(1).map((c, j) => ({ airline: c.name, source: "email bid", days: 3 + j, total_eur: 1960 + j * 70 }))] } : null,
    booking: booked ? { booking_reference: "5U-BK-LIVE", rate_id: offer.rate_id } : null,
    outgoing: out, delivery: quoted ? "sent" : null, reply_delivery: null, bid_requests: bidReq,
  };
  return [request, ...replies];
}

function iso(msAgo) {
  return new Date(Date.now() - msAgo).toISOString();
}

export function mockData() {
  const messages = [];
  let id = 500;
  lanes.forEach(([origin, destination, preference, kg, cbm, airline, source, delivery, booked, stage, reason], i) => {
    const age = (lanes.length - i) * 55_000 + 20_000;
    const rid = ++id;
    const received = iso(age);
    const invited = carriers.filter((c) => c.kind === "airline" && c.serves.split(" ").includes(destination)).slice(0, 3);
    const total = Math.round(kg * (2.1 + (i % 4) * 0.35) + 180);
    const offer = airline && {
      airline, source, days: 1.5 + (i % 5) * 0.75, total_eur: total,
      rate_id: source === "portal" ? "RT-" + (9100 + i) : null,
    };
    const outgoing = invited.map((c, j) => ({
      action: "bid:" + rid + ":" + c.email, kind: "bid", message_id: 9000 + rid * 10 + j,
      recipient: c.email, state: "sent", subject: "RFQ " + origin + "-" + destination + " / AEIOU-" + rid,
      body: `Dear ${c.name} team,\n\nplease quote ${kg} kg / ${cbm} cbm general cargo ${origin} to ${destination}.\nReply with all-in EUR and transit days.\n\nAEIOU dispatch`,
      time: iso(age - 4000 - j * 600),
    }));
    if (delivery) outgoing.push({
      action: "quote:" + rid, kind: "quote", message_id: 9000 + rid * 10 + 9, recipient: null,
      state: delivery === "uncertain" ? "uncertain" : "sent", subject: "Re: Air export quote " + origin + "-" + destination,
      body: `Dear customer,\n\nall-in EUR ${total} for ${kg} kg ${origin} to ${destination} with ${airline}.\nTransit ${offer.days} days.\n\nKind regards\nAEIOU`,
      time: iso(age - 38_000),
    });
    const bidRequests = {};
    invited.forEach((c, j) => (bidRequests[c.email] = j === 0 || delivery ? "priced" : "sent"));
    messages.push({
      id: rid, root: null, thread_id: rid, kind: "request",
      sender: "logistics@customer" + (i % 4) + ".example",
      subject: `Air export ${origin}-${destination} ${kg} kg (${preference})`,
      body: `Hello,\n\nplease quote ${preference} air freight from ${origin} to ${destination}.\n${kg} kg, ${cbm} cbm, 3 pallets, general cargo.\n${i % 3 === 0 ? "Pickup at our warehouse, postcode 6" + (1000 + i) + "." : ""}\n\nThanks`,
      received, stage: stage || (booked ? "booked" : delivery ? "sent" : "new"),
      review_reason: reason || null,
      fields: { origin, destination, weight_kg: kg, volume_cbm: cbm, preference, pickup_zone: i % 3 === 0 ? String(60 + (i % 4)) : "", pickup_eur: i % 3 === 0 ? 85 : null, pickup_days: i % 3 === 0 ? 0.5 : 0 },
      attachments: i === 6 ? [{ id: 1, filename: "packing-list.xlsx" }] : [],
      proposal: offer ? {
        revision: "r" + rid, offer, notes: i === 4 ? ["Two airlines have not answered yet."] : [],
        alternatives: [offer, ...invited.slice(1).map((c, j) => ({ airline: c.name, source: "email bid", days: offer.days + 0.5 + j, total_eur: total + 40 + j * 65 }))],
        body: outgoing.at(-1)?.body || "Draft quote", pickup_eur: i % 3 === 0 ? 85 : null, trucker: i % 3 === 0 ? "Rhein Trucking" : null,
      } : null,
      booking: booked ? { booking_reference: "5U-BK-" + (7300 + i), rate_id: offer.rate_id } : null,
      outgoing, delivery, reply_delivery: null, bid_requests: bidRequests,
    });
    invited.forEach((c, j) => {
      if (!(j === 0 || delivery)) return;
      messages.push({
        id: ++id, root: rid, thread_id: rid, kind: "carrier", sender: c.email,
        subject: "Re: RFQ " + origin + "-" + destination + " / AEIOU-" + rid,
        body: `All-in EUR ${total + j * 40}, transit ${(offer?.days ?? 2) + j} days. Valid 30 min.`,
        received: iso(age - 15_000 - j * 4000), stage: "bid priced", review_reason: null, fields: null,
        attachments: [], proposal: null, booking: null, outgoing: [], delivery: null, reply_delivery: null, bid_requests: {},
      });
    });
  });
  messages.push({
    id: ++id, root: null, thread_id: id, kind: "tracking", sender: "tracking@5u.example",
    subject: "Where is shipment AEIOU-501?", body: "Hi, can you share the status of AEIOU-501 FRA-SIN?",
    received: iso(30_000), stage: "tracking review", review_reason: null, fields: null, attachments: [],
    proposal: null, booking: null, outgoing: [], delivery: null, reply_delivery: null, bid_requests: {},
  });
  messages.push(...liveCase());
  messages.sort((a, b) => b.id - a.id);
  return {
    status: { last_poll: new Date().toISOString(), tariffs: 158, auto_send: true, start_at: "2026-11-21T15:00:00Z", error: null },
    messages,
    directory: { carriers, zones },
  };
}
