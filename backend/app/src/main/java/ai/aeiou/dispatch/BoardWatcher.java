package ai.aeiou.dispatch;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

@Component
class BoardWatcher {
  private static final Logger log = LoggerFactory.getLogger(BoardWatcher.class);
  private final Ledger ledger;
  private final FiveU upstream;
  private final boolean configured;

  BoardWatcher(Ledger ledger, FiveU upstream, @Value("${dispatch.key}") String key) {
    this.ledger = ledger;
    this.upstream = upstream;
    this.configured = !key.isBlank();
  }

  @Scheduled(initialDelay = 10000, fixedDelay = 60000)
  void poll() {
    if (!configured) return;
    try {
      JsonNode requests = ledger.tree(upstream.get("/v1/board/me")).path("requests");
      if (!requests.isArray()) {
        log.warn("5U board response is missing its requests array");
        return;
      }
      ledger.transactions().executeWithoutResult(tx -> {
        for (var result : requests) {
          // The board identifies RFQs by their original sent timestamp, without mailbox IDs.
          var received = Ledger.instant(result.path("sent_at").textValue());
          if (received == null || !result.path("verdict").isTextual()) continue;
          ledger.jdbc().update("""
              INSERT INTO board_result(received,result) VALUES(?,?)
              ON CONFLICT(received) DO UPDATE SET result=excluded.result
              WHERE board_result.result<>excluded.result
              """, received.toString(), ledger.write(result));
        }
      });
    } catch (RestClientException ex) {
      log.warn("5U board poll failed ({})", ex.getClass().getSimpleName());
    }
  }
}
