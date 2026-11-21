package ai.aeiou.forwarder.api;

import ai.aeiou.forwarder.api.dto.Draft;
import ai.aeiou.forwarder.api.dto.Enums.SessionType;
import ai.aeiou.forwarder.api.dto.EventItem;
import ai.aeiou.forwarder.api.dto.MailDetail;
import ai.aeiou.forwarder.api.dto.Page;
import ai.aeiou.forwarder.api.dto.SessionAction;
import ai.aeiou.forwarder.api.dto.SessionDetail;
import ai.aeiou.forwarder.api.dto.SessionSummary;
import ai.aeiou.forwarder.api.port.SessionActions;
import ai.aeiou.forwarder.api.query.Cursor;
import ai.aeiou.forwarder.api.query.EventQueries;
import ai.aeiou.forwarder.api.query.MailQueries;
import ai.aeiou.forwarder.api.query.SessionQueries;
import ai.aeiou.forwarder.router.MailType;
import ai.aeiou.forwarder.session.SessionStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sessions")
@Tag(name = "sessions")
public class SessionController {

    private final SessionQueries sessions;
    private final EventQueries events;
    private final MailQueries mails;
    private final SessionActions actions;

    public SessionController(SessionQueries sessions, EventQueries events, MailQueries mails, SessionActions actions) {
        this.sessions = sessions;
        this.events = events;
        this.mails = mails;
        this.actions = actions;
    }

    @GetMapping
    @Operation(operationId = "listSessions", summary = "Live board, most recently active first")
    public Page<SessionSummary> list(@RequestParam(required = false) SessionStatus status,
                                     @RequestParam(required = false) SessionType type,
                                     @RequestParam(required = false) Boolean needsHuman,
                                     @RequestParam(required = false) String q,
                                     @RequestParam(required = false) Integer limit,
                                     @RequestParam(required = false) String cursor) {
        return sessions.list(status, type, needsHuman, q, Cursor.clampLimit(limit), Cursor.decode(cursor));
    }

    @GetMapping("/{id}")
    @Operation(operationId = "getSession", summary = "Summary, RFQ, price candidates, bids and booking")
    public SessionDetail get(@PathVariable long id) {
        return sessions.detail(id).orElseThrow(() -> ApiException.notFound("Session", id));
    }

    @GetMapping("/{id}/events")
    @Operation(operationId = "listSessionEvents", summary = "Timeline, oldest first")
    public Page<EventItem> events(@PathVariable long id,
                                  @RequestParam(required = false) Integer limit,
                                  @RequestParam(required = false) String cursor) {
        requireSession(id);
        return events.forSession(id, Cursor.clampLimit(limit), Cursor.decode(cursor));
    }

    @GetMapping("/{id}/mails")
    @Operation(operationId = "listSessionMails", summary = "All mails of the session, oldest first")
    public List<MailDetail> mails(@PathVariable long id) {
        requireSession(id);
        return mails.forSession(id);
    }

    @GetMapping("/{id}/draft")
    @Operation(operationId = "getSessionDraft", summary = "Pending outbound mail waiting for a human, or 404")
    public Draft draft(@PathVariable long id) {
        return sessions.draft(id).orElseThrow(() -> ApiException.notFound("Draft for session", id));
    }

    @PostMapping("/{id}/actions")
    @Operation(operationId = "sessionAction", summary = "Human action; returns the updated session")
    public SessionSummary action(@PathVariable long id, @RequestBody SessionAction request) {
        requireSession(id);
        if (request.action() == null) throw ApiException.badRequest("action is required");
        switch (request.action()) {
            case approve -> actions.approve(id);
            case edit_and_send -> actions.editAndSend(id, requireText(request.body(), "body"));
            case reroute -> actions.reroute(id, mailType(request.type()));
            case take_over -> actions.takeOver(id);
            case release -> actions.release(id);
            case resolve -> actions.resolve(id, request.note());
        }
        return sessions.summary(id).orElseThrow(() -> ApiException.notFound("Session", id));
    }

    private void requireSession(long id) {
        if (!sessions.exists(id)) throw ApiException.notFound("Session", id);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw ApiException.badRequest(field + " is required");
        return value;
    }

    private static MailType mailType(String value) {
        try {
            return MailType.valueOf(requireText(value, "type"));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Unknown mail type: " + value);
        }
    }
}
