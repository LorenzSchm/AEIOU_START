package ai.aeiou.forwarder.api;

import static ai.aeiou.forwarder.domain.Json.*;

import ai.aeiou.forwarder.application.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Clock;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("live")
public class MailboxWebhook {
  private final LiveIntake intake;
  private final Clock clock;
  private final String secret;

  public MailboxWebhook(
      LiveIntake intake, Clock clock, @Value("${WEBHOOK_SIGNING_SECRET:}") String secret) {
    this.intake = intake;
    this.clock = clock;
    this.secret = secret;
  }

  @PostMapping("/hooks/5u/mailbox")
  public ResponseEntity<Void> receive(@RequestHeader HttpHeaders headers, @RequestBody byte[] raw)
      throws GeneralSecurityException {
    if (secret.isBlank()) return ResponseEntity.notFound().build();
    String stamp = headers.getFirst("X-Webhook-Timestamp"),
        signature = headers.getFirst("X-Webhook-Signature");
    if (stamp == null
        || !stamp.matches("[0-9]{1,12}")
        || signature == null
        || !signature.matches("sha256=[0-9a-f]{64}")
        || Math.abs(clock.instant().getEpochSecond() - Long.parseLong(stamp)) > 300)
      return ResponseEntity.status(401).build();
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    mac.update((stamp + ".").getBytes(StandardCharsets.US_ASCII));
    if (!MessageDigest.isEqual(mac.doFinal(raw), HexFormat.of().parseHex(signature.substring(7))))
      return ResponseEntity.status(401).build();
    try {
      var payload = M.readTree(raw);
      if (payload == null || !payload.isObject()) return ResponseEntity.badRequest().build();
      String event = str(payload, "event");
      if (!event.equals(headers.getFirst("X-Webhook-Event")))
        return ResponseEntity.badRequest().build();
      if (event.equals("ping") && "aeiou".equalsIgnoreCase(str(payload, "team")))
        return ResponseEntity.noContent().build();
      var mail = payload.path("message");
      if (!event.equals("mail.received")
          || !mail.path("id").isIntegralNumber()
          || !mail.path("id").canConvertToLong()
          || mail.path("id").asLong() <= 0
          || !str(mail, "id").equals(headers.getFirst("X-Webhook-Id")))
        return ResponseEntity.badRequest().build();
      intake.receivePush(mail);
      return ResponseEntity.noContent().build();
    } catch (java.io.IOException invalidJson) {
      return ResponseEntity.badRequest().build();
    }
  }
}
