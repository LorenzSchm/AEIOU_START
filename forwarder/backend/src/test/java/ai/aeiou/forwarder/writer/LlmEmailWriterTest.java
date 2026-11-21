package ai.aeiou.forwarder.writer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.aeiou.forwarder.event.EventLog;
import ai.aeiou.forwarder.llm.LlmClient;
import ai.aeiou.forwarder.llm.LlmClient.ChatMessage;
import ai.aeiou.forwarder.llm.LlmClient.LlmRequest;
import ai.aeiou.forwarder.llm.LlmClient.LlmResult;
import ai.aeiou.forwarder.mail.StoredMail;
import ai.aeiou.forwarder.settings.SettingsService;
import ai.aeiou.forwarder.writer.EmailWriter.MailIntent;
import ai.aeiou.forwarder.writer.EmailWriter.Purpose;
import ai.aeiou.forwarder.writer.EmailWriter.WrittenMail;
import ai.aeiou.forwarder.writer.LlmEmailWriter.GeneratedMail;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class LlmEmailWriterTest {

    private LlmClient llm;
    private SettingsService settings;
    private LlmEmailWriter writer;

    @BeforeEach
    void setUp() {
        llm = mock(LlmClient.class);
        settings = mock(SettingsService.class);
        when(settings.getString(anyString(), anyString())).thenAnswer(inv -> inv.getArgument(1));
        writer = new LlmEmailWriter(llm, settings, mock(EventLog.class), "gpt-test", new Random(7));
    }

    private static StoredMail customerMail(String subject, String body) {
        return new StoredMail(10, 4711, "in", "mark@customer.example", "aeiou@hackloop.mail", subject, body, null,
                List.of(), 4711L, 1L, OffsetDateTime.parse("2026-11-21T16:00:00Z"), List.of());
    }

    private static MailIntent quote(String language) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put(Facts.TOTAL, "1,387.35");
        facts.put(Facts.CURRENCY, "EUR");
        facts.put(Facts.AIRLINE, "Helix Air Freight");
        facts.put(Facts.TRANSIT_DAYS, 4);
        facts.put(Facts.ORIGIN, "FRA");
        facts.put(Facts.DESTINATION, "SIN");
        facts.put(Facts.WEIGHT_KG, "250");
        facts.put(Facts.COMMODITY, "machine parts");
        return new MailIntent(1L, Purpose.QUOTE, List.of(customerMail("RFQ FRA-SIN 250 kg", "Hi, need a price. Thanks, Mark")),
                facts, List.of("EUR 1,387.35"), language, "Mark", null);
    }

    private void llmAnswers(GeneratedMail... answers) {
        var stub = when(llm.structured(any(LlmRequest.class), eq(GeneratedMail.class)));
        for (GeneratedMail answer : answers) {
            stub = stub.thenReturn(new LlmResult<>(answer, "gpt-test", 300, 80, 0.0001, 400));
        }
    }

    @Test
    void validMailOnFirstTryKeepsThreadSubjectAndAddsSignature() {
        llmAnswers(new GeneratedMail("", """
                Hi Mark,

                for the 250 kg of machine parts to Singapore I can offer Helix Air Freight at EUR 1,387.35 all-in, 4 days in transit.

                Just reply to confirm and I'll book it.

                Best regards,
                Lena"""));

        WrittenMail mail = writer.write(quote("en"));

        assertThat(mail.fromTemplate()).isFalse();
        assertThat(mail.subject()).isEqualTo("Re: RFQ FRA-SIN 250 kg");
        assertThat(mail.body()).contains("EUR 1,387.35")
                .endsWith("Best regards,\n\nLena Hoffmann\nAEIOU Air Freight, Munich")
                .doesNotContain("Lena\n\nLena");
        verify(llm, times(1)).structured(any(), eq(GeneratedMail.class));
    }

    @Test
    void retriesOnceWithTheErrorsFedBack() {
        llmAnswers(
                new GeneratedMail("", "Hi Mark, it is EUR 1,387 or EUR 1,250 for a slower option. Best regards,"),
                new GeneratedMail("", "Hi Mark,\n\nHelix Air Freight can take it for EUR 1,387.35 all-in.\n\nBest regards,"));

        WrittenMail mail = writer.write(quote("en"));

        assertThat(mail.fromTemplate()).isFalse();
        assertThat(mail.body()).startsWith("Hi Mark,\n\nHelix Air Freight");
        ArgumentCaptor<LlmRequest> requests = ArgumentCaptor.forClass(LlmRequest.class);
        verify(llm, times(2)).structured(requests.capture(), eq(GeneratedMail.class));
        List<ChatMessage> retry = requests.getAllValues().get(1).messages();
        assertThat(retry).hasSize(4);
        assertThat(retry.get(2).role()).isEqualTo("assistant");
        assertThat(retry.get(3).content()).contains("\"EUR 1,387.35\" must appear").contains("EUR 1,250");
    }

    @Test
    void fallsBackToGermanTemplateAfterTwoFailures() {
        llmAnswers(new GeneratedMail("", "Preis: {total}"), new GeneratedMail("", "Preis: [total]"));

        WrittenMail mail = writer.write(quote("de"));

        assertThat(mail.fromTemplate()).isTrue();
        assertThat(mail.subject()).isEqualTo("Re: RFQ FRA-SIN 250 kg");
        assertThat(mail.body()).startsWith("Hallo Mark,")
                .contains("EUR 1,387.35 all-in")
                .contains("Helix Air Freight")
                .contains("Laufzeit 4 Tage")
                .contains("Viele Grüße")
                .doesNotContain("{").doesNotContain("[");
        assertThat(MailValidator.validate(quote("de"), mail.subject(), mail.body(), false)).isEmpty();
    }

    @Test
    void unsupportedTemplateLanguageFallsBackToEnglishWhenTheGatewayFails() {
        when(llm.structured(any(), eq(GeneratedMail.class))).thenThrow(new IllegalStateException("gateway down"));

        WrittenMail mail = writer.write(quote("fr"));

        assertThat(mail.fromTemplate()).isTrue();
        assertThat(mail.body()).startsWith("Hi Mark,").contains("EUR 1,387.35").contains("Just reply to confirm");
        verify(llm, times(1)).structured(any(), eq(GeneratedMail.class));
    }

    @Test
    void promptCarriesPersonaLanguageFactsAndGermanExample() {
        when(settings.getString(eq("writerPersonaName"), anyString())).thenReturn("Jonas Berger");
        llmAnswers(new GeneratedMail("", "Hallo Mark,\n\nHelix Air Freight fliegt es für EUR 1,387.35 all-in.\n\nViele Grüße"));

        WrittenMail mail = writer.write(quote("de"));

        ArgumentCaptor<LlmRequest> request = ArgumentCaptor.forClass(LlmRequest.class);
        verify(llm).structured(request.capture(), eq(GeneratedMail.class));
        assertThat(request.getValue().model()).isEqualTo("gpt-test");
        assertThat(request.getValue().purpose()).isEqualTo("writer.quote");
        assertThat(request.getValue().messages().get(0).content()).contains("You are Jonas Berger at AEIOU Air Freight, Munich");
        assertThat(request.getValue().messages().get(1).content())
                .contains("LANGUAGE: German")
                .contains("RECIPIENT: Mark")
                .contains("- total: 1,387.35")
                .contains("MUST APPEAR: \"EUR 1,387.35\"")
                .contains("Guten Tag Frau Weber")
                .contains("Hi, need a price.");
        assertThat(mail.body()).endsWith("Jonas Berger\nAEIOU Air Freight, Munich");
    }

    @Test
    void freshMailUsesTheGeneratedSubjectAndEnglishTemplateHasOne() {
        MailIntent bid = new MailIntent(1L, Purpose.BID_REQUEST, List.of(),
                Map.of(Facts.ORIGIN, "FRA", Facts.DESTINATION, "JNB", Facts.WEIGHT_KG, "410", Facts.PIECES, 2),
                List.of(), "en", null, null);
        llmAnswers(new GeneratedMail("Spot rate FRA-JNB, 410 kg",
                "Hello Cobalt team,\n\ncould you quote FRA to JNB, 2 pieces, 410 kg? All-in price, transit and validity please.\n\nThanks,"));

        assertThat(writer.write(bid).subject()).isEqualTo("Spot rate FRA-JNB, 410 kg");

        when(llm.structured(any(), eq(GeneratedMail.class))).thenThrow(new IllegalStateException("down"));
        WrittenMail fallback = writer.write(bid);
        assertThat(fallback.subject()).isEqualTo("Rate request FRA to JNB, 410 kg");
        assertThat(fallback.body()).startsWith("Hello team,").contains("2 pieces, 410 kg").contains("all-in price");
    }

    @Test
    void cleanRemovesEmDashesAndModelSignature() {
        Persona persona = new Persona("Lena Hoffmann", "AEIOU Air Freight, Munich");
        String cleaned = LlmEmailWriter.clean("Hi — all set.\n\nBest,\nLena Hoffmann\nAEIOU Air Freight\n", persona);
        assertThat(cleaned).isEqualTo("Hi - all set.\n\nBest,");
    }

    @Test
    void normalizesLanguageNames() {
        assertThat(LlmEmailWriter.normalizeLanguage("de-DE")).isEqualTo("de");
        assertThat(LlmEmailWriter.normalizeLanguage("German")).isEqualTo("de");
        assertThat(LlmEmailWriter.normalizeLanguage(null)).isEqualTo("en");
    }
}
