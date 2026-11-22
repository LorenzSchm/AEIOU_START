package ai.aeiou.dispatch;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.*;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class MailboxWebhookTest {
  private static final String SECRET = "local-test-signing-secret";
  private static final Instant NOW = Instant.parse("2026-11-21T19:30:00Z");
  @TempDir Path temp;

  @Test
  void verifiesAndDeduplicatesPushesWithoutSkippingOlderPollMail() throws Exception {
    var json = new ObjectMapper();
    var ledger = new Ledger(temp.resolve("webhook.sqlite").toString(), json);
    var webhook = new MailboxWebhook(ledger, new DispatchApplication().catalog(null),
        Clock.fixed(NOW, ZoneOffset.UTC), json, SECRET);
    var http = MockMvcBuilders.standaloneSetup(webhook).build();
    String body = """
        {"event":"mail.received","message":{"id":42,"from":"buyer@hackloop.mail",
        "subject":"RFQ FRA to MEL","body":"Fastest for 1431 kg and 10.71 cbm.",
        "sent_at":"2026-11-21T19:29:55Z"}}
        """;
    String timestamp = Long.toString(NOW.getEpochSecond());
    String expired = Long.toString(NOW.minusSeconds(301).getEpochSecond());
    http.perform(post(MailboxWebhook.PATH).content(body)
        .header("X-Webhook-Timestamp", timestamp).header("X-Webhook-Signature", "sha256=bad"))
        .andExpect(status().isUnauthorized());
    http.perform(post(MailboxWebhook.PATH).content(body)
        .header("X-Webhook-Timestamp", expired).header("X-Webhook-Signature", sign(expired, body)))
        .andExpect(status().isUnauthorized());
    assertEquals(0, ledger.jdbc().queryForObject("SELECT count(*) FROM mailbox_push", Integer.class));
    for (int i = 0; i < 2; i++) {
      http.perform(post(MailboxWebhook.PATH).content(body)
          .header("X-Webhook-Timestamp", timestamp)
          .header("X-Webhook-Signature", sign(timestamp, body)))
          .andExpect(status().isNoContent());
    }
    assertEquals(1, ledger.jdbc().queryForObject("SELECT count(*) FROM mailbox_push", Integer.class));
    webhook.processPending();
    assertEquals("RFQ FRA to MEL", ledger.mail(42).get("subject"));
    assertEquals(0, ledger.cursor());
    assertEquals(0, ledger.jdbc().queryForObject("SELECT count(*) FROM mailbox_push", Integer.class));
  }

  private static String sign(String timestamp, String body) throws Exception {
    var mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    return "sha256=" + HexFormat.of().formatHex(
        mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8)));
  }
}
