package ai.aeiou.dispatch;

import ai.aeiou.freight.Catalog;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
class MailboxWebhook {
  static final String PATH = "/api/v1/mailbox/webhook";
  private final Ledger ledger;
  private final Catalog catalog;
  private final Clock clock;
  private final ObjectMapper json;
  private final byte[] secret;
  private volatile Instant lastProcessed;

  MailboxWebhook(Ledger ledger, Catalog catalog, Clock clock, ObjectMapper json,
      @Value("${dispatch.webhook-secret}") String secret) {
    this.ledger = ledger;
    this.catalog = catalog;
    this.clock = clock;
    this.json = json;
    this.secret = secret.getBytes(StandardCharsets.UTF_8);
  }

  boolean enabled() {
    return secret.length > 0;
  }

  Instant lastProcessed() {
    return lastProcessed;
  }

  @PostMapping(PATH)
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void receive(@RequestHeader(value = "X-Webhook-Timestamp", defaultValue = "") String timestamp,
      @RequestHeader(value = "X-Webhook-Signature", defaultValue = "") String signature,
      @RequestBody byte[] body) throws GeneralSecurityException {
    if (!enabled()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    if (body.length > 256 * 1024) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE);
    if (!timestamp.matches("[0-9]{1,12}")
        || Math.abs(clock.instant().getEpochSecond() - Long.parseLong(timestamp)) > 300
        || !validSignature(timestamp, signature, body)) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }
    try {
      var event = json.readTree(body);
      if (event != null && "ping".equals(event.path("event").asText())) return;
      if (event != null && "mail.received".equals(event.path("event").asText())) {
        var message = event.path("message");
        var id = message.path("id");
        if (id.isIntegralNumber() && id.canConvertToLong() && id.longValue() > 0) {
          ledger.queuePush(id.longValue(), message);
          return;
        }
      }
    } catch (IOException ex) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
    }
    throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
  }

  private boolean validSignature(String timestamp, String signature, byte[] body)
      throws GeneralSecurityException {
    var mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secret, "HmacSHA256"));
    mac.update((timestamp + ".").getBytes(StandardCharsets.US_ASCII));
    String expected = "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
    return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
        signature.getBytes(StandardCharsets.US_ASCII));
  }

  @Scheduled(fixedDelayString = "${dispatch.poll-ms}")
  void processPending() {
    if (enabled() && ledger.ingestPushes(catalog, clock.instant())) lastProcessed = clock.instant();
  }
}
