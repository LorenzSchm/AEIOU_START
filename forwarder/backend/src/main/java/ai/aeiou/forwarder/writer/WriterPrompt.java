package ai.aeiou.forwarder.writer;

import ai.aeiou.forwarder.llm.LlmClient.ChatMessage;
import ai.aeiou.forwarder.mail.StoredMail;
import ai.aeiou.forwarder.writer.EmailWriter.MailIntent;
import ai.aeiou.forwarder.writer.EmailWriter.Purpose;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.random.RandomGenerator;

/**
 * Builds the token-lean writer prompt: a fixed system prompt with the persona and style rules, the one task line and
 * golden example for this purpose, then the facts and the (de-quoted, truncated) thread.
 */
final class WriterPrompt {

    private static final int MAX_THREAD_MAILS = 3;
    private static final int MAX_MAIL_CHARS = 1200;

    private static final String SYSTEM = """
            You are %s at %s, a small air-freight forwarding desk. You write one email, like a competent colleague who \
            does this all day: friendly, brief, precise.
            Rules:
            - Write in the language given. Greet the person by name if known. Mirror their register: German "Sie" unless \
            they write "du"; relaxed English if they are relaxed, formal if they are formal.
            - 2-4 short paragraphs. No bullet lists unless they used lists. Do not restate the subject.
            - Show you read their mail: mention a specific (commodity, ready date, weight, their reference).
            - Use exactly the given facts, never invent any (no flight numbers, dates, fees or promises not given). \
            Copy every MUST APPEAR string character for character.
            - No placeholders or brackets. No "I hope this email finds you well", "Certainly", "I'd be happy to", \
            "don't hesitate", "delve", no exclamation marks, no em dashes, no hype.
            - At most one clear call to action, only where it fits.
            - End with a closing phrase only ("Best regards," / "Viele Grüße"); the signature is added for you.
            Return JSON: subject (a short natural subject for a new mail, "" for a reply) and body.""";

    private static final Map<Purpose, String> TASKS = new EnumMap<>(Map.of(
            Purpose.QUOTE, "Quote the shipment: state the one all-in total exactly as given, the airline and transit time, "
                    + "validity if given. One option only: no alternatives, ranges or price breakdown. Close by inviting "
                    + "them to confirm so you can book.",
            Purpose.BOOKING_CONFIRMATION, "Confirm the booking is made: booking reference, airline, route, total, and "
                    + "departure/ETA if given. Reassuring, short.",
            Purpose.TRACKING_ANSWER, "Answer where the shipment is: status, airline, route, booking reference, departure "
                    + "and ETA, built on the progress fact. Specific, no hedging.",
            Purpose.BID_REQUEST, "Ask this airline for a spot rate with all shipment facts. Ask for the all-in price "
                    + "(freight, fuel, security), transit time and validity. Colleague to colleague, concise.",
            Purpose.TRUCK_REQUEST, "Ask this trucker for a pickup from the pickup address to the airport with the shipment "
                    + "facts and ready date. Ask for the all-in price and when they can collect.",
            Purpose.RATEDESK_REQUEST, "Ask the rate desk for a rate on this lane with the shipment facts. Ask for the "
                    + "all-in total, transit time, validity and rate ID.",
            Purpose.BOOKING_REQUEST, "Ask this airline to book the shipment at the agreed rate (state it exactly), with "
                    + "all shipment facts and our reference. Ask for a booking reference in return.",
            Purpose.FOLLOW_UP_ANSWER, "Answer their follow-up using only the facts. If the facts do not cover it, say "
                    + "you are checking and will come back shortly.",
            Purpose.HOLDING_REPLY, "A short note that you have their mail and are on it, and when they will hear back if "
                    + "given. No prices."));

    /** Golden examples for tone only. Keep them short: they are sent with every call. */
    private static final Map<Purpose, String> EXAMPLES_EN = new EnumMap<>(Map.of(
            Purpose.QUOTE, """
                    Hi Mark,

                    thanks for the details on the 320 kg of machine parts. For Frankfurt to Chicago, ready on Monday, \
                    I can offer you Kestrel Airways at EUR 1,910.40 all-in, 3 days in transit. The rate holds until \
                    tomorrow 18:00.

                    Just reply to confirm and I'll book it.

                    Best regards,""",
            Purpose.BOOKING_CONFIRMATION, """
                    Hi Sarah,

                    you're booked. Your textiles fly with Vega Skyfreight from Munich to Dubai under booking BK-7Q2M9X4L, \
                    EUR 842.00 all-in. Departure is planned for 24 November, arrival on the 26th.

                    I'll keep an eye on it and let you know if anything changes.

                    Best regards,""",
            Purpose.TRACKING_ANSWER, """
                    Hello,

                    BK-3HD82KQA is on its way: it left Frankfurt with Helix Air Freight yesterday and is due in Singapore \
                    on Thursday, 26 November. Everything is running to plan.

                    Best regards,""",
            Purpose.BID_REQUEST, """
                    Hello Cobalt team,

                    could you quote us a spot rate FRA to JNB: 2 pieces, 410 kg, 1.8 cbm, spare parts, ready 25 November.

                    Please send your all-in price including fuel and security, the transit time and how long the rate \
                    is valid.

                    Thanks and best regards,""",
            Purpose.HOLDING_REPLY, """
                    Hi Tom,

                    got it, thanks. I'm checking the options for your pharma shipment to Toronto now and will be back \
                    to you within the hour.

                    Best regards,"""));

    private static final Map<Purpose, String> EXAMPLES_DE = new EnumMap<>(Map.of(
            Purpose.QUOTE, """
                    Guten Tag Frau Weber,

                    danke für Ihre Anfrage zu den 180 kg Elektronikteilen ab München. Nach Shanghai kann ich Ihnen \
                    Orion Cargo Air für EUR 1,204.50 all-in anbieten, Laufzeit 4 Tage. Das Angebot gilt bis morgen 12 Uhr.

                    Geben Sie mir einfach kurz Bescheid, dann buche ich.

                    Viele Grüße""",
            Purpose.BOOKING_CONFIRMATION, """
                    Guten Tag Herr Brandt,

                    Ihre Sendung ist gebucht: Elbflug Air Freight, Düsseldorf nach New York, Buchungsnummer BK-K29DLM0P, \
                    EUR 2,315.00 all-in. Abflug ist für den 23. November geplant, Ankunft am 25.

                    Viele Grüße""",
            Purpose.TRACKING_ANSWER, """
                    Hallo Herr Kaya,

                    die Sendung BK-P0XA41TT ist gestern mit Quetzal Air Freight in Frankfurt gestartet und kommt \
                    planmäßig am Donnerstag, 26. November, in São Paulo an.

                    Viele Grüße"""));

    private static final List<String> OPENING_HINTS = List.of(
            "Open with the key fact right after the greeting.",
            "Open with a short thanks that names something specific from their mail.",
            "Open naturally, as if continuing a conversation with a regular contact.",
            "Open with one short sentence, then get to the point.");

    private WriterPrompt() {}

    static List<ChatMessage> build(MailIntent intent, Persona persona, String language, boolean freshMail, RandomGenerator random) {
        List<ChatMessage> messages = new ArrayList<>(3);
        messages.add(ChatMessage.system(SYSTEM.formatted(persona.fullName(), persona.company())));
        messages.add(ChatMessage.user(userMessage(intent, language, freshMail, random)));
        return messages;
    }

    static String userMessage(MailIntent intent, String language, boolean freshMail, RandomGenerator random) {
        StringBuilder sb = new StringBuilder(1024);
        sb.append("TASK: ").append(TASKS.get(intent.purpose())).append('\n');
        sb.append("LANGUAGE: ").append(Locale.forLanguageTag(language).getDisplayLanguage(Locale.ENGLISH)).append('\n');
        sb.append("MAIL: ").append(freshMail ? "new mail, write a subject" : "reply in the thread, subject \"\"").append('\n');
        if (intent.recipientName() != null && !intent.recipientName().isBlank()) {
            sb.append("RECIPIENT: ").append(intent.recipientName()).append('\n');
        }
        sb.append("STYLE: ").append(OPENING_HINTS.get(random.nextInt(OPENING_HINTS.size()))).append('\n');
        sb.append("FACTS:\n");
        if (intent.facts() != null) {
            intent.facts().forEach((k, v) -> {
                if (v != null && !String.valueOf(v).isBlank()) sb.append("- ").append(k).append(": ").append(v).append('\n');
            });
        }
        if (intent.mustContain() != null && !intent.mustContain().isEmpty()) {
            sb.append("MUST APPEAR: ");
            intent.mustContain().forEach(s -> sb.append('"').append(s).append("\" "));
            sb.append('\n');
        }
        if (intent.extraInstructions() != null && !intent.extraInstructions().isBlank()) {
            sb.append("NOTE: ").append(intent.extraInstructions()).append('\n');
        }
        String example = example(intent.purpose(), language);
        if (example != null) {
            sb.append("TONE EXAMPLE (other facts, do not copy them):\n").append(example).append('\n');
        }
        List<StoredMail> thread = intent.thread() == null ? List.of() : intent.thread();
        if (!thread.isEmpty()) {
            sb.append("THREAD (oldest first):\n");
            for (StoredMail mail : thread.subList(Math.max(0, thread.size() - MAX_THREAD_MAILS), thread.size())) {
                sb.append("--- ").append("out".equals(mail.direction()) ? "us" : "from " + mail.fromAddr())
                        .append(" | ").append(mail.subject()).append('\n')
                        .append(stripQuoted(mail.body())).append('\n');
            }
        }
        return sb.toString();
    }

    static String example(Purpose purpose, String language) {
        if ("de".equals(language) && EXAMPLES_DE.containsKey(purpose)) return EXAMPLES_DE.get(purpose);
        return EXAMPLES_EN.get(purpose);
    }

    /** Drops quoted history ("> ..." lines and everything after "On ... wrote:") and caps the length. */
    static String stripQuoted(String body) {
        if (body == null) return "";
        StringBuilder out = new StringBuilder();
        for (String line : body.split("\\R")) {
            String t = line.strip();
            if (t.startsWith(">")) continue;
            if (t.matches("(?i)^(on|am) .{3,120}(wrote|schrieb):$") || t.matches("^-{2,} ?(Original|Ursprüngliche).*")) break;
            out.append(line).append('\n');
        }
        String text = out.toString().strip();
        return text.length() > MAX_MAIL_CHARS ? text.substring(0, MAX_MAIL_CHARS) + " ..." : text;
    }
}
