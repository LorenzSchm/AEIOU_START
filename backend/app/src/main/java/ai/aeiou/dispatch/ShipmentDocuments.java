package ai.aeiou.dispatch;

import ai.aeiou.freight.*;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;
import javax.xml.stream.XMLStreamException;
import org.springframework.web.client.RestClientException;

final class ShipmentDocuments {
  record Decision(String action, String reason, String reply, Shipment shipment) {
    String stage() {
      return "dg-unavailable".equals(action) ? "declined" : "waiting for documents";
    }

    boolean holdsQuote() {
      return reason != null;
    }
  }

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
      String confirmedPostcode) {
    var latestPacking = read(original);
    var followUpDocuments = new ArrayList<PackingSafety.Document>();
    latestPacking.stream()
        .filter(Objects::nonNull)
        .filter(doc -> doc.safetyDataSheet() || doc.declaration())
        .forEach(followUpDocuments::add);
    var originalText = new PackingSafety.Document(Ledger.str(original, "body"));
    var dangerous = new HashSet<>(originalText.unNumbers());
    boolean dg =
        addDangerous(latestPacking, dangerous)
            || originalText.type() == PackingSafety.DocumentType.DANGEROUS
            || ledger.delivery("dg-docs:" + Ledger.id(original.get("id"))) != null;
    for (var message : followUps) {
      var body = new PackingSafety.Document(Ledger.str(message, "body"));
      dangerous.addAll(body.unNumbers());
      dg |= body.type() == PackingSafety.DocumentType.DANGEROUS;
      var attachments = read(message);
      if (attachments.isEmpty()) continue;
      dg |= addDangerous(attachments, dangerous);
      attachments.stream().filter(Objects::nonNull).forEach(followUpDocuments::add);
      latestPacking = attachments;
    }
    if (dg) return dangerousGoods(dangerous, followUpDocuments);
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
    var parsed = MailParser.request(subject, body, false, catalog, confirmedPostcode);
    var terms = parsed.shipment();
    var destinations = new HashSet<String>();
    var weights = new HashSet<BigDecimal>();
    var volumes = new HashSet<BigDecimal>();
    for (var doc : latestPacking) {
      destinations.addAll(doc.destinations());
      weights.addAll(doc.grossWeights());
      volumes.addAll(doc.volumes());
    }
    if (terms.destination() != null) destinations.add(terms.destination());
    if (terms.weightKg() != null) weights.add(terms.weightKg().stripTrailingZeros());
    if (terms.volumeCbm() != null) volumes.add(terms.volumeCbm().stripTrailingZeros());
    if (destinations.size() == 1
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

  private static boolean addDangerous(List<PackingSafety.Document> documents, Set<String> numbers) {
    boolean found = false;
    for (var document : documents) {
      if (document == null || document.type() != PackingSafety.DocumentType.DANGEROUS) continue;
      found = true;
      numbers.addAll(document.unNumbers());
    }
    return found;
  }

  private static Decision dangerousGoods(
      Set<String> unNumbers, List<PackingSafety.Document> followUps) {
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
    if (!followUps.isEmpty())
      return new Decision(
          "dg-incomplete",
          "Waiting for complete, matching dangerous goods documents.",
          "Thank you for the documents. Could you send a safety data sheet with Section 14 and the"
              + " shipper’s dangerous goods declaration, both showing the same UN classification?",
          null);
    return new Decision(
        "dg-docs",
        "Dangerous goods indicated. Hold pending documents and carrier review.",
        "The shipment details indicate dangerous goods. Could you send the current safety data"
            + " sheet, including Section 14, and the dangerous goods declaration if applicable?"
            + " Please also confirm the classification for air transport. We’ll review these before"
            + " we quote.",
        null);
  }
}
