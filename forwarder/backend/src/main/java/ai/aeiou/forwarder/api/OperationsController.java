package ai.aeiou.forwarder.api;

import ai.aeiou.forwarder.api.dto.ActiveShipment;
import ai.aeiou.forwarder.api.dto.BoardResult;
import ai.aeiou.forwarder.api.dto.Booking;
import ai.aeiou.forwarder.api.dto.Enums.BookingStatus;
import ai.aeiou.forwarder.api.dto.LlmCall;
import ai.aeiou.forwarder.api.dto.Page;
import ai.aeiou.forwarder.api.dto.Stats;
import ai.aeiou.forwarder.api.query.BookingQueries;
import ai.aeiou.forwarder.api.query.Cursor;
import ai.aeiou.forwarder.api.query.LlmCallQueries;
import ai.aeiou.forwarder.api.query.ReferenceQueries;
import ai.aeiou.forwarder.api.query.StatsQueries;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Bookings, globe data, LLM call inspection, KPIs and board results. */
@RestController
@RequestMapping("/api")
@Tag(name = "operations")
public class OperationsController {

    private static final int MAX_STATS_HOURS = 24 * 7;

    private final BookingQueries bookings;
    private final LlmCallQueries llmCalls;
    private final StatsQueries stats;
    private final ReferenceQueries reference;

    public OperationsController(BookingQueries bookings, LlmCallQueries llmCalls, StatsQueries stats,
                                ReferenceQueries reference) {
        this.bookings = bookings;
        this.llmCalls = llmCalls;
        this.stats = stats;
        this.reference = reference;
    }

    @GetMapping("/llm-calls")
    @Operation(operationId = "listLlmCalls", summary = "LLM calls, newest first, without prompt/response")
    public Page<LlmCall> llmCalls(@RequestParam(required = false) Long sessionId,
                                  @RequestParam(required = false) Integer limit,
                                  @RequestParam(required = false) String cursor) {
        return llmCalls.list(sessionId, Cursor.clampLimit(limit), Cursor.decode(cursor));
    }

    @GetMapping("/llm-calls/{id}")
    @Operation(operationId = "getLlmCall", summary = "One LLM call with request and response")
    public LlmCall llmCall(@PathVariable long id) {
        return llmCalls.detail(id).orElseThrow(() -> ApiException.notFound("LLM call", id));
    }

    @GetMapping("/bookings")
    @Operation(operationId = "listBookings", summary = "Bookings, newest first")
    public Page<Booking> bookings(@RequestParam(required = false) BookingStatus status,
                                  @RequestParam(required = false) Integer limit,
                                  @RequestParam(required = false) String cursor) {
        return bookings.list(status, Cursor.clampLimit(limit), Cursor.decode(cursor));
    }

    @GetMapping("/shipments/active")
    @Operation(operationId = "listActiveShipments", summary = "Booked/confirmed shipments with coordinates")
    public List<ActiveShipment> activeShipments() {
        return bookings.activeShipments();
    }

    @GetMapping("/stats")
    @Operation(operationId = "getStats", summary = "Dashboard KPIs; hourly series over the last `hours` hours")
    public Stats stats(@RequestParam(required = false, defaultValue = "24") int hours) {
        if (hours < 1 || hours > MAX_STATS_HOURS) {
            throw ApiException.badRequest("hours must be between 1 and " + MAX_STATS_HOURS);
        }
        return stats.stats(hours);
    }

    @GetMapping("/board")
    @Operation(operationId = "listBoardResults", summary = "Cached results from the 5U board (/v1/board/me)")
    public List<BoardResult> board() {
        return reference.boardResults();
    }
}
