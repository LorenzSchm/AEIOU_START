package ai.aeiou.forwarder.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record SessionDetail(
        SessionSummary session,
        @Schema(nullable = true) Rfq rfq,
        List<PriceCandidate> candidates,
        List<BidRequest> bids,
        @Schema(nullable = true) Booking booking) {}
