package ai.aeiou.forwarder.board;

import ai.aeiou.forwarder.fiveu.ApiResponse;
import ai.aeiou.forwarder.fiveu.FiveUClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

/**
 * Polls {@code /v1/board/me} every 60 s and hands the snapshot to {@link BoardIngest}. Only active on the single
 * instance that also polls mail ({@code app.poller.enabled=true}); tests and local runs never call 5U.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "app.poller.enabled", havingValue = "true")
public class BoardWatcher {

    private static final Logger log = LoggerFactory.getLogger(BoardWatcher.class);

    private final BoardIngest ingest;
    private final FiveUClient fiveU;
    private final boolean configured;

    public BoardWatcher(BoardIngest ingest, FiveUClient fiveU, @Value("${app.fiveu.api-key:}") String apiKey) {
        this.ingest = ingest;
        this.fiveU = fiveU;
        this.configured = !apiKey.isBlank();
        if (!configured) log.warn("Board watcher enabled but TEAM_API_KEY is empty; it will stay idle");
    }

    @Scheduled(initialDelayString = "${app.board.initial-delay-ms:10000}", fixedDelayString = "${app.board.interval-ms:60000}")
    void poll() {
        if (!configured) return;
        try {
            ApiResponse<JsonNode> res = fiveU.boardMe();
            if (!res.isSuccess()) {
                log.warn("Board poll failed: HTTP {}", res.status());
                return;
            }
            // The board schema is untyped in the 5U spec; BoardEntry parses the raw rows tolerantly.
            BoardIngest.Result r = ingest.ingest(res.rawBody());
            if (r.changed() > 0) log.info("Board: {} requests, {} changed, {} alerts", r.seen(), r.changed(), r.alerts());
        } catch (RestClientException e) {
            log.warn("Board poll failed: {}", e.getMessage());
        } catch (RuntimeException e) {
            log.error("Board snapshot could not be processed", e);
        }
    }
}
