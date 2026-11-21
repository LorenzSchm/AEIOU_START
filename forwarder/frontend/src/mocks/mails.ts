/** Mail texts for mock mode, written the way customers, carriers and our desk actually write. */
import { format } from "date-fns";
import { AIRPORTS, COMMODITIES, PERSONA, type Customer } from "./reference";

export interface RfqFacts {
  customer: Customer;
  origin: string;
  dest: string;
  pickup: { zip: string; city: string } | null;
  kg: number;
  cbm: number;
  pieces: number;
  commodity: number;
  mode: "cheapest" | "fastest";
  readyDate: Date;
}

const city = (iata: string) => AIRPORTS[iata]?.city ?? iata;
const first = (c: Customer) => c.name.split(" ")[0];
const num = (n: number, lang: string) => n.toLocaleString(lang === "en" ? "en-GB" : "de-DE", { maximumFractionDigits: 2 });
const day = (d: Date, lang: string) =>
  lang === "de" ? format(d, "dd.MM.yyyy") : lang === "es" ? format(d, "dd/MM/yyyy") : format(d, "EEEE, d MMMM");

export function rfqSubject(f: RfqFacts): string {
  const { lang, register } = f.customer;
  if (lang === "de") return `Anfrage Luftfracht ${f.pickup ? f.pickup.city : f.origin} – ${f.dest}, ${num(f.kg, lang)} kg`;
  if (lang === "es") return `Cotización envío aéreo ${f.origin}-${f.dest}`;
  return register === "casual"
    ? `Quote please: ${f.origin} to ${city(f.dest)}`
    : `Air freight quote request ${city(f.origin)} – ${city(f.dest)} (${num(f.kg, lang)} kg)`;
}

export function rfqBody(f: RfqFacts): string {
  const c = f.customer;
  const com = COMMODITIES[f.commodity];
  const pickupEn = f.pickup ? `door pickup at ${f.pickup.zip} ${f.pickup.city}` : `delivered to ${city(f.origin)} airport (${f.origin})`;
  if (c.lang === "de") {
    return `Guten Tag,

bitte senden Sie uns ein Angebot für folgende Luftfrachtsendung:

${f.pickup ? `Abholung: ${f.pickup.zip} ${f.pickup.city}` : `Anlieferung: Flughafen ${city(f.origin)} (${f.origin})`}
Ziel: ${city(f.dest)} (${f.dest})
${f.pieces} Colli, ${num(f.kg, "de")} kg, ${num(f.cbm, "de")} m³
Ware: ${com.de}
Abholbereit ab ${day(f.readyDate, "de")}

${f.mode === "fastest" ? "Die Ware wird dringend benötigt, bitte die schnellste Verbindung anbieten." : "Die Laufzeit ist nicht kritisch, wir brauchen die günstigste Variante."}

${c.register === "casual" ? "Danke und viele Grüße" : "Vielen Dank und freundliche Grüße"}
${c.name}
${c.role}
${c.company}`;
  }
  if (c.lang === "es") {
    return `Hola,

necesitamos cotización para un envío aéreo:

- origen: ${f.pickup ? `recogida en ${f.pickup.zip} ${f.pickup.city}` : `aeropuerto de ${city(f.origin)} (${f.origin})`}
- destino: ${city(f.dest)} (${f.dest})
- ${f.pieces} bultos, ${num(f.kg, "es")} kg, ${num(f.cbm, "es")} m3
- mercancía: ${com.es}
- lista el ${day(f.readyDate, "es")}

${f.mode === "fastest" ? "Lo necesitamos lo antes posible, así que la opción más rápida, por favor." : "Buscamos la opción más económica, el tránsito no corre prisa."}

Gracias,
${c.name}
${c.company}`;
  }
  if (c.register === "casual") {
    return `Hi there,

could you quote us air freight from ${f.pickup ? `${f.pickup.city} (${f.pickup.zip}, pickup needed)` : f.origin} to ${city(f.dest)}? It's ${f.pieces} ${com.pkg}, ${num(f.kg, "en")} kg in total, roughly ${num(f.cbm, "en")} cbm. ${com.en.charAt(0).toUpperCase() + com.en.slice(1)}, nothing dangerous. Ready from ${day(f.readyDate, "en")}.

${f.mode === "fastest" ? "Speed matters on this one, so whatever gets there first." : "Cheapest option is fine, no rush on transit."}

Cheers,
${first(c)}
${c.company}`;
  }
  return `Dear AEIOU team,

we would like to receive your offer for the following air export shipment:

Pickup: ${pickupEn}
Destination airport: ${city(f.dest)} (${f.dest})
Pieces: ${f.pieces} ${com.pkg}
Gross weight: ${num(f.kg, "en")} kg
Volume: ${num(f.cbm, "en")} cbm
Commodity: ${com.en}
Ready for collection: ${day(f.readyDate, "en")}

${f.mode === "fastest" ? "The goods are needed on site urgently, so please quote the fastest available routing." : "Transit time is not critical, so please quote the most economical routing."}

Kind regards,
${c.name}
${c.role}
${c.company}`;
}

export function ambiguousBody(f: RfqFacts): string {
  return `Hi Lena,

as discussed on the phone this morning: same as last time, but this round it's ${f.pieces} ${COMMODITIES[f.commodity].pkg} more, about ${num(f.kg, "en")} kg altogether. Can you also check whether you could pick it up from our warehouse instead of us dropping it at ${f.origin}?

Need it in ${city(f.dest)} by end of next week.

Thanks,
${first(f.customer)}`;
}

export function quoteBody(
  f: RfqFacts,
  q: { total: string; currency: string; airline: string; transit: number; truck: boolean },
): string {
  const c = f.customer;
  const totalStr = Number(q.total).toLocaleString(c.lang === "en" ? "en-GB" : "de-DE", { minimumFractionDigits: 2 });
  if (c.lang === "de") {
    return `Hallo ${c.register === "formal" ? c.name : first(c)},

vielen Dank für Ihre Anfrage. Für die ${f.pieces} Colli (${num(f.kg, "de")} kg, ${num(f.cbm, "de")} m³) von ${f.pickup ? f.pickup.city : city(f.origin)} nach ${city(f.dest)} können wir Ihnen anbieten:

${totalStr} ${q.currency} all-in${q.truck ? ", inklusive Abholung" : ""}

Geflogen wird mit ${q.airline}, die Laufzeit beträgt etwa ${q.transit} Tage. Wenn das für Sie passt, antworten Sie einfach auf diese Mail, dann buchen wir sofort.

Viele Grüße
${PERSONA.name}
${PERSONA.company}`;
  }
  if (c.lang === "es") {
    return `Hola ${first(c)},

gracias por tu consulta. Para los ${f.pieces} bultos (${num(f.kg, "es")} kg, ${num(f.cbm, "es")} m3) de ${f.pickup ? f.pickup.city : city(f.origin)} a ${city(f.dest)} te podemos ofrecer:

${totalStr} ${q.currency} todo incluido${q.truck ? ", con recogida" : ""}

Vuela con ${q.airline}, unos ${q.transit} días de tránsito. Si te encaja, responde a este correo y lo reservamos enseguida.

Un saludo,
${PERSONA.name}
${PERSONA.company}`;
  }
  if (c.register === "casual") {
    return `Hi ${first(c)},

thanks for sending this over. For the ${f.pieces} ${COMMODITIES[f.commodity].pkg} (${num(f.kg, "en")} kg) to ${city(f.dest)} the best I can do is:

${q.currency} ${totalStr} all-in${q.truck ? ", pickup included" : ""}

That's with ${q.airline}, roughly ${q.transit} days door to airport. Let me know and I'll get it booked straight away.

Best,
${PERSONA.first}
${PERSONA.company}`;
  }
  return `Dear ${c.name},

thank you for your request. For your ${f.pieces} ${COMMODITIES[f.commodity].pkg} (${num(f.kg, "en")} kg, ${num(f.cbm, "en")} cbm) from ${f.pickup ? f.pickup.city : city(f.origin)} to ${city(f.dest)} we can offer:

${q.currency} ${totalStr} all-in${q.truck ? ", including pickup" : ", airport to airport"}

The shipment would fly with ${q.airline}, with a transit time of around ${q.transit} days. If this works for you, simply reply to this mail and we will book it right away.

Kind regards,
${PERSONA.name}
${PERSONA.company}`;
}

export function acceptanceBody(f: RfqFacts): string {
  const c = f.customer;
  if (c.lang === "de") return `Hallo Frau Hartmann,\n\ndas passt, bitte buchen Sie.\n\nDanke und Gruß\n${c.name}`;
  if (c.lang === "es") return `Perfecto, adelante con la reserva.\n\nGracias,\n${first(c)}`;
  return c.register === "casual"
    ? `Great, go ahead and book it please.\n\n${first(c)}`
    : `Dear Ms Hartmann,\n\nthank you, we accept your offer. Please proceed with the booking.\n\nKind regards,\n${c.name}`;
}

export function bookedBody(f: RfqFacts, b: { ref: string; airline: string; departure: Date; eta: Date }): string {
  const c = f.customer;
  if (c.lang === "de") {
    return `Hallo ${c.register === "formal" ? c.name : first(c)},

erledigt: Ihre Sendung ist bei ${b.airline} gebucht, Buchungsnummer ${b.ref}. Abflug ist am ${format(b.departure, "dd.MM.")}, die Ankunft in ${city(f.dest)} erwarten wir am ${format(b.eta, "dd.MM.")}.

Ich melde mich, sobald die Bestätigung der Airline da ist.

Viele Grüße
${PERSONA.name}`;
  }
  if (c.lang === "es") {
    return `Hola ${first(c)},

listo: el envío está reservado con ${b.airline}, referencia ${b.ref}. Sale el ${format(b.departure, "dd/MM")} y llega a ${city(f.dest)} hacia el ${format(b.eta, "dd/MM")}.

Te aviso en cuanto la aerolínea lo confirme.

Un saludo,
${PERSONA.name}`;
  }
  return `Hi ${first(c)},

all done: your shipment is booked with ${b.airline} under ${b.ref}. It departs on ${format(b.departure, "EEE d MMM")} and should land in ${city(f.dest)} on ${format(b.eta, "EEE d MMM")}.

I'll let you know as soon as the airline confirms.

Best regards,
${PERSONA.name}`;
}

export function bidRequestBody(carrier: string, f: RfqFacts): string {
  return `Hello ${carrier} team,

could you give us your best all-in rate for ${num(f.kg, "en")} kg / ${num(f.cbm, "en")} cbm (${f.pieces} ${COMMODITIES[f.commodity].pkg}, ${COMMODITIES[f.commodity].en}) from ${f.origin} to ${f.dest}, ready ${format(f.readyDate, "d MMM")}?

Price and transit time would be great within the next couple of minutes if you can.

Thanks a lot,
${PERSONA.name}
${PERSONA.company}`;
}

export function bidReplyBody(carrier: string, f: RfqFacts, price: string, transit: number): string {
  return `Hi Lena,

for ${f.origin}-${f.dest}, ${num(f.kg, "en")} kg we can offer EUR ${price} all-in, transit ${transit} days, valid until end of day.

Best regards,
Pricing desk
${carrier}`;
}

export function bidDeclineBody(carrier: string): string {
  return `Hi Lena,

sorry, no capacity on that lane this week, we have to pass on this one.

Regards,
${carrier}`;
}

export function truckRequestBody(f: RfqFacts): string {
  return `Hallo zusammen,

könnt ihr uns eine Abholung in ${f.pickup?.zip} ${f.pickup?.city} zum Flughafen ${f.origin} anbieten? ${f.pieces} Colli, ${num(f.kg, "de")} kg, abholbereit ab ${format(f.readyDate, "dd.MM.")}.

Danke und Gruß
${PERSONA.first}`;
}

export function truckReplyBody(trucker: string, f: RfqFacts, price: string): string {
  return `Hallo ${PERSONA.first},

Abholung ${f.pickup?.zip} → ${f.origin} machen wir für ${price} EUR, Zustellung am selben Tag.

Gruß
Disposition ${trucker}`;
}

export function trackingQuestion(ref: string, bookingRef: string, dest: string): string {
  return `Hello,

could you tell me the current status of our shipment ${bookingRef} (your reference ${ref})? When is it expected to arrive in ${city(dest)}?

Thank you`;
}

export function trackingAnswer(b: { ref: string; airline: string; origin: string; dest: string; departure: Date; eta: Date; confirmed: boolean }): string {
  return `Hello,

booking ${b.ref} is ${b.confirmed ? "confirmed" : "booked"} with ${b.airline}. It departs ${b.origin} on ${format(b.departure, "EEE d MMM")} and is expected in ${city(b.dest)} on ${format(b.eta, "EEE d MMM")}.

If anything changes I'll let you know straight away.

Best regards,
${PERSONA.name}
${PERSONA.company}`;
}

export function bookingConfirmedBody(ref: string, team: string, airline: string): string {
  return `Booking ${ref} confirmed.

Team reference: ${team}
Airline: ${airline}

This is an automated message.`;
}

export function tariffMailBody(airline: string): string {
  return `Dear partners,

please find attached our updated general cargo tariff valid from 1 December 2026. All rates are per kg of chargeable weight in EUR; surcharges as before.

Kind regards,
Revenue Management
${airline}`;
}
