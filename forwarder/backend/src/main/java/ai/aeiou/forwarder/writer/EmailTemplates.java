package ai.aeiou.forwarder.writer;

import ai.aeiou.forwarder.writer.EmailWriter.MailIntent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;

/**
 * Human-written fallback mails per purpose in German and English (other languages get English). Used when the model
 * fails twice or the gateway is down, so they have to read as well as a real person's mail on a busy day.
 */
final class EmailTemplates {

    record Draft(String subject, String body) {}

    private EmailTemplates() {}

    static Draft render(MailIntent intent, String language, RandomGenerator random) {
        boolean de = "de".equals(language);
        Map<String, Object> f = intent.facts() == null ? Map.of() : intent.facts();
        Text t = new Text(f, de);
        List<String> paragraphs = new ArrayList<>();
        String subject;
        String lane = t.lane();
        String shipment = t.shipment();

        switch (intent.purpose()) {
            case QUOTE -> {
                subject = de ? "Angebot " + lane : "Quote " + lane;
                String price = t.price();
                paragraphs.add(de
                        ? pick(random, "danke für Ihre Anfrage" + t.about() + ".", "vielen Dank für die Details" + t.about() + ".")
                        + " Für " + lane + " kann ich Ihnen " + t.or(Facts.AIRLINE, "eine Direktverbindung") + " für "
                        + price + " all-in anbieten" + t.transitDe() + "."
                        : pick(random, "thanks for sending this through" + t.about() + ".", "thanks for the details" + t.about() + ".")
                        + " For " + lane + " I can offer you " + t.or(Facts.AIRLINE, "a direct service") + " at "
                        + price + " all-in" + t.transitEn() + ".");
                if (t.has(Facts.VALID_UNTIL)) {
                    paragraphs.add(de ? "Das Angebot gilt bis " + t.get(Facts.VALID_UNTIL) + "."
                            : "The rate is valid until " + t.get(Facts.VALID_UNTIL) + ".");
                }
                paragraphs.add(de ? "Geben Sie mir einfach kurz Bescheid, dann buche ich."
                        : "Just reply to confirm and I'll book it.");
            }
            case BOOKING_CONFIRMATION -> {
                subject = de ? "Buchungsbestätigung " + t.get(Facts.BOOKING_REFERENCE) : "Booking confirmed " + t.get(Facts.BOOKING_REFERENCE);
                paragraphs.add(de
                        ? "Ihre Sendung ist gebucht: " + t.or(Facts.AIRLINE, "Airline") + ", " + lane + ", Buchungsnummer "
                        + t.get(Facts.BOOKING_REFERENCE) + t.priceSuffix(" all-in") + "."
                        : "you're all booked. Your shipment flies with " + t.or(Facts.AIRLINE, "the airline") + " on " + lane
                        + " under booking " + t.get(Facts.BOOKING_REFERENCE) + t.priceSuffix(" all-in") + ".");
                if (t.has(Facts.DEPARTURE)) {
                    paragraphs.add(de ? "Abflug ist für " + t.get(Facts.DEPARTURE) + " geplant" + t.etaDe() + "."
                            : "Departure is planned for " + t.get(Facts.DEPARTURE) + t.etaEn() + ".");
                }
                paragraphs.add(de ? "Ich behalte die Sendung im Blick und melde mich, falls sich etwas ändert."
                        : "I'll keep an eye on it and let you know if anything changes.");
            }
            case TRACKING_ANSWER -> {
                subject = "Status " + t.get(Facts.BOOKING_REFERENCE);
                String head = de
                        ? "hier der aktuelle Stand zu " + t.or(Facts.BOOKING_REFERENCE, "Ihrer Sendung") + " (" + t.or(Facts.AIRLINE, "") + ", " + lane + "):"
                        : "here's where " + t.or(Facts.BOOKING_REFERENCE, "your shipment") + " stands (" + t.or(Facts.AIRLINE, "") + ", " + lane + "):";
                paragraphs.add(head.replace("(, ", "(") + " " + t.or(Facts.PROGRESS, ""));
                if (t.has(Facts.ETA)) {
                    paragraphs.add(de ? "Die planmäßige Ankunft ist " + t.get(Facts.ETA) + "."
                            : "Scheduled arrival is " + t.get(Facts.ETA) + ".");
                }
            }
            case BID_REQUEST, RATEDESK_REQUEST -> {
                subject = de ? "Ratenanfrage " + lane + t.weightSuffix() : "Rate request " + lane + t.weightSuffix();
                paragraphs.add(de
                        ? "könnten Sie uns bitte eine Rate für " + lane + " geben: " + shipment + "."
                        : "could you quote us a rate for " + lane + ": " + shipment + ".");
                paragraphs.add(de
                        ? "Bitte nennen Sie den All-in-Preis inklusive Fuel und Security, die Laufzeit und wie lange die Rate gilt"
                        + (intent.purpose() == EmailWriter.Purpose.RATEDESK_REQUEST ? ", gern mit Rate-ID." : ".")
                        : "Please send your all-in price including fuel and security, the transit time and how long the rate is valid"
                        + (intent.purpose() == EmailWriter.Purpose.RATEDESK_REQUEST ? ", plus the rate ID." : "."));
            }
            case TRUCK_REQUEST -> {
                String from = t.or(Facts.PICKUP_CITY, "") + (t.has(Facts.PICKUP_ZIP) ? " (" + t.get(Facts.PICKUP_ZIP) + ")" : "");
                String to = t.or(Facts.AIRPORT, t.or(Facts.ORIGIN, "the airport"));
                subject = de ? "Abholung " + from.strip() + " nach " + to : "Pickup " + from.strip() + " to " + to;
                paragraphs.add(de
                        ? "wir bräuchten eine Abholung in " + from.strip() + " zum Flughafen " + to + ": " + shipment + "."
                        : "we need a pickup in " + from.strip() + " to " + to + " airport: " + shipment + ".");
                paragraphs.add(de ? "Was kostet das all-in, und wann könnten Sie abholen?"
                        : "What would that cost all-in, and when could you collect?");
            }
            case BOOKING_REQUEST -> {
                subject = de ? "Buchung " + lane + t.refSuffix() : "Booking " + lane + t.refSuffix();
                paragraphs.add(de
                        ? "wir möchten folgende Sendung bei Ihnen buchen: " + lane + ", " + shipment + t.priceSuffix(" zur vereinbarten Rate") + "."
                        : "we'd like to book the following with you: " + lane + ", " + shipment + t.priceSuffix(" at the agreed rate") + ".");
                paragraphs.add(de ? "Bitte bestätigen Sie die Buchung kurz mit Ihrer Buchungsnummer" + t.refMention(true) + "."
                        : "Please confirm with your booking reference" + t.refMention(false) + ".");
            }
            case FOLLOW_UP_ANSWER -> {
                subject = de ? "Ihre Rückfrage" : "Your question";
                paragraphs.add(de ? "danke für Ihre Nachricht. Ich prüfe das gerade und melde mich in Kürze bei Ihnen."
                        : "thanks for your message. I'm looking into it now and will get back to you shortly.");
            }
            case HOLDING_REPLY -> {
                subject = de ? "Ihre Anfrage" : "Your request";
                String when = t.or(Facts.NEXT_UPDATE, de ? "in Kürze" : "shortly");
                paragraphs.add(de ? "danke, ist angekommen. Ich kümmere mich darum und melde mich " + when + "."
                        : "thanks, got it. I'm on it and will get back to you " + when + ".");
            }
            default -> throw new IllegalStateException("Unhandled purpose " + intent.purpose());
        }

        StringBuilder body = new StringBuilder(greeting(intent.recipientName(), de, intent.purpose())).append("\n\n");
        paragraphs.forEach(p -> body.append(p.replaceAll(" {2,}", " ")).append("\n\n"));
        appendMissingLiterals(intent, body, de);
        body.append(de ? "Viele Grüße" : "Best regards,");
        return new Draft(subject.replaceAll(" {2,}", " ").strip(), body.toString());
    }

    private static String greeting(String name, boolean de, EmailWriter.Purpose purpose) {
        boolean carrier = purpose == EmailWriter.Purpose.BID_REQUEST || purpose == EmailWriter.Purpose.TRUCK_REQUEST
                || purpose == EmailWriter.Purpose.RATEDESK_REQUEST || purpose == EmailWriter.Purpose.BOOKING_REQUEST;
        if (name != null && !name.isBlank()) return (de ? "Hallo " : "Hi ") + name.strip() + ",";
        if (carrier) return de ? "Hallo zusammen," : "Hello team,";
        return de ? "Guten Tag," : "Hello,";
    }

    /** Safety net: a literal the caller insists on must be in the mail, even if no sentence above used it. */
    private static void appendMissingLiterals(MailIntent intent, StringBuilder body, boolean de) {
        if (intent.mustContain() == null) return;
        List<String> missing = intent.mustContain().stream().filter(s -> body.indexOf(s) < 0).toList();
        if (!missing.isEmpty()) {
            body.append(de ? "Zur Referenz: " : "For reference: ").append(String.join(", ", missing)).append("\n\n");
        }
    }

    private static String pick(RandomGenerator random, String... options) {
        return options[random.nextInt(options.length)];
    }

    /** Small helpers that turn optional facts into sentence fragments. */
    private record Text(Map<String, Object> f, boolean de) {

        boolean has(String key) {
            return f.get(key) != null && !String.valueOf(f.get(key)).isBlank();
        }

        String get(String key) {
            return has(key) ? String.valueOf(f.get(key)).strip() : "";
        }

        String or(String key, String fallback) {
            return has(key) ? get(key) : fallback;
        }

        String lane() {
            if (has(Facts.ORIGIN) && has(Facts.DESTINATION)) return get(Facts.ORIGIN) + (de ? " nach " : " to ") + get(Facts.DESTINATION);
            return or(Facts.DESTINATION, "");
        }

        String price() {
            return has(Facts.CURRENCY) ? get(Facts.CURRENCY) + " " + get(Facts.TOTAL) : get(Facts.TOTAL);
        }

        String priceSuffix(String label) {
            return has(Facts.TOTAL) ? label + (de ? " von " : " of ") + price() : "";
        }

        String about() {
            List<String> bits = new ArrayList<>();
            if (has(Facts.WEIGHT_KG)) bits.add(get(Facts.WEIGHT_KG) + " kg");
            if (has(Facts.COMMODITY)) bits.add(get(Facts.COMMODITY));
            if (bits.isEmpty()) return "";
            return (de ? " zu den " : " on the ") + String.join(de ? " " : " of ", bits);
        }

        String transitEn() {
            return has(Facts.TRANSIT_DAYS) ? ", " + get(Facts.TRANSIT_DAYS) + " days in transit" : "";
        }

        String transitDe() {
            return has(Facts.TRANSIT_DAYS) ? ", Laufzeit " + get(Facts.TRANSIT_DAYS) + " Tage" : "";
        }

        String etaEn() {
            return has(Facts.ETA) ? ", arrival " + get(Facts.ETA) : "";
        }

        String etaDe() {
            return has(Facts.ETA) ? ", Ankunft " + get(Facts.ETA) : "";
        }

        String weightSuffix() {
            return has(Facts.WEIGHT_KG) ? ", " + get(Facts.WEIGHT_KG) + " kg" : "";
        }

        String refSuffix() {
            return has(Facts.OUR_REFERENCE) ? " / " + get(Facts.OUR_REFERENCE) : "";
        }

        String refMention(boolean german) {
            if (!has(Facts.OUR_REFERENCE)) return "";
            return german ? " (unsere Referenz " + get(Facts.OUR_REFERENCE) + ")" : " (our reference " + get(Facts.OUR_REFERENCE) + ")";
        }

        String shipment() {
            List<String> bits = new ArrayList<>();
            if (has(Facts.PIECES)) bits.add(get(Facts.PIECES) + (de ? " Packstücke" : " pieces"));
            if (has(Facts.WEIGHT_KG)) bits.add(get(Facts.WEIGHT_KG) + " kg");
            if (has(Facts.VOLUME_CBM)) bits.add(get(Facts.VOLUME_CBM) + " cbm");
            if (has(Facts.COMMODITY)) bits.add(get(Facts.COMMODITY));
            if (has(Facts.READY_DATE)) bits.add((de ? "bereit ab " : "ready ") + get(Facts.READY_DATE));
            return String.join(", ", bits);
        }
    }
}
