package ai.aeiou.forwarder.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class SessionApiTest extends ApiTestBase {

    private static final OffsetDateTime T0 = OffsetDateTime.of(2026, 11, 21, 10, 0, 0, 123_456_000, ZoneOffset.UTC);

    @Test
    void pagesThroughAllSessionsNewestFirstWithoutGapsOrDuplicates() throws Exception {
        List<Long> expected = new ArrayList<>();
        for (int i = 0; i < 7; i++) expected.addFirst(data.session("AEI-" + i, "RFQ", "NEW", T0.plusSeconds(i), false));
        // Two sessions with the same timestamp: the id breaks the tie.
        long tieA = data.session("AEI-TA", "RFQ", "NEW", T0.minusSeconds(1), false);
        long tieB = data.session("AEI-TB", "RFQ", "NEW", T0.minusSeconds(1), false);
        expected.add(tieB);
        expected.add(tieA);

        List<Long> seen = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            JsonNode page = getJson("/api/sessions?limit=4" + (cursor == null ? "" : "&cursor=" + cursor));
            page.get("items").forEach(item -> seen.add(item.get("id").asLong()));
            cursor = page.get("nextCursor").isNull() ? null : page.get("nextCursor").asString();
            pages++;
        } while (cursor != null);

        assertThat(seen).containsExactlyElementsOf(expected);
        assertThat(pages).isEqualTo(3);
    }

    @Test
    void filtersByStatusTypeNeedsHumanAndText() throws Exception {
        data.session("AEI-AAA", "RFQ", "QUOTED", T0, false);
        data.session("AEI-BBB", "RFQ", "NEEDS_HUMAN", T0.plusSeconds(1), true);
        data.session("AEI-CCC", "TRACKING", "ANSWERED", T0.plusSeconds(2), false);

        assertThat(refs(getJson("/api/sessions?status=QUOTED"))).containsExactly("AEI-AAA");
        assertThat(refs(getJson("/api/sessions?type=TRACKING"))).containsExactly("AEI-CCC");
        assertThat(refs(getJson("/api/sessions?needsHuman=true"))).containsExactly("AEI-BBB");
        assertThat(refs(getJson("/api/sessions?q=bbb"))).containsExactly("AEI-BBB");
        assertThat(refs(getJson("/api/sessions?type=RFQ&needsHuman=false"))).containsExactly("AEI-AAA");
    }

    @Test
    void summaryCarriesLaneBoardVerdictAndMoneyAsString() throws Exception {
        long id = data.session("AEI-LANE", "RFQ", "QUOTED", T0, false);
        data.rfq(id, "FRA", "SIN");
        jdbc.sql("UPDATE session SET quoted_total = 1387.3, quoted_currency = 'EUR' WHERE id = :id").param("id", id).update();
        jdbc.sql("INSERT INTO board_result (request_key, session_id, verdict) VALUES ('r1', :id, 'accepted')")
                .param("id", id).update();

        JsonNode s = getJson("/api/sessions").get("items").get(0);
        assertThat(s.get("lane").asString()).isEqualTo("FRA → SIN");
        assertThat(s.get("boardVerdict").asString()).isEqualTo("accepted");
        assertThat(s.get("quotedTotal").asString()).isEqualTo("1387.30");
        assertThat(s.get("mode").isNull()).isTrue();
    }

    @Test
    void detailCombinesRfqCandidatesBidsAndBooking() throws Exception {
        long id = data.session("AEI-DET", "RFQ", "BOOKED", T0, false);
        data.rfq(id, "MUC", "JFK");
        jdbc.sql("""
                INSERT INTO price_candidate (session_id, source, airline_name, total, currency, total_eur, chosen,
                                             breakdown)
                VALUES (:id, 'sheet', 'Helix', 900, 'EUR', 900, true, '{"freight": 800}'),
                       (:id, 'portal', 'Vega', 950, 'EUR', 950, false, NULL)""").param("id", id).update();
        jdbc.sql("INSERT INTO carrier (name, kind, email) VALUES ('Vega', 'airline', 'vega@x.test')").update();
        jdbc.sql("""
                INSERT INTO bid_request (session_id, carrier_id, kind, price, currency)
                SELECT :id, id, 'airline', 940, 'EUR' FROM carrier""").param("id", id).update();
        data.booking(id, "MUC", "JFK", "booked");

        JsonNode d = getJson("/api/sessions/" + id);
        assertThat(d.at("/session/publicRef").asString()).isEqualTo("AEI-DET");
        assertThat(d.at("/rfq/weightKg").asString()).isEqualTo("120.50");
        assertThat(d.at("/candidates/0/airlineName").asString()).isEqualTo("Helix");
        assertThat(d.at("/candidates/0/breakdown/freight").asInt()).isEqualTo(800);
        assertThat(d.at("/bids/0/carrierName").asString()).isEqualTo("Vega");
        assertThat(d.at("/booking/price").asString()).isEqualTo("1387.35");
        assertThat(d.at("/booking/status").asString()).isEqualTo("booked");
    }

    @Test
    void timelineIsAscendingAndPaginated() throws Exception {
        long id = data.session("AEI-EV", "RFQ", "NEW", T0, false);
        long other = data.session("AEI-OTHER", "RFQ", "NEW", T0, false);
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ids.add(data.event(id, "mail.in", i == 0 ? "{\"x\":1}" : null));
            data.event(other, "mail.in", null);
        }
        JsonNode first = getJson("/api/sessions/" + id + "/events?limit=3");
        assertThat(first.get("items").values()).extracting(n -> n.get("id").asLong()).containsExactlyElementsOf(ids.subList(0, 3));
        assertThat(first.at("/items/0/hasPayload").asBoolean()).isTrue();
        assertThat(first.at("/items/1/hasPayload").asBoolean()).isFalse();
        assertThat(first.at("/items/0").has("payload")).isFalse();

        JsonNode second = getJson("/api/sessions/" + id + "/events?limit=3&cursor=" + first.get("nextCursor").asString());
        assertThat(second.get("items").values()).extracting(n -> n.get("id").asLong()).containsExactlyElementsOf(ids.subList(3, 5));
        assertThat(second.get("nextCursor").isNull()).isTrue();
    }

    @Test
    void sessionMailsAreOldestFirstWithAttachments() throws Exception {
        long id = data.session("AEI-M", "RFQ", "NEW", T0, false);
        long in = data.mail(1001, "in", id, "Need a quote", "Hello ".repeat(50), T0);
        data.mail(1002, "out", id, "Re: Need a quote", "Here you go", T0.plusMinutes(1));
        data.attachment(in, "goods.pdf", "application/pdf", new byte[]{1, 2, 3});

        JsonNode mails = getJson("/api/sessions/" + id + "/mails");
        assertThat(mails.values()).extracting(m -> m.get("externalId").asLong()).containsExactly(1001L, 1002L);
        assertThat(mails.at("/0/snippet").asString()).hasSize(160);
        assertThat(mails.at("/0/attachmentCount").asInt()).isEqualTo(1);
        assertThat(mails.at("/0/attachments/0/filename").asString()).isEqualTo("goods.pdf");
        assertThat(mails.at("/1/attachments").isEmpty()).isTrue();
    }

    @Test
    void draftIsServedOr404() throws Exception {
        long id = data.session("AEI-D", "RFQ", "QUOTED", T0, true);
        mvc.perform(get("/api/sessions/" + id + "/draft")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
        data.draft(id);
        JsonNode draft = getJson("/api/sessions/" + id + "/draft");
        assertThat(draft.get("to").asString()).isEqualTo("anna@customer.test");
        assertThat(draft.get("inReplyToExt").asLong()).isEqualTo(1001);
    }

    @Test
    void errorsHaveTheContractShape() throws Exception {
        mvc.perform(get("/api/sessions/999")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"))
                .andExpect(jsonPath("$.message").value("Session 999 not found"));
        mvc.perform(get("/api/sessions?cursor=bm9wZQ")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"));
        mvc.perform(get("/api/sessions?status=NOPE")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"));
    }

    private static List<String> refs(JsonNode page) {
        return page.get("items").values().stream().map(n -> n.get("publicRef").asString()).toList();
    }
}
