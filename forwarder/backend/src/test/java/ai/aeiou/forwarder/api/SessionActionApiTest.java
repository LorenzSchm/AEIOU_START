package ai.aeiou.forwarder.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import ai.aeiou.forwarder.event.Actor;
import ai.aeiou.forwarder.router.MailType;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class SessionActionApiTest extends ApiTestBase {

    private long id;

    @BeforeEach
    void seed() {
        id = data.session("AEI-ACT", "RFQ", "NEEDS_HUMAN", OffsetDateTime.now(), true);
    }

    @Test
    void takeOverAndReleaseToggleAutopilotAndLogHumanEvents() throws Exception {
        JsonNode off = postJson(actions(), "{\"action\":\"take_over\"}", 200);
        assertThat(off.get("autopilot").asBoolean()).isFalse();
        verify(eventLog).append(eq(id), isNull(), eq("human.take_over"), eq(Actor.human), anyString(), any());

        JsonNode on = postJson(actions(), "{\"action\":\"release\"}", 200);
        assertThat(on.get("autopilot").asBoolean()).isTrue();
        verify(eventLog).append(eq(id), isNull(), eq("human.release"), eq(Actor.human), anyString(), any());
    }

    @Test
    void resolveClearsNeedsHuman() throws Exception {
        postJson(actions(), "{\"action\":\"resolve\",\"note\":\"checked by phone\"}", 200);
        verify(sessionService).clearNeedsHuman(id);
        verify(eventLog).append(eq(id), isNull(), eq("human.resolve"), eq(Actor.human),
                eq("Resolved: checked by phone"), any());
    }

    @Test
    void approveNeedsADraftAndDelegatesSending() throws Exception {
        JsonNode err = postJson(actions(), "{\"action\":\"approve\"}", 409);
        assertThat(err.get("error").asString()).isEqualTo("no_draft");
        verifyNoInteractions(draftSender);

        data.draft(id);
        postJson(actions(), "{\"action\":\"approve\"}", 200);
        verify(draftSender).sendDraft(id, null);
        verify(eventLog).append(eq(id), isNull(), eq("human.approve"), eq(Actor.human), anyString(), any());
    }

    @Test
    void editAndSendRequiresBody() throws Exception {
        data.draft(id);
        postJson(actions(), "{\"action\":\"edit_and_send\"}", 400);
        postJson(actions(), "{\"action\":\"edit_and_send\",\"body\":\"Dear Anna, EUR 900.00\"}", 200);
        verify(draftSender).sendDraft(id, "Dear Anna, EUR 900.00");
        verify(eventLog).append(eq(id), isNull(), eq("human.edit_send"), eq(Actor.human), anyString(), any());
    }

    @Test
    void rerouteTargetsTheLatestInboundMail() throws Exception {
        OffsetDateTime t = OffsetDateTime.now();
        data.mail(11, "in", id, "first", "a", t.minusMinutes(5));
        long latest = data.mail(12, "in", id, "second", "b", t.minusMinutes(1));
        data.mail(13, "out", id, "reply", "c", t);

        postJson(actions(), "{\"action\":\"reroute\",\"type\":\"NOPE\"}", 400);
        postJson(actions(), "{\"action\":\"reroute\",\"type\":\"FOLLOW_UP\"}", 200);
        verify(mailRerouter).reroute(latest, MailType.FOLLOW_UP);
        verify(eventLog).append(eq(id), eq(latest), eq("human.reroute"), eq(Actor.human), anyString(), any());
    }

    @Test
    void unknownSessionIs404AndNothingHappens() throws Exception {
        postJson("/api/sessions/9999/actions", "{\"action\":\"take_over\"}", 404);
        verify(eventLog, never()).append(any(), any(), anyString(), any(), anyString(), any());
    }

    private String actions() {
        return "/api/sessions/" + id + "/actions";
    }
}
