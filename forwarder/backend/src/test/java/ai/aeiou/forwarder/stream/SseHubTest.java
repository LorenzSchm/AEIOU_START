package ai.aeiou.forwarder.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

import ai.aeiou.forwarder.api.ApiTestBase;
import ai.aeiou.forwarder.event.EventAppended;
import ai.aeiou.forwarder.event.SessionChanged;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.web.MockHttpServletResponse;

class SseHubTest extends ApiTestBase {

    @Autowired ApplicationEventPublisher publisher;

    @Test
    void streamsEventsSessionsAndCoalescesBursts() throws Exception {
        long id = data.session("AEI-SSE", "RFQ", "PRICING", OffsetDateTime.now(), false);
        MockHttpServletResponse response = mvc.perform(get("/api/stream"))
                .andExpect(request().asyncStarted())
                .andReturn()
                .getResponse();
        assertThat(response.getContentType()).startsWith("text/event-stream");
        awaitContent(response, "event:ping");

        publisher.publishEvent(new EventAppended(77, id, null, "pricing.decision", "system", "Chose Helix",
                OffsetDateTime.now(), true));
        String afterEvent = awaitContent(response, "event:event");
        assertThat(afterEvent).contains("id:77").contains("\"kind\":\"pricing.decision\"").contains("\"hasPayload\":true");

        for (int i = 0; i < 20; i++) publisher.publishEvent(new SessionChanged(id));
        String afterSession = awaitContent(response, "event:session");
        assertThat(afterSession).contains("\"publicRef\":\"AEI-SSE\"");
        Thread.sleep(SseHub.COALESCE_WINDOW.toMillis() * 3);
        assertThat(count(response.getContentAsString(), "event:session")).isEqualTo(1);
    }

    private static String awaitContent(MockHttpServletResponse response, String needle) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
        while (Instant.now().isBefore(deadline)) {
            String body = response.getContentAsString();
            if (body.contains(needle)) return body;
            Thread.sleep(20);
        }
        throw new AssertionError("No '" + needle + "' in SSE stream: " + response.getContentAsString());
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) n++;
        return n;
    }
}
