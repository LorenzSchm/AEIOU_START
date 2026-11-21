package ai.aeiou.forwarder.router;

import ai.aeiou.forwarder.config.AppProperties;
import ai.aeiou.forwarder.llm.LlmClient;
import ai.aeiou.forwarder.llm.LlmClient.ChatMessage;
import ai.aeiou.forwarder.llm.LlmClient.LlmRequest;
import ai.aeiou.forwarder.mail.MailRepository;
import ai.aeiou.forwarder.mail.StoredMail;
import ai.aeiou.forwarder.settings.SettingsService;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Router stage 2: one structured LLM call that classifies the mail and extracts the RFQ, bid or
 * tracking data in the same pass. The model comes from the {@code llmModelRouter} setting,
 * falling back to {@code app.llm.model-router}.
 */
@Component
class Stage2Classifier {

    static final String PURPOSE = "router";
    private static final int THREAD_CONTEXT_MAILS = 6;
    private static final int THREAD_SNIPPET_CHARS = 400;
    private static final int MAX_BODY_CHARS = 8000;

    static final String SYSTEM_PROMPT = """
            You are the mail router of AEIOU Air Freight, a digital air-freight forwarder. Our mailbox is
            aeiou@hackloop.mail. Customers ask us for air-freight quotes; we buy transport from airlines
            (and truckers for door pickup) and quote the customer one all-in price.

            Classify ONE incoming mail and extract its data. Answer only with JSON that matches the schema.

            ## Types
            - NEW_RFQ: a customer asks for a price for a new air shipment (route, weight, volume, ...).
            - ACCEPTANCE: a customer accepts a quote we sent ("please book", "go ahead", "we accept").
            - FOLLOW_UP: a customer writes again in an existing quote thread without accepting: a question,
              changed details, a rejection, a reminder.
            - CARRIER_BID: an airline or trucker answers our bid request with a price, or declines it.
            - RATEDESK_REPLY: the rate desk (ratedesk@hackloop.mail) answers a rate question of ours.
            - BOOKING_CONFIRMATION: an airline confirms a booking ("Booking BK-xxxxxxxx confirmed").
            - TARIFF: an airline sends a general tariff or rate sheet, usually attached, not tied to one shipment.
            - TRACKING_QUESTION: someone asks where a shipment is, its status, departure or ETA.
            - OTHER: anything else (newsletters, spam, out-of-office, unclear).

            ## Extraction rules
            - Fill only the block that fits the type and set the others to null: rfq for NEW_RFQ (also for
              FOLLOW_UP or ACCEPTANCE when the customer restates or changes shipment details), bid for
              CARRIER_BID and RATEDESK_REPLY, tracking for TRACKING_QUESTION.
            - Never guess. A value the mail does not state is null. Do not invent airports, weights or prices.
            - rfq.originZip: postcode of the pickup address, digits only. rfq.originCity: origin city as written.
            - rfq.originAirport: 3-letter IATA code if the mail names the airport or the city has one obvious
              cargo airport (Frankfurt FRA, Munich/München MUC, Düsseldorf DUS, Stuttgart STR, Cologne/Köln CGN,
              Hamburg HAM). Null for a pickup address in a town without an airport.
            - rfq.destination: 3-letter IATA code of the destination airport; map well-known cities
              (New York JFK, Singapore SIN, Dubai DXB, Tokyo NRT, ...). Null if unsure.
            - rfq.weightKg: total gross weight in kg. Convert units (1 lb = 0.4536 kg, 1 t = 1000 kg) and
              multiply per-piece weights by the number of pieces.
            - rfq.volumeCbm: total volume in cubic metres. If only dimensions are given, compute
              length x width x height (cm) / 1,000,000 x pieces.
            - rfq.mode: "fastest" if the customer stresses speed, urgency or the earliest arrival;
              "cheapest" if they stress price or budget, or say nothing about it.
            - rfq.doorPickup: true ONLY if the customer asks us to collect the goods at their address (pickup,
              collect, ex works, "from our warehouse in <town>"); then also fill rfq.originZip. false if they bring
              or deliver the goods to the airport themselves ("we will deliver to DUS ourselves"), or if they only
              name an origin airport. null if unclear.
            - rfq.readyDate: ISO date (YYYY-MM-DD) the goods are ready; resolve relative dates from the mail date.
            - bid.price: the all-in total for the shipment as a plain number; bid.currency: ISO code (EUR, USD).
              bid.transitDays: whole days. bid.declined: true if the carrier cannot offer. bid.airlineOrCompany:
              who makes the offer.
            - tracking.reference: any booking reference (BK-xxxxxxxx), our reference (AEI-xxxxx) or other
              shipment id; tracking.question: the question in one short sentence.
            - language: ISO 639-1 code of the language the main text is written in (en, de, fr, ...). Ignore
              greetings and signature lines: an English mail signed "Mit freundlichen Gruessen" is "en".
            - senderName: the name the sender signs with, e.g. "Maria Keller"; null if unsigned.
            - confidence: your probability (0 to 1) that the type is right. Stay below 0.6 when the mail is
              ambiguous.
            - reasoning: one short sentence explaining the type.

            Hints come from deterministic checks against our database. Trust them.
            """;

    private final LlmClient llm;
    private final MailRepository mails;
    private final SettingsService settings;
    private final AppProperties props;

    Stage2Classifier(LlmClient llm, MailRepository mails, SettingsService settings, AppProperties props) {
        this.llm = llm;
        this.mails = mails;
        this.settings = settings;
        this.props = props;
    }

    Extraction classify(StoredMail mail, long sessionId, List<String> hints) {
        String model = settings.getString("llmModelRouter", props.llm().modelRouter());
        List<StoredMail> thread = mails.recentInSession(sessionId, mail.id(), THREAD_CONTEXT_MAILS);
        LlmRequest request = new LlmRequest(sessionId, PURPOSE, model,
                List.of(ChatMessage.system(SYSTEM_PROMPT), ChatMessage.user(userPrompt(mail, hints, thread))), 0.0);
        return llm.structured(request, Extraction.class).value();
    }

    static String userPrompt(StoredMail mail, List<String> hints, List<StoredMail> thread) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Hints\n");
        if (hints.isEmpty()) sb.append("- none\n");
        hints.forEach(h -> sb.append("- ").append(h).append('\n'));

        sb.append("\n## Earlier mails in this thread (oldest first)\n");
        if (thread.isEmpty()) sb.append("- none\n");
        for (StoredMail m : thread) {
            sb.append("- [").append("out".equals(m.direction()) ? "sent by us" : "received").append("] ")
                    .append(m.fromAddr()).append(" -> ").append(m.toAddr())
                    .append(" | ").append(m.subject())
                    .append(" | ").append(snippet(m.body(), THREAD_SNIPPET_CHARS)).append('\n');
        }

        sb.append("\n## Mail to classify\n")
                .append("From: ").append(mail.fromAddr()).append('\n')
                .append("To: ").append(mail.toAddr()).append('\n')
                .append("Date: ").append(mail.sentAt()).append('\n')
                .append("Subject: ").append(mail.subject()).append('\n');
        if (!mail.attachments().isEmpty()) {
            sb.append("Attachments: ").append(mail.attachments().stream()
                    .map(StoredMail.AttachmentRef::filename).collect(Collectors.joining(", "))).append('\n');
        }
        sb.append("\n").append(truncate(mail.body(), MAX_BODY_CHARS));
        return sb.toString();
    }

    private static String snippet(String body, int max) {
        if (body == null) return "";
        return truncate(body.replaceAll("\\s+", " ").trim(), max);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + " […]";
    }
}
