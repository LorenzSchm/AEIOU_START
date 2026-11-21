package ai.aeiou.forwarder.tariff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.aeiou.forwarder.event.Actor;
import ai.aeiou.forwarder.event.EventLog;
import ai.aeiou.forwarder.llm.LlmClient;
import ai.aeiou.forwarder.mail.MailGateway;
import ai.aeiou.forwarder.mail.StoredMail;
import ai.aeiou.forwarder.reference.Carrier;
import ai.aeiou.forwarder.reference.ReferenceData;
import ai.aeiou.forwarder.router.MailType;
import ai.aeiou.forwarder.router.RoutedMail;
import ai.aeiou.forwarder.session.SessionService;
import ai.aeiou.forwarder.session.SessionStatus;
import ai.aeiou.forwarder.settings.SettingsService;
import ai.aeiou.forwarder.tariff.parse.AirportResolver;
import ai.aeiou.forwarder.tariff.parse.KitFiles;
import ai.aeiou.forwarder.tariff.parse.TariffParsers;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TariffIngestionHandlerTest {

    private static final long SESSION = 7L;
    private static final String YELLOWTAIL_FILE = "yellowtail-air-freight-tariff-2026-11.txt";
    private static final Carrier YELLOWTAIL = new Carrier(25, "Yellowtail Air Freight", null, Carrier.Kind.airline,
            "yellowtail-air-freight@hackloop.mail", List.of("BOG"));

    private final MailGateway mailGateway = mock(MailGateway.class);
    private final TariffAdminService admin = mock(TariffAdminService.class);
    private final ReferenceData referenceData = mock(ReferenceData.class);
    private final LlmClient llm = mock(LlmClient.class);
    private final SessionService sessions = mock(SessionService.class);
    private final SettingsService settings = mock(SettingsService.class);
    private final EventLog events = mock(EventLog.class);
    private final TariffParsers parsers = TariffParsers.standard(AirportResolver.NONE);
    private final TariffIngestionHandler handler = new TariffIngestionHandler(mailGateway, parsers, admin, referenceData, llm,
            sessions, settings, events, "test-model");

    private ParsedTariff november;

    @BeforeEach
    void setUp() throws Exception {
        november = parsers.parse(KitFiles.source(YELLOWTAIL_FILE)).withAirline("Yellowtail Air Freight", null);
        StoredTariff current = stored(1, november, TariffStatus.active);
        when(admin.inForce("Yellowtail Air Freight")).thenReturn(Optional.of(new TariffAdminService.TariffDetail(current, november.lanes())));
        when(admin.today()).thenReturn(LocalDate.of(2026, 11, 21));
        when(admin.store(any(), anyString(), anyString(), any(), anyString(), any()))
                .thenAnswer(inv -> stored(2, inv.getArgument(0), inv.getArgument(5)));
        when(referenceData.carrierByEmail(YELLOWTAIL.email())).thenReturn(Optional.of(YELLOWTAIL));
        when(referenceData.airlineByName(anyString())).thenReturn(Optional.empty());
        when(referenceData.airlineByName("Yellowtail Air Freight")).thenReturn(Optional.of(YELLOWTAIL));
        when(settings.getBool(anyString(), anyBoolean())).thenAnswer(inv -> inv.getArgument(1));
    }

    @Test
    void supportsTariffMailsOnly() {
        assertThat(handler.supports()).containsExactly(MailType.TARIFF);
    }

    @Test
    void plausibleSheetIsActivated() {
        handler.handle(mailWith(YELLOWTAIL_FILE, december(KitFiles.source(YELLOWTAIL_FILE).content())));

        ArgumentCaptor<ParsedTariff> saved = ArgumentCaptor.forClass(ParsedTariff.class);
        verify(admin).store(saved.capture(), eq("2026-12-mail99"), eq("mail"), eq(99L), eq(YELLOWTAIL_FILE), eq(TariffStatus.active));
        assertThat(saved.getValue().lanes()).hasSize(15);
        verify(events).append(eq(SESSION), eq(99L), eq("tariff.ingested"), eq(Actor.system), anyString(), any());
        verify(sessions).setStatus(SESSION, SessionStatus.CLOSED);
        verify(sessions, never()).flagNeedsHuman(anyLong(), anyString());
        verify(llm, never()).structured(any(), any());
    }

    @Test
    void rateJumpOver50PercentIsHeldForReview() {
        String text = new String(december(KitFiles.source(YELLOWTAIL_FILE).content()), StandardCharsets.UTF_8)
                .replace("EUR 4.62  EUR 4.06", "EUR 9.62  EUR 4.06");
        handler.handle(mailWith(YELLOWTAIL_FILE, text.getBytes(StandardCharsets.UTF_8)));

        verify(admin).store(any(), anyString(), eq("mail"), eq(99L), anyString(), eq(TariffStatus.pending_review));
        verify(events).append(eq(SESSION), eq(99L), eq("tariff.rejected"), eq(Actor.system), contains("50%"), any());
        verify(sessions).flagNeedsHuman(eq(SESSION), contains("HAM-BOG 4.62 -> 9.62"));
    }

    @Test
    void imageGoesToAHuman() {
        handler.handle(mailWith("tariff-dec.jpg", new byte[] {1, 2, 3}));

        verify(admin, never()).store(any(), anyString(), anyString(), any(), anyString(), any());
        verify(events).append(eq(SESSION), eq(99L), eq("tariff.rejected"), eq(Actor.system), contains("image"), any());
        verify(sessions).flagNeedsHuman(eq(SESSION), contains("image"));
    }

    @Test
    void unknownLayoutFallsBackToTheLlm() {
        LlmTariffExtraction extraction = new LlmTariffExtraction("Yellowtail Air Freight", "EUR", null, "2026-12-01", "2026-12-31",
                november.lanes().stream().map(l -> new LlmTariffExtraction.Lane(l.origin(), l.destination(), l.transitDays(),
                        l.minimum().doubleValue(), l.breaks().stream().map(b -> new LlmTariffExtraction.Break(b.fromKg(), b.rate().doubleValue())).toList(),
                        l.fuelPerKg().doubleValue(), l.securityPerKg().doubleValue(), List.of())).toList(), List.of());
        when(llm.structured(any(), eq(LlmTariffExtraction.class)))
                .thenReturn(new LlmClient.LlmResult<>(extraction, "test-model", 1000, 800, 0.001, 900));

        handler.handle(mailWith("rates.txt", "Our December rates are like November, nothing else to say.".getBytes(StandardCharsets.UTF_8)));

        verify(llm).structured(any(), eq(LlmTariffExtraction.class));
        verify(admin).store(any(), eq("2026-12-mail99"), eq("mail"), eq(99L), eq("rates.txt"), eq(TariffStatus.active));
        verify(events).append(eq(SESSION), eq(99L), eq("tariff.ingested"), eq(Actor.llm), anyString(), any());
    }

    @Test
    void llmResultIsHeldWhenAutoActivationIsOff() {
        when(settings.getBool(eq(TariffIngestionHandler.AUTO_ACTIVATE_LLM), anyBoolean())).thenReturn(false);
        when(llm.structured(any(), eq(LlmTariffExtraction.class))).thenReturn(new LlmClient.LlmResult<>(
                new LlmTariffExtraction("Yellowtail Air Freight", "EUR", null, "2026-12-01", "2026-12-31",
                        List.of(new LlmTariffExtraction.Lane("FRA", "ORD", 4, 110.0, List.of(new LlmTariffExtraction.Break(0, 3.73)),
                                0.66, 0.11, List.of())), List.of()), "m", 1, 1, 0, 1));

        handler.handle(mailWith("rates.txt", "see below".getBytes(StandardCharsets.UTF_8)));

        verify(admin).store(any(), anyString(), anyString(), any(), anyString(), eq(TariffStatus.pending_review));
        verify(sessions).flagNeedsHuman(eq(SESSION), contains("auto-activation is off"));
    }

    @Test
    void sheetFromAnotherAirlineIsSuspicious() {
        Carrier helix = new Carrier(8, "Helix Air Freight", null, Carrier.Kind.airline, "helix-air-freight@hackloop.mail", List.of());
        when(referenceData.carrierByEmail(helix.email())).thenReturn(Optional.of(helix));
        when(admin.inForce("Helix Air Freight")).thenReturn(Optional.empty());

        handler.handle(new RoutedMail(mail(helix.email(), YELLOWTAIL_FILE, december(KitFiles.source(YELLOWTAIL_FILE).content())),
                MailType.TARIFF, 1, 1, SESSION, null));

        verify(admin).store(any(), anyString(), anyString(), any(), anyString(), eq(TariffStatus.pending_review));
        verify(sessions).flagNeedsHuman(eq(SESSION), contains("sent by Helix Air Freight"));
    }

    private RoutedMail mailWith(String filename, byte[] content) {
        return new RoutedMail(mail(YELLOWTAIL.email(), filename, content), MailType.TARIFF, 1, 1, SESSION, null);
    }

    private StoredMail mail(String from, String filename, byte[] content) {
        when(mailGateway.attachmentContent(5L)).thenReturn(content);
        return new StoredMail(99, 1234, "in", from, "aeiou@hackloop.mail", "December tariff", "Please find attached.", null, List.of(),
                1234L, SESSION, OffsetDateTime.now(), List.of(new StoredMail.AttachmentRef(5, 55L, filename, null, 100L)));
    }

    private static byte[] december(byte[] november) {
        return new String(november, StandardCharsets.UTF_8)
                .replace("01.11.2026", "01.12.2026").replace("30.11.2026", "31.12.2026")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static StoredTariff stored(long id, ParsedTariff t, TariffStatus status) {
        return new StoredTariff(id, t.airlineName(), null, "v", "mail", "f", null, t.validFrom(), t.validTo(), t.currency(), null,
                status, t.lanes().size(), null, OffsetDateTime.now());
    }
}
