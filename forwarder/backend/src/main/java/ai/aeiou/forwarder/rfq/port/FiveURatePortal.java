package ai.aeiou.forwarder.rfq.port;

import ai.aeiou.forwarder.fiveu.ApiResponse;
import ai.aeiou.forwarder.fiveu.FiveUClient;
import ai.aeiou.forwarder.fiveu.PortalModels;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

/**
 * {@link RatePortal} over the shared {@link FiveUClient}. Lanes change rarely (at most with the hourly setup
 * change), so they are cached for {@link #LANES_TTL}.
 */
@Component
public class FiveURatePortal implements RatePortal {

    static final Duration LANES_TTL = Duration.ofSeconds(60);
    private static final Logger log = LoggerFactory.getLogger(FiveURatePortal.class);

    private final FiveUClient fiveU;
    private final Clock clock;
    private volatile CachedLanes cachedLanes;

    @Autowired
    public FiveURatePortal(FiveUClient fiveU) {
        this(fiveU, Clock.systemUTC());
    }

    FiveURatePortal(FiveUClient fiveU, Clock clock) {
        this.fiveU = fiveU;
        this.clock = clock;
    }

    @Override
    public List<Lane> lanes() {
        CachedLanes cached = cachedLanes;
        if (cached != null && clock.instant().isBefore(cached.expiresAt())) return cached.lanes();
        ApiResponse<PortalModels.Ports> res = fiveU.ports();
        if (!res.isSuccess() || res.body() == null) {
            throw new IllegalStateException("Portal ports answered HTTP " + res.status());
        }
        List<Lane> lanes = nullSafe(res.body().lanes()).stream()
                .map(l -> new Lane(l.origin(), l.destination(),
                        nullSafe(l.airlines()).stream().map(a -> new Airline(a.code(), a.name())).toList()))
                .toList();
        cachedLanes = new CachedLanes(lanes, clock.instant().plus(LANES_TTL));
        return lanes;
    }

    @Override
    public Optional<Brackets> brackets(String airline) {
        ApiResponse<PortalModels.Brackets> res = fiveU.brackets(airline);
        if (res.status() != 200 || res.body() == null) return Optional.empty();
        PortalModels.Brackets b = res.body();
        return Optional.of(new Brackets(b.airline(), b.airlineCode(), nullSafe(b.breaksKg())));
    }

    @Override
    public RateResult rate(RateQuery q) {
        try {
            ApiResponse<PortalModels.Rate> res = fiveU.rate(q.origin(), q.destination(), q.airline(), q.weightKg(), q.volumeCbm());
            boolean ok = res.status() == 200 && res.body() != null;
            OptionalInt retryAfter = res.retryAfterSeconds();
            return new RateResult(res.status(), ok ? toRate(res.body()) : null, boxed(res.rateLimitLimit()),
                    boxed(res.rateLimitRemaining()), retryAfter.isPresent() ? Duration.ofSeconds(retryAfter.getAsInt()) : null,
                    ok ? null : res.errorMessage());
        } catch (RestClientException e) {
            log.warn("Portal rate call failed for {}: {}", q, e.getMessage());
            return new RateResult(0, null, null, null, null, e.getMessage());
        }
    }

    private static Rate toRate(PortalModels.Rate r) {
        return new Rate(r.rateId(), r.airline(), r.airlineCode(), r.total(), r.currency(), r.fxEurPerUsd(),
                r.chargeableWeightKg(), r.transitDays(), r.validUntil() == null ? null : OffsetDateTime.parse(r.validUntil()),
                r.datasetVersion());
    }

    private static Integer boxed(OptionalInt value) {
        return value.isPresent() ? value.getAsInt() : null;
    }

    private static <T> List<T> nullSafe(List<T> list) {
        return list == null ? List.of() : list;
    }

    private record CachedLanes(List<Lane> lanes, Instant expiresAt) {}
}
