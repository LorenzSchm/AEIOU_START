package ai.aeiou.forwarder.booking.port;

import ai.aeiou.forwarder.fiveu.ApiResponse;
import ai.aeiou.forwarder.fiveu.FiveUClient;
import ai.aeiou.forwarder.fiveu.PortalModels.Booking;
import ai.aeiou.forwarder.fiveu.PortalModels.Rate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

/** {@link BookingPortal} over the shared {@link FiveUClient}. */
@Component
public class FiveUBookingPortal implements BookingPortal {

    private static final Logger log = LoggerFactory.getLogger(FiveUBookingPortal.class);

    private final FiveUClient fiveU;

    public FiveUBookingPortal(FiveUClient fiveU) {
        this.fiveU = fiveU;
    }

    @Override
    public BookingResult book(String rateId, String teamReference) {
        try {
            ApiResponse<Booking> res = fiveU.book(rateId, teamReference);
            boolean booked = res.status() == 200 || res.status() == 201;
            return new BookingResult(res.status(), booked ? res.body() : null, booked ? null : res.errorMessage());
        } catch (RestClientException e) {
            log.warn("Portal booking unreachable for {}: {}", rateId, e.getMessage());
            return new BookingResult(UNREACHABLE, null, e.getMessage());
        }
    }

    @Override
    public RateResult rate(RateQuery q) {
        try {
            ApiResponse<Rate> res = fiveU.rate(q.origin(), q.destination(), q.airline(), q.weightKg(), q.volumeCbm());
            boolean ok = res.status() == 200;
            return new RateResult(res.status(), ok ? res.body() : null, ok ? null : res.errorMessage());
        } catch (RestClientException e) {
            log.warn("Portal rate unreachable for {}: {}", q, e.getMessage());
            return new RateResult(UNREACHABLE, null, e.getMessage());
        }
    }
}
