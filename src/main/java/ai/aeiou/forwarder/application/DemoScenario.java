package ai.aeiou.forwarder.application;

import static ai.aeiou.forwarder.application.Workflow.*;
import static ai.aeiou.forwarder.domain.Json.*;

import ai.aeiou.forwarder.domain.Pricing;
import com.fasterxml.jackson.databind.node.*;
import java.math.*;
import java.time.*;
import org.springframework.stereotype.Component;

@Component
public class DemoScenario {
  private final Workflow flow;

  public DemoScenario(Workflow flow) {
    this.flow = flow;
  }

  public void start(Instant at, boolean door) {
    String id = door ? "s_101" : "s_100", ref = door ? "AE-101" : "AE-100";
    String scope =
        door
            ? "Fastest door-to-port service with pickup in 80331 Munich"
            : "Cheapest airport-to-airport service";
    var mail =
        mail(
            id + "_rfq",
            "RFQ",
            "buyer@hackloop.mail",
            TEAM,
            scope + " MUC to LAX",
            scope + " MUC to LAX. 100 kg gross total, 0.2 cbm.",
            at,
            null);
    byte[] manifest = str(mail, "bodyText").getBytes(java.nio.charset.StandardCharsets.UTF_8);
    mail.withArray("attachments")
        .add(
            obj(
                "id",
                id + "_manifest",
                "filename",
                "manifest.txt",
                "sizeBytes",
                manifest.length,
                "mediaType",
                "text/plain"));
    var d = flow.receive(id, ref, mail, at);
    flow.store.db.update(
        "insert into attachment values(?,?,?,?,?)",
        id + "_manifest",
        id,
        "manifest.txt",
        "text/plain",
        manifest);
    for (var contact : flow.catalog.contacts("airline", "LAX").stream().limit(2).toList())
      flow.queueMail(
          d,
          "ask-" + contact.name(),
          "BID_REQUEST",
          contact.email(),
          null,
          "Please quote " + ref + ", MUC to LAX, 100 kg, 0.2 cbm.",
          at);
    if (door)
      for (var contact : flow.catalog.contacts("trucker", "80").stream().limit(1).toList())
        flow.queueMail(
            d,
            "pickup-" + contact.name(),
            "BID_REQUEST",
            contact.email(),
            null,
            "Please quote pickup in 80331 Munich for " + ref + ".",
            at);
    var portal =
        offer(id + "_portal", "Demo portal carrier", "PORTAL", door ? "270" : "348", 2, at);
    portal.put("rateId", "R-DEMO" + ref.replace("-", "") + "0001");
    flow.store.schedule(id + ":portal", id, "OFFER", at.plusSeconds(12), portal);
    if (door) {
      var withPickup = offer(id + "_complete", "Demo door service", "BID", "330", 3, at);
      var lines = withPickup.withArray("lineItems");
      lines.removeAll();
      lines.add(
          obj(
              "kind",
              "INCLUSIVE",
              "label",
              "Air leg",
              "amount",
              Pricing.money(new BigDecimal("285"), "EUR"),
              "basis",
              "complete air service",
              "evidence",
              withPickup.get("evidence")));
      lines.add(
          obj(
              "kind",
              "PICKUP",
              "label",
              "Munich collection",
              "amount",
              Pricing.money(new BigDecimal("45"), "EUR"),
              "basis",
              "pickup zone 80",
              "evidence",
              withPickup.get("evidence")));
      withPickup.put("includesPickup", true);
      flow.store.schedule(id + ":combined", id, "OFFER", at.plusSeconds(45), withPickup);
    }
    var late =
        offer(
            id + "_late",
            "Demo late carrier",
            "BID",
            door ? "310" : "288",
            door ? 2 : 4,
            at.plusSeconds(140));
    late.put("includesPickup", door);
    flow.store.schedule(id + ":late", id, "OFFER", at.plusSeconds(140), late);
  }

  static ObjectNode offer(
      String id, String name, String source, String price, int days, Instant at) {
    var value = Pricing.money(new BigDecimal(price), "EUR");
    var evidence =
        arr(
            evidence(
                source,
                id,
                "demo offer",
                "EUR " + price + " inclusive, " + days + " days",
                at.toString()));
    return obj(
        "id",
        id,
        "source",
        source,
        "provider",
        name,
        "quotedTotal",
        value,
        "comparisonTotal",
        value,
        "transitDays",
        days,
        "eligible",
        true,
        "requestRevision",
        1,
        "reasons",
        arr(),
        "evidence",
        evidence,
        "validUntil",
        at.plusSeconds(600).toString(),
        "lineItems",
        arr(
            obj(
                "kind",
                "INCLUSIVE",
                "label",
                "Confirmed all-in offer",
                "amount",
                value,
                "basis",
                "requested shipment",
                "evidence",
                evidence)));
  }
}
