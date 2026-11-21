package ai.aeiou.forwarder;

import ai.aeiou.forwarder.fiveu.FiveUClient;
import ai.aeiou.forwarder.llm.LlmClient;
import ai.aeiou.forwarder.router.MailHandler;
import ai.aeiou.forwarder.router.MailType;
import ai.aeiou.forwarder.router.RoutedMail;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base for core DB tests: a real Postgres (Testcontainers) with Flyway, and the two outbound
 * clients mocked, so no test ever reaches the 5U API or the LLM gateway. Subclasses share one
 * Spring context and one container.
 */
@SpringBootTest(properties = {
        "app.fiveu.base-url=http://localhost:9",
        "app.fiveu.api-key=test-key-not-real",
        "app.poller.enabled=false",
        "app.mail.send-enabled=false"
})
@Import(CoreDatabaseTest.Containers.class)
public abstract class CoreDatabaseTest {

    @MockitoBean
    protected FiveUClient fiveU;

    @MockitoBean
    protected LlmClient llm;

    @TestConfiguration(proxyBeanMethods = false)
    static class Containers {
        @Bean
        @ServiceConnection
        PostgreSQLContainer postgres() {
            return new PostgreSQLContainer("postgres:16-alpine");
        }

        @Bean
        RecordingHandler recordingHandler() {
            return new RecordingHandler();
        }
    }

    /** Test double that records every routed mail it receives. */
    public static class RecordingHandler implements MailHandler {
        public final List<RoutedMail> received = new CopyOnWriteArrayList<>();

        @Override
        public Set<MailType> supports() {
            return EnumSet.allOf(MailType.class);
        }

        @Override
        public void handle(RoutedMail mail) {
            received.add(mail);
        }
    }
}
