package ai.aeiou.forwarder.application;

import static ai.aeiou.forwarder.domain.Json.*;

import ai.aeiou.forwarder.adapters.Catalog;
import ai.aeiou.forwarder.domain.BidTiming;
import ai.aeiou.forwarder.domain.Pricing;
import ai.aeiou.forwarder.infrastructure.Ledger;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class ShadowPolicy {
  private final Ledger store;
  private final Catalog catalog;

  public ShadowPolicy(Ledger store, Catalog catalog) {
    this.store = store;
    this.catalog = catalog;
  }

  public void evaluate(ObjectNode current, Instant at) {
    String self = Workflow.sid(current);
    List<BidTiming.Observation> history = new ArrayList<>();
    var solicited =
        store.db.query(
            "select shipment_id,data from mail",
            (r, i) -> Map.entry(r.getString(1), parse(r.getString(2))));
    var archived = new HashMap<String, JsonNode>();
    for (var d : store.read("select data from shipment"))
      archived.put(d.at("/session/id").asText(), d);
    for (var item : solicited) {
      JsonNode m = item.getValue();
      if (item.getKey().equals(self) || !str(m, "category").equals("BID_REQUEST")) continue;
      var other = archived.get(item.getKey());
      if (other == null || !other.has("recorded")) continue;
      Instant known = Instant.parse(other.at("/recorded/at").asText());
      if (!known.isBefore(at)) continue;
      String name = catalog.byEmail(str(m, "to")).map(Catalog.Contact::name).orElse("");
      var response =
          other
              .path("candidates")
              .valueStream()
              .filter(c -> str(c, "source").equals("BID") && str(c, "provider").equals(name))
              .findFirst();
      var baseline =
          other
              .path("candidates")
              .valueStream()
              .filter(c -> str(c, "id").equals(other.at("/quote/candidateId").asText()))
              .findFirst();
      if (baseline.isEmpty()) continue;
      Instant arrival = null;
      boolean improves = false;
      if (response.isPresent()) {
        var c = response.get();
        arrival = Instant.parse(str(other.path("arrival"), str(c, "id")));
        if (c.path("eligible").asBoolean() && c.has("comparisonTotal"))
          improves =
              other.at("/session/objective").asText().equals("FASTEST")
                  ? Pricing.transitHours(c) < Pricing.transitHours(baseline.get())
                  : Pricing.decimal(c.get("comparisonTotal"), "amount")
                          .compareTo(
                              Pricing.decimal(baseline.get().get("comparisonTotal"), "amount"))
                      < 0;
      }
      history.add(
          new BidTiming.Observation(name, Instant.parse(str(m, "at")), at, arrival, improves));
    }
    var forecasts = arr();
    for (var item : solicited)
      if (item.getKey().equals(self) && str(item.getValue(), "category").equals("BID_REQUEST")) {
        var mail = item.getValue();
        String name = catalog.byEmail(str(mail, "to")).map(Catalog.Contact::name).orElse("");
        int elapsed = (int) Duration.between(Instant.parse(str(mail, "at")), at).getSeconds();
        var prediction =
            BidTiming.estimate(
                history,
                name,
                at,
                elapsed,
                15,
                (int) Duration.between(at, Workflow.deadline(current)).getSeconds());
        forecasts.add(
            obj(
                "carrier",
                name,
                "samples",
                prediction.samples(),
                "carrierSamples",
                prediction.carrierSamples(),
                "probability",
                prediction.probability(),
                "wait",
                prediction.waitForBid()));
      }
    current.set(
        "shadowTiming",
        obj("version", "bid-window-v1", "cutoff", at.toString(), "forecasts", forecasts));
    if (!forecasts.isEmpty())
      store.event(
          self,
          "DECISION",
          "Shadow timing evaluated "
              + forecasts.size()
              + " carrier intervals using prior cases; baseline controls dispatch.",
          at,
          current.at("/session/version").asInt());
  }
}
