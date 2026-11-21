package ai.aeiou.forwarder.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Mailbox, global feed, globe, stats, board, LLM calls and tariffs. */
class ReadApiTest extends ApiTestBase {

    private final OffsetDateTime now = OffsetDateTime.now();

    @Test
    void mailSearchUsesFullTextAndDirection() throws Exception {
        long s = data.session("AEI-MAIL", "RFQ", "NEW", now, false);
        data.mail(1, "in", s, "Quote request Singapore", "Please quote 120 kg to SIN", now.minusMinutes(3));
        data.mail(2, "out", s, "Re: Quote request Singapore", "Our offer", now.minusMinutes(2));
        data.mail(3, "in", null, "Tariff December", "Attached our new rates", now.minusMinutes(1));

        assertThat(externalIds(getJson("/api/mails"))).containsExactly(3L, 2L, 1L);
        assertThat(externalIds(getJson("/api/mails?q=singapore"))).containsExactly(2L, 1L);
        assertThat(externalIds(getJson("/api/mails?q=singapore&direction=in"))).containsExactly(1L);
        JsonNode page = getJson("/api/mails?limit=2");
        assertThat(externalIds(getJson("/api/mails?limit=2&cursor=" + page.get("nextCursor").asString())))
                .containsExactly(1L);
        assertThat(getJson("/api/mails?q=singapore").at("/items/0/sessionRef").asString()).isEqualTo("AEI-MAIL");
    }

    @Test
    void attachmentDownloadSetsTypeAndDisposition() throws Exception {
        long mail = data.mail(5, "in", null, "Sheet", "see attached", now);
        long a = data.attachment(mail, "Tarif Dezember.xlsx", "application/vnd.ms-excel", new byte[]{9, 8, 7});

        mvc.perform(get("/api/attachments/" + a))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/vnd.ms-excel"))
                .andExpect(header().string("Content-Disposition",
                        startsWith("attachment; filename=")))
                .andExpect(content().bytes(new byte[]{9, 8, 7}));
        mvc.perform(get("/api/attachments/999")).andExpect(status().isNotFound());
    }

    @Test
    void globalFeedIsNewestFirstAndPayloadIsLazy() throws Exception {
        long s = data.session("AEI-FEED", "RFQ", "NEW", now, false);
        long a = data.event(s, "mail.in", null);
        long b = data.event(s, "llm.call", "{\"model\":\"gpt-4o-mini\",\"tokens\":42}");
        long c = data.event(null, "system.alert", null);

        assertThat(getJson("/api/events").get("items").values()).extracting(n -> n.get("id").asLong())
                .containsExactly(c, b, a);
        assertThat(getJson("/api/events?kind=llm.call").get("items").values()).extracting(n -> n.get("id").asLong())
                .containsExactly(b);
        JsonNode payload = getJson("/api/events/" + b + "/payload");
        assertThat(payload.at("/payload/tokens").asInt()).isEqualTo(42);
        assertThat(getJson("/api/events/" + a + "/payload").get("payload").isNull()).isTrue();
    }

    @Test
    void activeShipmentsJoinAirportCoordinates() throws Exception {
        data.airport("FRA", "Frankfurt", 50.03, 8.57);
        data.airport("SIN", "Singapore", 1.36, 103.99);
        long s1 = data.session("AEI-S1", "RFQ", "BOOKED", now, false);
        long s2 = data.session("AEI-S2", "RFQ", "BOOKED", now, false);
        data.booking(s1, "FRA", "SIN", "confirmed");
        data.booking(s2, "FRA", "SIN", "failed");

        JsonNode list = getJson("/api/shipments/active");
        assertThat(list.size()).isEqualTo(1);
        assertThat(list.at("/0/publicRef").asString()).isEqualTo("AEI-S1");
        assertThat(list.at("/0/destination/lat").asDouble()).isEqualTo(1.36);
        assertThat(list.at("/0/origin/city").asString()).isEqualTo("Frankfurt");

        assertThat(getJson("/api/bookings?status=failed").at("/items/0/sessionId").asLong()).isEqualTo(s2);
    }

    @Test
    void statsAggregateResponseTimesCostsAndVerdicts() throws Exception {
        OffsetDateTime t = now.minusMinutes(30);
        long s = data.session("AEI-ST", "RFQ", "QUOTED", t, false);
        jdbc.sql("UPDATE session SET quoted_at = :q WHERE id = :id").param("q", t.plusSeconds(85)).param("id", s).update();
        data.mail(100, "in", s, "RFQ", "quote please", t);
        data.mail(101, "out", s, "Re: RFQ", "offer", t.plusSeconds(90));
        jdbc.sql("INSERT INTO llm_call (session_id, purpose, model, cost_usd) VALUES (:s, 'router', 'm', 0.0015)")
                .param("s", s).update();
        jdbc.sql("INSERT INTO board_result (request_key, session_id, verdict) VALUES ('k1', :s, 'rejected')")
                .param("s", s).update();
        data.session("AEI-TR", "TRACKING", "ANSWERED", t, false);

        JsonNode stats = getJson("/api/stats?hours=3");
        assertThat(stats.get("sessionsTotal").asLong()).isEqualTo(2);
        assertThat(stats.get("rfqsTotal").asLong()).isEqualTo(1);
        assertThat(stats.get("quotesSent").asLong()).isEqualTo(1);
        assertThat(stats.get("rejected").asLong()).isEqualTo(1);
        assertThat(stats.get("llmCostUsdTotal").asString()).isEqualTo("0.0015");
        assertThat(stats.get("llmCostUsdPerQuote").asString()).isEqualTo("0.0015");
        assertThat(stats.get("medianResponseSeconds").asDouble()).isEqualTo(90.0);
        assertThat(stats.at("/statusCounts/QUOTED").asLong()).isEqualTo(1);
        assertThat(stats.at("/statusCounts/FAILED").asLong()).isZero();
        assertThat(stats.get("hourly").size()).isEqualTo(3);
        long rfqs = 0;
        for (JsonNode h : stats.get("hourly")) rfqs += h.get("rfqs").asLong();
        assertThat(rfqs).isEqualTo(1);

        mvc.perform(get("/api/stats?hours=0")).andExpect(status().isBadRequest());
    }

    @Test
    void boardLlmCallsTariffsAndCarriers() throws Exception {
        long s = data.session("AEI-B", "RFQ", "QUOTED", now, false);
        jdbc.sql("""
                INSERT INTO board_result (request_key, session_id, round, verdict, reason_code, points)
                VALUES ('req-1', :s, '3', 'accepted', NULL, '{"price": 10, "speed": 5.5, "note": "x"}')""")
                .param("s", s).update();
        JsonNode board = getJson("/api/board");
        assertThat(board.at("/0/publicRef").asString()).isEqualTo("AEI-B");
        assertThat(board.at("/0/points/speed").asDouble()).isEqualTo(5.5);
        assertThat(board.at("/0/points").has("note")).isFalse();

        long call = jdbc.sql("""
                INSERT INTO llm_call (session_id, purpose, model, request, response)
                VALUES (:s, 'extract', 'gpt-4o-mini', '{"messages": []}', '{"ok": true}') RETURNING id""")
                .param("s", s).query(Long.class).single();
        JsonNode calls = getJson("/api/llm-calls?sessionId=" + s);
        assertThat(calls.at("/items/0/request").isNull()).isTrue();  // payloads only on the detail endpoint
        assertThat(getJson("/api/llm-calls/" + call).at("/response/ok").asBoolean()).isTrue();

        long tariff = jdbc.sql("""
                INSERT INTO tariff (airline_name, version, source, currency, status)
                VALUES ('Helix', '2026-12', 'mail', 'EUR', 'pending_review') RETURNING id""")
                .query(Long.class).single();
        jdbc.sql("""
                INSERT INTO tariff_lane (tariff_id, origin, destination, minimum, breaks, fuel_per_kg)
                VALUES (:t, 'FRA', 'SIN', 75, '[{"fromKg": 0, "rate": 5.92}, {"fromKg": 45, "rate": 5.0}]', 0.85)""")
                .param("t", tariff).update();
        JsonNode detail = getJson("/api/tariffs/" + tariff);
        assertThat(detail.get("laneCount").asInt()).isEqualTo(1);
        assertThat(detail.at("/lanes/0/minimum").asString()).isEqualTo("75.00");
        assertThat(detail.at("/lanes/0/breaks/1/rate").asString()).isEqualTo("5.00");
        assertThat(detail.at("/lanes/0/fuelPerKg").asString()).isEqualTo("0.85");

        assertThat(postJson("/api/tariffs/" + tariff + "/activate", "", 200).get("status").asString()).isEqualTo("active");
        // an active tariff can be pulled (e.g. a wrongly parsed sheet); unknown ids are 404
        assertThat(postJson("/api/tariffs/" + tariff + "/reject", "", 200).get("status").asString()).isEqualTo("rejected");
        assertThat(postJson("/api/tariffs/999999/reject", "", 404).get("error").asString()).isEqualTo("not_found");

        jdbc.sql("INSERT INTO carrier (name, kind, email, serves) VALUES ('Vega', 'airline', 'v@x.test', '{SIN,BKK}')")
                .update();
        assertThat(getJson("/api/carriers").at("/0/serves/1").asString()).isEqualTo("BKK");
    }

    private static List<Long> externalIds(JsonNode page) {
        return page.get("items").values().stream().map(n -> n.get("externalId").asLong()).toList();
    }
}
