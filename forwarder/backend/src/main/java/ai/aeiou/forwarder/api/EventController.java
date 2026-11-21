package ai.aeiou.forwarder.api;

import ai.aeiou.forwarder.api.dto.EventItem;
import ai.aeiou.forwarder.api.dto.EventPayload;
import ai.aeiou.forwarder.api.dto.Page;
import ai.aeiou.forwarder.api.query.Cursor;
import ai.aeiou.forwarder.api.query.EventQueries;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/events")
@Tag(name = "events")
public class EventController {

    private final EventQueries events;

    public EventController(EventQueries events) {
        this.events = events;
    }

    @GetMapping
    @Operation(operationId = "listEvents", summary = "Global audit feed, newest first")
    public Page<EventItem> list(@RequestParam(required = false) String kind,
                                @RequestParam(required = false) Integer limit,
                                @RequestParam(required = false) String cursor) {
        return events.feed(kind, Cursor.clampLimit(limit), Cursor.decode(cursor));
    }

    @GetMapping("/{id}/payload")
    @Operation(operationId = "getEventPayload", summary = "Raw JSON payload of one event")
    public EventPayload payload(@PathVariable long id) {
        return events.payload(id).orElseThrow(() -> ApiException.notFound("Event", id));
    }
}
