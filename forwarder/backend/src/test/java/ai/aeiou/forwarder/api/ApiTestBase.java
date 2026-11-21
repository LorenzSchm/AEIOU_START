package ai.aeiou.forwarder.api;

import ai.aeiou.forwarder.api.port.DraftSender;
import ai.aeiou.forwarder.api.port.MailRerouter;
import ai.aeiou.forwarder.event.EventLog;
import ai.aeiou.forwarder.fiveu.FiveUClient;
import ai.aeiou.forwarder.session.SessionService;
import ai.aeiou.forwarder.settings.SettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full application context against a Testcontainers Postgres with the real read models. Collaborators owned by
 * other modules (event log, session service, settings, sending, routing, the 5U client) are mocked. Every subclass shares one
 * cached context and one container; tables are truncated before each test.
 */
@SpringBootTest(properties = {"app.poller.enabled=false", "app.mail.send-enabled=false",
        "app.fiveu.base-url=http://5u.invalid", "app.fiveu.api-key=test-team-key"})
@AutoConfigureMockMvc
@Import(ApiTestBase.Containers.class)
public abstract class ApiTestBase {

    @TestConfiguration(proxyBeanMethods = false)
    static class Containers {
        @Bean
        @ServiceConnection
        PostgreSQLContainer postgres() {
            return new PostgreSQLContainer("postgres:16-alpine");
        }
    }

    @MockitoBean protected EventLog eventLog;
    @MockitoBean protected SessionService sessionService;
    @MockitoBean protected SettingsService settingsService;
    @MockitoBean protected DraftSender draftSender;
    @MockitoBean protected MailRerouter mailRerouter;
    @MockitoBean protected FiveUClient fiveU;

    @Autowired protected MockMvc mvc;
    @Autowired protected JdbcClient jdbc;
    @Autowired protected JsonMapper mapper;

    protected TestData data;

    @BeforeEach
    void resetDatabase() {
        jdbc.sql("""
                TRUNCATE draft, board_result, tariff_lane, tariff, booking, price_candidate, bid_request, rfq,
                         llm_call, event, attachment, mail, session, airport, carrier, setting
                RESTART IDENTITY CASCADE""").update();
        data = new TestData(jdbc);
    }

    protected JsonNode getJson(String url) throws Exception {
        return mapper.readTree(mvc.perform(get(url)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    protected JsonNode postJson(String url, String body, int expectedStatus) throws Exception {
        return mapper.readTree(mvc.perform(post(url).contentType("application/json").content(body))
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString());
    }
}
