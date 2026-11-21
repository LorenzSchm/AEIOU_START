package ai.aeiou.forwarder.application;

import static ai.aeiou.forwarder.domain.Json.*;

import ai.aeiou.forwarder.adapters.Catalog;
import ai.aeiou.forwarder.domain.*;
import ai.aeiou.forwarder.infrastructure.Ledger;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.math.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class Workflow {
  public static final String TEAM = "aeiou@hackloop.mail";
  public final Ledger store;
  final Catalog catalog;
  public final TimelineClock clock;
  public final int decisionSeconds, reserveSeconds;
  private final ShadowPolicy shadow;

  public Workflow(
      Ledger store,
      Catalog catalog,
      TimelineClock clock,
      ShadowPolicy shadow,
      @Value("${forwarder.decision-seconds}") int decisionSeconds,
      @Value("${forwarder.reserve-seconds}") int reserveSeconds) {
    this.store = store;
    this.catalog = catalog;
    this.clock = clock;
    this.shadow = shadow;
    this.decisionSeconds = decisionSeconds;
    this.reserveSeconds = reserveSeconds;
  }

  public static ObjectNode session(ObjectNode d) {
    return (ObjectNode) d.get("session");
  }

  public static String sid(ObjectNode d) {
    return str(session(d), "id");
  }

  public static Instant deadline(ObjectNode d) {
    return Instant.parse(str(session(d), "deadlineAt"));
  }

  public static ObjectNode usage() {
    return obj("calls", 0, "inputTokens", 0, "outputTokens", 0, "costBasis", "UNKNOWN");
  }

  public static ObjectNode mail(
      String id,
      String category,
      String from,
      String to,
      String subject,
      String body,
      Instant at,
      String parent) {
    return obj(
        "id",
        id,
        "category",
        category,
        "from",
        from,
        "to",
        to,
        "subject",
        subject,
        "bodyText",
        body,
        "at",
        at.toString(),
        "parentId",
        parent,
        "direction",
        from.equals(TEAM) ? "OUT" : "IN",
        "attachments",
        arr());
  }

  public ObjectNode receive(String id, String reference, ObjectNode mail, Instant at) {
    var s =
        obj(
            "id",
            id,
            "reference",
            reference,
            "subject",
            mail.get("subject"),
            "phase",
            "RECEIVED",
            "control",
            "AUTO",
            "version",
            0,
            "verdict",
            "OPEN",
            "receivedAt",
            at.toString(),
            "deadlineAt",
            at.plusSeconds(360).toString(),
            "updatedAt",
            at.toString(),
            "objective",
            "UNKNOWN",
            "scope",
            "UNKNOWN");
    var d =
        obj(
            "session",
            s,
            "requestRevision",
            1,
            "facts",
            arr(),
            "candidates",
            arr(),
            "milestones",
            arr(),
            "usage",
            usage(),
            "customer",
            mail.get("from"),
            "parent",
            mail.get("id"),
            "original",
            mail,
            "arrival",
            obj(),
            "inputs",
            obj());
    store.message(id, mail);
    store.changed(d, "SESSION", "Customer request received", at);
    s.put("phase", "SOURCING");
    facts(d, ShipmentFacts.extract(str(mail, "bodyText")), mail, at);
    store.schedule(id + ":decide", id, "DECIDE", at.plusSeconds(decisionSeconds), obj());
    store.schedule(id + ":last", id, "DECIDE", at.plusSeconds(360 - reserveSeconds), obj());
    store.schedule(id + ":expire", id, "DECIDE", at.plusSeconds(360), obj());
    store.schedule(id + ":shadow-result", id, "SHADOW", at.plusSeconds(361), obj());
    store.changed(d, "SESSION", "Sourcing started", at);
    return d;
  }

  public void facts(ObjectNode d, ObjectNode fields, JsonNode source, Instant at) {
    var s = session(d);
    var known = (ObjectNode) d.get("inputs");
    known.setAll(fields);
    if (fields.has("grossAlternatives")) known.remove("grossKg");
    else if (fields.has("grossKg")) known.remove("grossAlternatives");
    if (fields.has("volumeAlternatives")) known.remove("volumeCbm");
    else if (fields.has("volumeCbm")) known.remove("volumeAlternatives");
    var provenance = d.withObject("/fieldEvidence");
    for (var field : fields.properties())
      provenance.set(
          field.getKey(),
          evidence(
              "MAIL",
              str(source, "id"),
              "message body",
              str(source, "bodyText"),
              str(source, "at")));
    for (String f : List.of("origin", "destination", "objective", "scope", "language"))
      if (known.has(f)) s.set(f, known.get(f));
    ArrayNode facts = arr();
    for (String f :
        List.of(
            "origin",
            "destination",
            "objective",
            "scope",
            "grossKg",
            "volumeCbm",
            "zone",
            "pickupPostcode",
            "readyDate",
            "constraints",
            "pieces",
            "quoteDeadline",
            "deliveryDeadline")) {
      String value = str(known, f);
      String alt =
          f.equals("grossKg")
              ? "grossAlternatives"
              : f.equals("volumeCbm") ? "volumeAlternatives" : "";
      boolean ambiguous = !alt.isEmpty() && known.has(alt);
      var ev = provenance.get(ambiguous ? alt : f);
      facts.add(
          obj(
              "field",
              f,
              "state",
              ambiguous ? "AMBIGUOUS" : value.isEmpty() ? "MISSING" : "KNOWN",
              "value",
              value.isEmpty() ? null : value,
              "alternatives",
              ambiguous ? known.get(alt) : arr(),
              "evidence",
              ev == null ? arr() : arr(ev)));
    }
    int groupIndex = 0;
    for (var g : known.path("packages")) {
      int index = groupIndex++;
      facts.add(
          obj(
              "field",
              "packages[" + index + "].dimensions",
              "value",
              g.path("count").asInt()
                  + " × "
                  + str(g, "lengthM")
                  + " × "
                  + str(g, "widthM")
                  + " × "
                  + str(g, "heightM")
                  + " m",
              "state",
              "KNOWN",
              "alternatives",
              arr(),
              "evidence",
              arr(
                  evidence(
                      "MAIL",
                      str(source, "id"),
                      "package dimensions",
                      str(g, "phrase"),
                      str(source, "at")))));
    }
    d.set("facts", facts);
    if (known.has("quoteDeadline")) {
      Instant due = Instant.parse(str(known, "quoteDeadline"));
      if (due.isBefore(deadline(d))) {
        s.put("deadlineAt", due.toString());
        store.schedule(
            sid(d) + ":explicit:" + d.get("requestRevision").asInt(),
            sid(d),
            "DECIDE",
            due.minusSeconds(reserveSeconds),
            obj());
        store.schedule(
            sid(d) + ":explicit-expire:" + d.get("requestRevision").asInt(),
            sid(d),
            "DECIDE",
            due,
            obj());
      }
    }
    var zone = str(known, "zone");
    if (!known.has("origin") && catalog.airport(zone) != null)
      s.put("origin", catalog.airport(zone));
    if (known.has("grossKg") && known.has("volumeCbm")) {
      var kg = Pricing.decimal(known, "grossKg");
      var cbm = Pricing.decimal(known, "volumeCbm");
      if (kg.signum() > 0 && cbm.signum() >= 0)
        for (var c :
            catalog.offers(
                str(s, "origin"),
                str(s, "destination"),
                kg,
                cbm,
                d.path("requestRevision").asInt(),
                at)) {
          if (clock.live && catalog.byName(str(c, "provider")).isEmpty()) continue;
          offer(d, (ObjectNode) c, at);
        }
    }
    store.changed(d, "FACTS", "Normalized shipment facts and priced applicable tariff rows", at);
  }

  private static String arrivalEstimate(String ready, int hours, boolean de) {
    LocalDate date = LocalDate.parse(ready);
    String first = date.plusDays(hours / 24).toString();
    String last = date.plusDays(Math.ceilDiv(hours, 24)).toString();
    String arrival = first.equals(last) ? first : first + (de ? " bis " : " to ") + last;
    return de
        ? " Bei Transportbeginn am " + ready + " erwarten wir die Ankunft am " + arrival + "."
        : " If transport starts on " + ready + ", estimated arrival is " + arrival + ".";
  }

  private static Instant serviceStart(ObjectNode d, Instant at) {
    String ready = d.at("/inputs/readyDate").asText("");
    if (ready.isEmpty()) return at;
    Instant earliest = LocalDate.parse(ready).atStartOfDay(ZoneOffset.UTC).toInstant();
    return earliest.isAfter(at) ? earliest : at;
  }

  public void offer(ObjectNode d, ObjectNode candidate, Instant at) {
    var s = session(d);
    String id = str(candidate, "id");
    if (d.withArray("candidates").valueStream().anyMatch(c -> str(c, "id").equals(id))) return;
    boolean needPickup =
        str(s, "scope").equals("DOOR_TO_PORT") && !candidate.path("includesPickup").asBoolean();
    if (needPickup && candidate.path("eligible").asBoolean())
      d.withObject("/airLegs").set(id, candidate.deepCopy());
    if (!str(s, "scope").equals("AIRPORT_TO_AIRPORT")
        && !candidate.path("includesPickup").asBoolean()) {
      candidate.put("eligible", false);
      candidate.withArray("reasons").add("Missing complete pickup price or service time");
    }
    candidate.remove("includesPickup");
    if (d.path("inputs").has("deliveryDeadline")
        && serviceStart(d, at)
            .plus(Duration.ofHours(Pricing.transitHours(candidate)))
            .isAfter(Instant.parse(d.at("/inputs/deliveryDeadline").asText()))) {
      candidate.put("eligible", false);
      candidate.withArray("reasons").add("Transit exceeds requested delivery deadline");
    }
    if (d.path("inputs").hasNonNull("constraints")
        && !str(d.get("inputs"), "constraints").isBlank()) {
      candidate.put("eligible", false);
      candidate.withArray("reasons").add("Cargo constraints require provider confirmation");
    }
    d.withArray("candidates").add(candidate);
    ((ObjectNode) d.get("arrival")).put(id, at.toString());
    store.changed(d, "CANDIDATE", "Received offer from " + str(candidate, "provider"), at);
    if (needPickup) for (var pickup : d.path("pickupLegs")) combine(d, id, pickup, at);
    if (d.at("/latestDecision/action").asText().equals("WAIT")
        && !d.has("quote")
        && str(s, "control").equals("AUTO"))
      store.schedule(sid(d) + ":recheck:" + id, sid(d), "DECIDE", clock.now(), obj());
  }

  public void pickup(ObjectNode d, ObjectNode pickup, Instant at) {
    d.withObject("/pickupLegs").set(str(pickup, "id"), pickup);
    for (var entry : d.path("airLegs").properties()) combine(d, entry.getKey(), pickup, at);
    store.changed(d, "CANDIDATE", "Pickup offer received for the requested zone", at);
  }

  private void combine(ObjectNode d, String airId, JsonNode pickup, Instant at) {
    var air = d.path("airLegs").path(airId);
    if (!air.has("quotedTotal")
        || air.get("requestRevision").asInt() != pickup.get("requestRevision").asInt()
        || !str(air.get("quotedTotal"), "currency").equals("EUR")) return;
    var c = (ObjectNode) air.deepCopy();
    String id = airId + "_" + str(pickup, "id");
    int hours = Pricing.transitHours(air) + Pricing.transitHours(pickup);
    if (pickup.has("validUntil")
        && (!air.has("validUntil")
            || Instant.parse(str(pickup, "validUntil"))
                .isBefore(Instant.parse(str(air, "validUntil")))))
      c.set("validUntil", pickup.get("validUntil"));
    c.put("airBidId", airId);
    c.set("airTotal", air.get("quotedTotal"));
    c.put("id", id)
        .put("includesPickup", true)
        .put("transitHours", hours)
        .put("transitDays", Math.ceilDiv(hours, 24));
    var total =
        Pricing.money(
            Pricing.decimal(air.get("quotedTotal"), "amount")
                .add(Pricing.decimal(pickup.get("total"), "amount")),
            "EUR");
    c.set("quotedTotal", total);
    c.set("comparisonTotal", total);
    c.withArray("evidence").addAll((ArrayNode) pickup.get("evidence"));
    c.withArray("lineItems")
        .add(
            obj(
                "kind",
                "PICKUP",
                "label",
                pickup.get("provider"),
                "basis",
                "Collection in postal zone " + str(d.get("inputs"), "zone"),
                "amount",
                pickup.get("total"),
                "evidence",
                pickup.get("evidence")));
    d.withObject("/pickupBookings").set(id, pickup);
    offer(d, c, at);
  }

  public ObjectNode decision(
      String action, Instant at, String reason, String cutoff, String candidate, Instant wake) {
    return obj(
        "action",
        action,
        "at",
        at.toString(),
        "reason",
        reason,
        "policyVersion",
        "baseline-v1.1",
        "pendingSources",
        action.equals("WAIT") ? arr("carrier replies") : arr(),
        "cutoff",
        cutoff,
        "chosenCandidateId",
        candidate,
        "wakeAt",
        wake == null ? null : wake.toString());
  }

  public void scoreShadow(ObjectNode d, Instant at) {
    if (!d.has("recorded")) return;
    var baseline = chosen(d);
    Instant sent = Instant.parse(d.at("/recorded/at").asText());
    boolean wait =
        d.at("/shadowTiming/forecasts").valueStream().anyMatch(f -> f.path("wait").asBoolean());
    Instant simulated = wait ? sent.plusSeconds(15) : sent;
    var options =
        d.withArray("candidates")
            .valueStream()
            .filter(c -> !Instant.parse(str(d.path("arrival"), str(c, "id"))).isAfter(simulated))
            .toList();
    var best =
        Pricing.choose(
            options,
            str(session(d), "objective"),
            simulated,
            d.at("/quote/requestRevision").asInt(),
            str(d, "pin"));
    int samples =
        d.at("/shadowTiming/forecasts")
            .valueStream()
            .mapToInt(f -> f.path("samples").asInt())
            .max()
            .orElse(0);
    var amount = best.map(c -> str(c.get("comparisonTotal"), "amount")).orElse("unknown");
    String summary =
        "Shadow replay on observed offers: "
            + samples
            + " earlier labeled intervals, wait "
            + (wait ? 15 : 0)
            + "s, baseline EUR "
            + str(baseline.get("comparisonTotal"), "amount")
            + ", simulated EUR "
            + amount
            + ", deadline missed "
            + (!simulated.isBefore(deadline(d)) || best.isEmpty())
            + ".";
    store.changed(d, "DECISION", summary, at);
  }

  public void decide(ObjectNode d, Instant at) {
    var s = session(d);
    if (!str(s, "phase").equals("SOURCING") || d.has("quote")) return;
    shadow.evaluate(d, at);
    String cutoff = Long.toString(store.cursor());
    var winner =
        Pricing.choose(
            d.withArray("candidates").valueStream().toList(),
            str(s, "objective"),
            at,
            d.get("requestRevision").asInt(),
            str(d, "pin"));
    boolean paused = str(s, "control").equals("PAUSED");
    if (!at.isBefore(deadline(d))
        || (!paused
            && winner.isEmpty()
            && !at.isBefore(deadline(d).minusSeconds(reserveSeconds)))) {
      String reason =
          paused
              ? "Paused at the customer deadline"
              : "No complete eligible offer before the dispatch deadline";
      d.set("latestDecision", decision("CLOSE", at, reason, cutoff, null, null));
      s.put("phase", "CLOSED").put("outcomeReason", reason);
    } else if (winner.isPresent() && !paused) {
      var c = winner.get();
      var total = c.get("quotedTotal");
      boolean de = str(s, "language").equals("de");
      String route = str(s, "origin") + " → " + str(s, "destination");
      String service =
          str(s, "scope").equals("DOOR_TO_PORT")
              ? (de ? "inklusive Abholung in " : "including collection in ")
                  + d.at("/inputs/pickupPostcode").asText(d.at("/inputs/zone").asText())
              : (de ? "von Flughafen zu Flughafen" : "airport to airport");
      int hours = Pricing.transitHours(c);
      String duration =
          hours % 24 == 0
              ? hours / 24 + (de ? " Tage" : hours == 24 ? " day" : " days")
              : hours + (de ? " Stunden" : " hours");
      String ready = d.at("/inputs/readyDate").asText("");
      String body =
          (de ? "Guten Tag,\n\nfür " : "Hello,\n\nFor ")
              + route
              + (de ? " können wir Ihnen " : " we can offer ")
              + str(total, "amount")
              + " "
              + str(total, "currency")
              + (de ? " gesamt anbieten, " : " all-in, ")
              + service
              + ". "
              + (de ? "Die geschätzte Laufzeit mit " : "Estimated transit with ")
              + str(c, "provider")
              + (de ? " beträgt " : " is ")
              + duration
              + "."
              + (ready.isEmpty() ? "" : arrivalEstimate(ready, hours, de))
              + (de
                  ? "\n\nSollen wir den Transport für Sie buchen?\nTeam AEIOU"
                  : "\n\nShall we book this for you?\nTeam AEIOU");
      d.set(
          "latestDecision",
          decision(
              "QUOTE",
              at,
              "Best complete offer for " + str(s, "objective"),
              cutoff,
              str(c, "id"),
              null));
      d.set(
          "quote",
          obj(
              "candidateId",
              c.get("id"),
              "at",
              at.toString(),
              "total",
              total,
              "body",
              body,
              "sendState",
              "QUEUED",
              "requestRevision",
              d.get("requestRevision")));
      d.set("recorded", d.get("latestDecision").deepCopy());
      d.set(
          "decisionInputs",
          obj(
              "session",
              s.deepCopy(),
              "facts",
              d.get("facts").deepCopy(),
              "candidates",
              d.get("candidates").deepCopy(),
              "arrival",
              d.get("arrival").deepCopy()));
      queueMail(d, "quote", "QUOTE", str(d, "customer"), str(d, "parent"), body, at);
    } else {
      var wake = paused ? deadline(d) : deadline(d).minusSeconds(reserveSeconds);
      d.set(
          "latestDecision",
          decision(
              "WAIT",
              at,
              paused ? "Operator pause" : "Waiting for a complete offer",
              cutoff,
              null,
              wake));
    }
    store.changed(d, "DECISION", str(d.get("latestDecision"), "reason"), at);
  }

  public void queueMail(
      ObjectNode d,
      String key,
      String category,
      String to,
      String parent,
      String body,
      Instant at) {
    if (to == null || !to.matches("[A-Za-z0-9._+-]+@hackloop\\.mail") || to.equals(TEAM)) return;
    var m =
        mail(
            sid(d) + "_" + key,
            category,
            TEAM,
            to,
            "Re: " + str(session(d), "subject"),
            body,
            at,
            parent);
    store.schedule(sid(d) + ":" + key, sid(d), "SEND", at, m);
  }

  public void sent(ObjectNode d, ObjectNode m, String upstream, Instant at) {
    m.put("upstreamId", upstream).put("at", at.toString());
    store.message(sid(d), m);
    if (str(m, "category").equals("QUOTE")) {
      session(d).put("phase", "QUOTED");
      ((ObjectNode) d.get("quote")).put("sendState", "SENT").put("sentAt", at.toString());
    }
    store.changed(d, "MESSAGE", "Dispatched " + str(m, "category"), at);
  }

  public JsonNode chosen(ObjectNode d) {
    return d.withArray("candidates")
        .valueStream()
        .filter(c -> str(c, "id").equals(d.at("/quote/candidateId").asText()))
        .findFirst()
        .orElseThrow();
  }

  public void accepted(ObjectNode d, String parent, Instant at) {
    if (!d.has("quote") || d.has("booking")) return;
    if (d.at("/quote/sendState").asText().equals("UNCERTAIN"))
      store.db.update(
          "update job set state='RECONCILED' where id=? and state='UNCERTAIN'", sid(d) + ":quote");
    ((ObjectNode) d.get("quote")).put("sendState", "SENT");
    var s = session(d);
    s.put("phase", "ACCEPTED");
    store.changed(d, "SESSION", "Customer accepted the quote", at);
    var c = chosen(d);
    s.put("phase", "BOOKING");
    d.set(
        "booking",
        obj(
            "provider",
            c.get("provider"),
            "teamReference",
            s.get("reference"),
            "state",
            "REQUESTED",
            "rateId",
            c.get("rateId")));
    d.put("acceptedParent", parent);
    var pickup = d.path("pickupBookings").path(str(c, "id"));
    if (pickup.isObject() && pickup.path("bookingRequired").asBoolean(true))
      queueMail(
          d,
          "book-pickup",
          "BOOKING",
          str(pickup, "email"),
          str(pickup, "parent"),
          "Please arrange the quoted collection for "
              + str(s, "reference")
              + " at postcode "
              + d.at("/inputs/pickupPostcode").asText(d.at("/inputs/zone").asText())
              + ".",
          at);
    if (c.has("rateId") || !clock.live)
      store.schedule(
          sid(d) + ":book",
          sid(d),
          "BOOK",
          at,
          obj("rate_id", c.get("rateId"), "team_reference", s.get("reference")));
    else
      catalog
          .byName(str(c, "provider"))
          .ifPresent(
              contact ->
                  queueMail(
                      d,
                      "booking-request",
                      "BOOKING",
                      contact.email(),
                      c.has("carrierMail") ? str(c, "carrierMail") : null,
                      "Please book "
                          + (c.has("carrierReference") ? str(c, "carrierReference") + " for " : "")
                          + str(s, "reference")
                          + " on "
                          + str(s, "origin")
                          + " to "
                          + str(s, "destination")
                          + " as quoted: "
                          + str(
                              c.has("airTotal") ? c.get("airTotal") : c.get("quotedTotal"),
                              "amount")
                          + " "
                          + d.at("/quote/total/currency").asText()
                          + ".",
                      at));
    store.changed(d, "BOOKING", "Booking requested with stable team reference", at);
  }

  public void confirmed(ObjectNode d, String ref, Instant at) {
    if (!d.has("booking") || str(session(d), "phase").equals("BOOKED") || ref.isBlank()) return;
    ((ObjectNode) d.get("booking"))
        .put("state", "CONFIRMED")
        .put("bookingReference", ref)
        .put("confirmedAt", at.toString());
    session(d).put("phase", "BOOKED");
    d.withArray("milestones")
        .add(
            obj(
                "label",
                "Booking confirmed: " + ref,
                "kind",
                "CONFIRMED",
                "effectiveAt",
                at.toString(),
                "observedAt",
                at.toString(),
                "evidence",
                arr(evidence("TRACKING", ref, "carrier confirmation", ref, at.toString()))));
    String confirmation =
        str(session(d), "language").equals("de")
            ? "Ihre Sendung ist gebucht. Bestätigte Buchungsreferenz: "
            : "Your shipment is booked. Confirmed booking reference: ";
    queueMail(
        d,
        "confirm",
        "BOOKING",
        str(d, "customer"),
        str(d, "acceptedParent"),
        confirmation + ref + ".",
        at);
    store.changed(d, "BOOKING", "Carrier confirmed booking " + ref, at);
    for (var q : d.path("pendingTracking")) tracking(d, (ObjectNode) q, at);
    d.remove("pendingTracking");
    store.save(d);
  }

  private static final java.util.regex.Pattern MOVEMENT =
      java.util.regex.Pattern.compile(
          "(?im)^\\s*(?:the (?:shipment|flight) (?:has )?)?(departed(?: from)?|arrived at|delivered"
              + " to)\\s+([A-Z]{3})\\s+(?:at |on"
              + " )?(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(?::\\d{2})?(?:Z|[+-]\\d{2}:\\d{2}))\\s*[.!]?\\s*$");

  public void carrierMilestones(ObjectNode d, ObjectNode m, String body, Instant observed) {
    var matches = MOVEMENT.matcher(body);
    while (matches.find()) {
      try {
        Instant effective = OffsetDateTime.parse(matches.group(3)).toInstant();
        if (!effective.isAfter(observed))
          d.withArray("milestones")
              .add(
                  obj(
                      "label",
                      matches.group(1) + " " + matches.group(2),
                      "kind",
                      "CONFIRMED",
                      "effectiveAt",
                      effective.toString(),
                      "observedAt",
                      observed.toString(),
                      "evidence",
                      arr(
                          evidence(
                              "MAIL",
                              str(m, "id"),
                              "carrier update",
                              matches.group().strip(),
                              observed.toString()))));
      } catch (DateTimeException ignored) {
      }
    }
  }

  public void tracking(ObjectNode d, ObjectNode question, Instant at) {
    if (d.withArray("milestones").isEmpty()) {
      d.withObject("/pendingTracking").set(str(question, "id"), question.deepCopy());
      store.changed(d, "TRACKING", "Tracking question is awaiting confirmation", at);
      return;
    }
    var milestone =
        d.withArray("milestones")
            .valueStream()
            .max(
                Comparator.comparing((JsonNode x) -> str(x, "effectiveAt"))
                    .thenComparing(x -> str(x, "observedAt")))
            .orElseThrow();
    String intro =
        str(session(d), "language").equals("de")
            ? "Letzter bestätigter Status: "
            : "Latest recorded status: ";
    queueMail(
        d,
        "tracking-" + str(question, "id"),
        "TRACKING",
        str(question, "from"),
        str(question, "id"),
        str(session(d), "origin")
            + " → "
            + str(session(d), "destination")
            + ", "
            + d.at("/booking/provider").asText()
            + " ("
            + d.at("/booking/bookingReference").asText()
            + "). "
            + intro
            + str(milestone, "label")
            + " ("
            + str(milestone, "effectiveAt")
            + ").",
        at);
    store.changed(d, "TRACKING", "Answered from the confirmed shipment record", at);
  }
}
