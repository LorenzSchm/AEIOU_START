package ai.aeiou.dispatch;

import ai.aeiou.freight.*;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;
import javax.xml.stream.XMLStreamException;
import org.springframework.web.client.RestClientException;

final class ShipmentDocuments {
  record Decision(
      String action, String reason, String reply, Shipment shipment, DangerousGoodsCheck gatewayReview) {
    Decision(String action, String reason, String reply, Shipment shipment) {
      this(action, reason, reply, shipment, null);
    }

    String stage() {
      return "dg-unavailable".equals(action) ? "declined" : "waiting for documents";
    }

    boolean holdsQuote() {
      return reason != null;
    }
  }

  record SubmittedDocument(boolean sdsWithSection14, boolean declaration, Set<String> unNumbers) {}

  record DangerousGoodsCheck(Set<String> classificationEvidence, List<SubmittedDocument> submitted,
      Set<String> unNumbers, boolean missingSds, boolean missingDeclaration) {}

  private record Attachment(long messageId, long attachmentId) {}

  private final Ledger ledger;
  private final FiveU upstream;
  private final Catalog catalog;
  private final Map<Attachment, PackingSafety.Document> extracted =
      new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Attachment, PackingSafety.Document> entry) {
          return size() > 64;
        }
      };

  ShipmentDocuments(Ledger ledger, FiveU upstream, Catalog catalog) {
    this.ledger = ledger;
    this.upstream = upstream;
    this.catalog = catalog;
  }

  private List<PackingSafety.Document> read(Map<String, Object> message) {
    var documents = new ArrayList<PackingSafety.Document>();
    for (Object raw : ledger.read(message.get("attachments"), List.class)) {
      var file = (Map<?, ?>) raw;
      var key = new Attachment(Ledger.id(message.get("id")), Ledger.id(file.get("id")));
      try {
        var doc = extracted.get(key);
        if (doc == null) {
          doc =
              new PackingSafety.Document(
                  PackingSafety.extract(
                      Objects.toString(file.get("filename"), ""),
                      upstream.bytes(key.messageId(), key.attachmentId())));
          extracted.put(key, doc);
        }
        documents.add(doc);
      } catch (IOException | XMLStreamException | RestClientException ex) {
        documents.add(null);
      }
    }
    return documents;
  }

  Decision inspect(Map<String, Object> original, List<Map<String, Object>> followUps,
      String confirmedPickupZone, String customerDeliveryAirport) {
    var latestPacking = read(original);
    var followUpDocuments = new ArrayList<PackingSafety.Document>();
    latestPacking.stream()
        .filter(Objects::nonNull)
        .filter(doc -> doc.safetyDataSheet() || doc.declaration())
        .forEach(followUpDocuments::add);
    var originalText = new PackingSafety.Document(Ledger.str(original, "body"));
    var dangerous = new HashSet<String>();
    var evidence = new LinkedHashSet<String>();
    boolean dg = addDangerous(latestPacking, dangerous, evidence);
    dg |= addDangerous(List.of(originalText), dangerous, evidence);
    dg |= ledger.delivery("dg-docs:" + Ledger.id(original.get("id"))) != null;
    var emptyCorrection = new MailParser.PackingCorrection(Set.of(), Set.of(), Set.of());
    var correction = emptyCorrection;
    for (var message : followUps) {
      var body = new PackingSafety.Document(Ledger.str(message, "body"));
      dg |= addDangerous(List.of(body), dangerous, evidence);
      var attachments = read(message);
      if (!attachments.isEmpty()) {
        dg |= addDangerous(attachments, dangerous, evidence);
        attachments.stream().filter(Objects::nonNull).forEach(followUpDocuments::add);
        latestPacking = attachments;
        correction = emptyCorrection;
      }
      correction = correction.followedBy(
          MailParser.confirmedPackingCorrection(body.text()).orElse(emptyCorrection));
    }
    if (dg) return dangerousGoods(dangerous, followUpDocuments, evidence);
    if (latestPacking.isEmpty()) return new Decision(null, null, null, null);
    if (latestPacking.stream().anyMatch(Objects::isNull)
        || latestPacking.stream()
            .noneMatch(doc -> doc.type() == PackingSafety.DocumentType.GENERAL))
      return new Decision(
          "packing-copy",
          "Waiting for a readable packing specification and cargo classification.",
          "Could you send a readable packing list with the total gross weight, total volume, and"
              + " dangerous goods classification?",
          null);
    var subject = Ledger.str(original, "subject");
    var body =
        Ledger.str(original, "body")
            .replaceAll(
                "(?iu)\\b(?:non[- ]dangerous|not (?:dangerous|hazardous)|non[- ]hazardous)\\b",
                "general cargo");
    var parsed = MailParser.request(subject, body, false, catalog, confirmedPickupZone,
        customerDeliveryAirport);
    var terms = parsed.shipment();
    var destinations = new HashSet<String>();
    var weights = new HashSet<BigDecimal>();
    var volumes = new HashSet<BigDecimal>();
    for (var doc : latestPacking) {
      destinations.addAll(doc.destinations());
      weights.addAll(doc.grossWeights());
      volumes.addAll(doc.volumes());
    }
    reconcilePackingValue(destinations, terms.destination(), correction.destinations());
    boolean weightsReadable = reconcilePackingTotal(weights, terms.weightKg(), correction.weightsKg());
    boolean volumesReadable = reconcilePackingTotal(volumes, terms.volumeCbm(), correction.volumesCbm());
    if (weightsReadable && volumesReadable && destinations.size() == 1
        && weights.size() == 1
        && volumes.size() == 1
        && (parsed.issue() == null || parsed.issue().equals(catalog.problem(terms))
            || CustomerClarifications.PICKUP_ISSUE.equals(parsed.issue()))) {
      var verified =
          new Shipment(
              terms.origin(),
              destinations.iterator().next(),
              weights.iterator().next(),
              volumes.iterator().next(),
              terms.preference(),
              terms.pickupZone(),
              null,
              0);
      if (catalog.problem(verified) == null) return new Decision(null, null, null, verified);
      if (CustomerClarifications.PICKUP_ISSUE.equals(parsed.issue()))
        return new Decision(null, null, null, null);
    }
    return new Decision(
        "packing-correction",
        "Waiting for consistent shipment details.",
        "Could you send a corrected packing list and confirm the destination, gross weight and"
            + " total volume? I couldn’t reconcile the shipment details in the documents and"
            + " email.",
        null);
  }

  private static boolean reconcilePackingTotal(
      Set<BigDecimal> documented, BigDecimal original, Set<String> correction) {
    var values = new HashSet<BigDecimal>();
    BigDecimal documentTotal = documented.size() == 1 ? documented.iterator().next() : null;
    for (String claim : correction) {
      var value = MailParser.packingNumber(claim, documentTotal);
      if (value == null) return false;
      values.add(value);
    }
    reconcilePackingValue(documented, original == null ? null : original.stripTrailingZeros(), values);
    return true;
  }

  private static <T> void reconcilePackingValue(Set<T> documented, T original, Set<T> correction) {
    boolean corroborated = documented.size() == 1 && documented.equals(correction);
    if (original != null && !corroborated) documented.add(original);
    documented.addAll(correction);
  }

  private static boolean addDangerous(
      List<PackingSafety.Document> documents, Set<String> numbers, Set<String> evidence) {
    boolean found = false;
    for (var document : documents) {
      if (document == null || document.type() != PackingSafety.DocumentType.DANGEROUS) continue;
      found = true;
      numbers.addAll(document.unNumbers());
      document.text().lines().map(String::strip).filter(PackingSafety::declaresDangerousGoods)
          .map(line -> line.substring(0, Math.min(line.length(), 240)))
          .limit(4).forEach(evidence::add);
    }
    return found;
  }

  private static Decision dangerousGoods(
      Set<String> unNumbers, List<PackingSafety.Document> followUps, Set<String> evidence) {
    boolean matchingSds =
        unNumbers.size() == 1
            && followUps.stream()
                .anyMatch(doc -> doc.safetyDataSheet() && doc.unNumbers().equals(unNumbers));
    boolean matchingDeclaration =
        unNumbers.size() == 1
            && followUps.stream()
                .anyMatch(doc -> doc.declaration() && doc.unNumbers().equals(unNumbers));
    if (matchingSds && matchingDeclaration)
      return new Decision(
          "dg-unavailable",
          "Dangerous goods documents received. No supported dangerous goods rate.",
          "Thank you for sending the documents. We don’t have a confirmed dangerous goods service"
              + " for this shipment, so we’re unable to offer a quote.",
          null);
    var review = new DangerousGoodsCheck(evidence, followUps.stream()
        .map(doc -> new SubmittedDocument(doc.safetyDataSheet(), doc.declaration(), doc.unNumbers()))
        .toList(), unNumbers, !matchingSds, !matchingDeclaration);
    if (!followUps.isEmpty())
      return new Decision(
          "dg-incomplete",
          "Waiting for complete, matching dangerous goods documents.",
          "Thank you for the documents. Could you send a safety data sheet with Section 14 and the"
              + " shipper’s dangerous goods declaration, both showing the same UN classification?",
          null, review);
    return new Decision(
        "dg-docs",
        "Dangerous goods indicated. Hold pending documents and carrier review.",
        "The shipment details indicate dangerous goods. Could you send the current safety data"
            + " sheet, including Section 14, and the dangerous goods declaration if applicable?"
            + " Please also confirm the classification for air transport. We’ll review these before"
            + " we quote.",
        null, review);
  }
}
