package ai.aeiou.forwarder.pricing;

import ai.aeiou.forwarder.reference.Carrier;
import ai.aeiou.forwarder.reference.ReferenceData;
import ai.aeiou.forwarder.tariff.RateCard;
import ai.aeiou.forwarder.tariff.TariffRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * {@link PricingService} over the active tariff sheets. All lanes live in an immutable in-memory snapshot,
 * swapped atomically by {@link #reload()}, so a lookup is a map get plus a few multiplications.
 * <p>
 * When an airline has several active versions (a new month's sheet arrived before its valid_from), the version
 * in force today is used: the latest valid_from on or before today, else the earliest upcoming one.
 */
@Service
public class SheetPricingService implements PricingService {

    public static final String SOURCE = "sheet";
    private static final Logger log = LoggerFactory.getLogger(SheetPricingService.class);

    private final TariffRepository tariffs;
    private final ReferenceData referenceData;
    private final FxRates fxRates;
    private final Clock clock;
    private final AtomicReference<Map<String, List<RateCard>>> lanes = new AtomicReference<>(Map.of());

    public SheetPricingService(TariffRepository tariffs, ReferenceData referenceData, FxRates fxRates, ObjectProvider<Clock> clock) {
        this.tariffs = tariffs;
        this.referenceData = referenceData;
        this.fxRates = fxRates;
        this.clock = clock.getIfAvailable(Clock::systemDefaultZone);
    }

    @Override
    public List<PriceQuote> priceFromSheets(Shipment shipment) {
        List<PriceQuote> quotes = new ArrayList<>();
        for (RateCard card : cardsInForce(shipment.origin(), shipment.destination())) {
            quote(card, shipment).ifPresent(quotes::add);
        }
        quotes.sort(Comparator.comparing(PriceQuote::totalEur));
        return quotes;
    }

    @Override
    public Set<String> sheetAirlines(String origin, String destination) {
        return cardsInForce(origin, destination).stream()
                .map(RateCard::airlineName)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    @Override
    public void reload() {
        int superseded = tariffs.supersedeOutdated(LocalDate.now(clock));
        Map<String, List<RateCard>> byLane = tariffs.activeRateCards().stream()
                .collect(Collectors.groupingBy(c -> key(c.lane().origin(), c.lane().destination()), Collectors.toUnmodifiableList()));
        lanes.set(Map.copyOf(byLane));
        log.info("Pricing reloaded: {} lanes from active tariffs ({} versions superseded)", byLane.size(), superseded);
    }

    /** Prices one rate card; used for every sheet candidate and handy for what-if checks. */
    public Optional<PriceQuote> quote(RateCard card, Shipment shipment) {
        Optional<Fx> fx = fx(card);
        if (fx.isEmpty()) {
            log.warn("No EUR rate for {} tariff of {}; lane {} skipped", card.currency(), card.airlineName(), card.lane().key());
            return Optional.empty();
        }
        LanePrice price = PricingEngine.price(card.lane(), shipment);
        BigDecimal totalEur = PricingEngine.cents(price.total().multiply(fx.get().eurPerUnit()));
        String code = card.airlineCode() != null ? card.airlineCode()
                : referenceData.airlineByName(card.airlineName()).map(Carrier::code).orElse(null);
        return Optional.of(new PriceQuote(SOURCE, code, card.airlineName(), price.total(), card.currency(), totalEur,
                card.lane().transitDays(), null, breakdown(card, price, fx.get())));
    }

    private List<RateCard> cardsInForce(String origin, String destination) {
        if (origin == null || destination == null) return List.of();
        LocalDate today = LocalDate.now(clock);
        Map<String, RateCard> byAirline = new LinkedHashMap<>();
        for (RateCard card : lanes.get().getOrDefault(key(origin, destination), List.of())) {
            byAirline.merge(card.airlineName().toLowerCase(Locale.ROOT), card, (a, b) -> inForce(a, b, today));
        }
        return List.copyOf(byAirline.values());
    }

    private static RateCard inForce(RateCard a, RateCard b, LocalDate today) {
        LocalDate fa = a.validFrom() == null ? LocalDate.MIN : a.validFrom();
        LocalDate fb = b.validFrom() == null ? LocalDate.MIN : b.validFrom();
        boolean aStarted = !fa.isAfter(today);
        boolean bStarted = !fb.isAfter(today);
        if (aStarted != bStarted) return aStarted ? a : b;
        if (aStarted) return fa.isAfter(fb) || (fa.equals(fb) && a.tariffId() > b.tariffId()) ? a : b;
        return fa.isBefore(fb) ? a : b;
    }

    private Optional<Fx> fx(RateCard card) {
        if ("EUR".equalsIgnoreCase(card.currency())) return Optional.of(new Fx(BigDecimal.ONE, "none"));
        if (card.fxEurPerUnit() != null) return Optional.of(new Fx(card.fxEurPerUnit(), "sheet"));
        if ("USD".equalsIgnoreCase(card.currency())) return Optional.of(new Fx(fxRates.eurPerUsd(), "default"));
        return Optional.empty();
    }

    private Map<String, Object> breakdown(RateCard card, LanePrice p, Fx fx) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("tariffId", card.tariffId());
        b.put("tariffVersion", card.version());
        b.put("validFrom", card.validFrom() == null ? null : card.validFrom().toString());
        b.put("validTo", card.validTo() == null ? null : card.validTo().toString());
        if (card.validTo() != null && card.validTo().isBefore(LocalDate.now(clock))) b.put("tariffExpired", true);
        b.put("lane", card.lane().key());
        b.put("chargeableKg", p.chargeableKg());
        b.put("bracketFromKg", p.bracketFromKg());
        b.put("usedBreakFromKg", p.usedBreakFromKg());
        b.put("higherBreakUsed", p.higherBreakUsed());
        b.put("billedKg", p.billedKg());
        b.put("ratePerKg", p.ratePerKg());
        b.put("freightAtRate", p.freightAtRate());
        b.put("minimum", p.minimum());
        b.put("minimumApplied", p.minimumApplied());
        b.put("freight", p.freight());
        b.put("fuelPerKg", p.fuelPerKg());
        b.put("fuel", p.fuel());
        b.put("securityPerKg", p.securityPerKg());
        b.put("security", p.security());
        b.put("extras", p.extras());
        b.put("total", p.total());
        b.put("currency", card.currency());
        b.put("fxEurPerUnit", fx.eurPerUnit());
        b.put("fxSource", fx.source());
        return b;
    }

    private static String key(String origin, String destination) {
        return origin.trim().toUpperCase(Locale.ROOT) + "-" + destination.trim().toUpperCase(Locale.ROOT);
    }

    /** @param source none (EUR sheet), sheet (rate stated on the sheet) or default (configured fallback, flagged) */
    private record Fx(BigDecimal eurPerUnit, String source) {}
}
