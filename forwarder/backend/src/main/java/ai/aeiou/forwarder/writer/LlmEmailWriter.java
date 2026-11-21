package ai.aeiou.forwarder.writer;

import org.springframework.beans.factory.annotation.Autowired;

import ai.aeiou.forwarder.event.Actor;
import ai.aeiou.forwarder.event.EventLog;
import ai.aeiou.forwarder.llm.LlmClient;
import ai.aeiou.forwarder.llm.LlmClient.ChatMessage;
import ai.aeiou.forwarder.llm.LlmClient.LlmRequest;
import ai.aeiou.forwarder.mail.StoredMail;
import ai.aeiou.forwarder.settings.SettingsService;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.random.RandomGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Writes every outgoing mail with one LLM call as our persona, validates the result against the hard facts, retries
 * once with the problems fed back, and falls back to a human-written template (ARCHITECTURE.md §15).
 */
@Service
public class LlmEmailWriter implements EmailWriter {

    private static final Logger log = LoggerFactory.getLogger(LlmEmailWriter.class);
    private static final int MAX_ATTEMPTS = 2;
    private static final double TEMPERATURE = 0.7;
    private static final List<String> SUPPORTED_TEMPLATE_LANGUAGES = List.of("de", "en");

    /** Structured output of the writer call. */
    record GeneratedMail(String subject, String body) {}

    private final LlmClient llm;
    private final SettingsService settings;
    private final EventLog events;
    private final String model;
    private final RandomGenerator random;

    @Autowired
    public LlmEmailWriter(LlmClient llm, SettingsService settings, EventLog events,
                          @Value("${app.llm.model-writer}") String model) {
        this(llm, settings, events, model, new java.util.SplittableRandom());
    }

    LlmEmailWriter(LlmClient llm, SettingsService settings, EventLog events, String model, RandomGenerator random) {
        this.llm = llm;
        this.settings = settings;
        this.events = events;
        this.model = model;
        this.random = random;
    }

    @Override
    public WrittenMail write(MailIntent intent) {
        Persona persona = persona();
        String language = normalizeLanguage(intent.language());
        boolean fresh = replySubject(intent.thread()) == null;
        List<ChatMessage> messages = WriterPrompt.build(intent, persona, language, fresh, random);
        String purpose = "writer." + intent.purpose().name().toLowerCase(Locale.ROOT);

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            GeneratedMail generated;
            try {
                generated = llm.structured(new LlmRequest(intent.sessionId(), purpose, model, messages, TEMPERATURE),
                        GeneratedMail.class).value();
            } catch (RuntimeException e) {
                log.warn("Writer LLM call failed for {} (session {}): {}", intent.purpose(), intent.sessionId(), e.toString());
                break;
            }
            if (generated == null) break;
            String body = clean(generated.body(), persona);
            String subject = fresh ? cleanSubject(generated.subject()) : replySubject(intent.thread());
            List<String> errors = MailValidator.validate(intent, subject, body, fresh);
            if (errors.isEmpty()) {
                return new WrittenMail(subject, sign(body, persona), false);
            }
            events.append(intent.sessionId(), "writer.rejected", Actor.system,
                    "Writer draft failed checks (attempt " + attempt + "): " + errors.getFirst(),
                    Map.of("purpose", intent.purpose().name(), "attempt", attempt, "errors", errors));
            messages = new ArrayList<>(messages);
            messages.add(ChatMessage.assistant("{\"subject\":" + quote(generated.subject()) + ",\"body\":" + quote(generated.body()) + "}"));
            messages.add(ChatMessage.user("Rewrite the mail and fix: " + String.join(" ", errors)));
        }
        return template(intent, persona, language, fresh);
    }

    private WrittenMail template(MailIntent intent, Persona persona, String language, boolean fresh) {
        String templateLanguage = SUPPORTED_TEMPLATE_LANGUAGES.contains(language) ? language : "en";
        EmailTemplates.Draft draft = EmailTemplates.render(intent, templateLanguage, random);
        events.append(intent.sessionId(), "writer.template", Actor.system,
                "Used the " + templateLanguage + " template for " + intent.purpose(),
                Map.of("purpose", intent.purpose().name(), "language", templateLanguage));
        String subject = fresh ? draft.subject() : replySubject(intent.thread());
        return new WrittenMail(subject, sign(draft.body(), persona), true);
    }

    Persona persona() {
        return new Persona(settings.getString("writerPersonaName", Persona.DEFAULT_NAME),
                settings.getString("writerCompany", Persona.DEFAULT_COMPANY));
    }

    /** "Re: <subject>" of the latest mail in the thread, or null when this is a fresh mail. */
    static String replySubject(List<StoredMail> thread) {
        if (thread == null || thread.isEmpty()) return null;
        String subject = thread.getLast().subject() == null ? "" : thread.getLast().subject().strip();
        return subject.regionMatches(true, 0, "Re:", 0, 3) ? subject : "Re: " + subject;
    }

    static String normalizeLanguage(String language) {
        if (language == null || language.isBlank()) return "en";
        String l = language.strip().toLowerCase(Locale.ROOT);
        return switch (l) {
            case "german", "deutsch" -> "de";
            case "english" -> "en";
            case "french", "français" -> "fr";
            case "spanish", "español" -> "es";
            case "italian", "italiano" -> "it";
            default -> l.length() >= 2 && Character.isLetter(l.charAt(0)) && Character.isLetter(l.charAt(1)) ? l.substring(0, 2) : "en";
        };
    }

    /** Removes the usual model artefacts: em dashes, a signature the model added itself, stray whitespace. */
    static String clean(String body, Persona persona) {
        if (body == null) return "";
        String text = body.replace("\r\n", "\n").replace(" — ", " - ").replace("—", " - ").replace("–", "-").strip();
        List<String> lines = new ArrayList<>(List.of(text.split("\n", -1)));
        while (!lines.isEmpty()) {
            String last = lines.getLast().strip();
            if (last.isEmpty() || last.equalsIgnoreCase(persona.firstName()) || last.equalsIgnoreCase(persona.fullName())
                    || persona.company().toLowerCase(Locale.ROOT).startsWith(last.toLowerCase(Locale.ROOT))) {
                lines.removeLast();
            } else {
                break;
            }
        }
        return String.join("\n", lines).replaceAll("\n{3,}", "\n\n").strip();
    }

    private static String cleanSubject(String subject) {
        if (subject == null) return null;
        String s = subject.strip().replaceFirst("(?i)^(subject|betreff):\\s*", "");
        return s.isEmpty() ? null : s;
    }

    private static String sign(String body, Persona persona) {
        return body.strip() + "\n\n" + persona.signature();
    }

    private static String quote(String s) {
        if (s == null) return "\"\"";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }
}
