package ai.aeiou.forwarder.api.dto;

import ai.aeiou.forwarder.session.SessionStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@Schema(description = "Dashboard KPIs. Totals are all-time, hourly covers the requested window.")
public record Stats(
        long sessionsTotal,
        long rfqsTotal,
        long quotesSent,
        long booked,
        long needsHuman,
        @Schema(description = "From board results") long accepted,
        @Schema(description = "From board results") long rejected,
        String llmCostUsdTotal,
        String llmCostUsdPerQuote,
        @Schema(nullable = true, description = "First quote mail out minus the RFQ mail in") Double medianResponseSeconds,
        @Schema(nullable = true) Double p90ResponseSeconds,
        List<Hour> hourly,
        @Schema(description = "Every SessionStatus is present, zero when unused") Map<SessionStatus, Long> statusCounts) {

    @Schema(name = "StatsHour")
    public record Hour(
            OffsetDateTime hour,
            long rfqs,
            long quotes,
            long accepted,
            long rejected,
            String llmCostUsd,
            @Schema(nullable = true) Double medianResponseSeconds) {}
}
