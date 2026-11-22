package ai.aeiou.dispatch;

import ai.aeiou.edgelab.PackingAttachmentTools;
import ai.aeiou.freight.*;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import javax.xml.stream.XMLStreamException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

final class ShipmentDocuments {
  private static final Pattern FIRM_PICKUP_TOTAL = Pattern.compile(
      "(?iu)\\bThis\\s+price\\s+is\\s+all-in\\.");
  private static final Pattern PICKUP_BREAKDOWN = Pattern.compile(
      "(?iu)\\bIt\\s+has\\s+a\\s+base\\s+charge\\s+of\\s+[0-9]+[.,][0-9]{2}\\s+EUR"
          + "\\s+and\\s+a\\s+weight\\s+charge\\s+of\\s+[0-9]+[.,][0-9]{2}\\s+EUR\\.");
  private static final Pattern OFFER_TOTAL = Pattern.compile(
      "(?im)^\\h*Total(?:\\h+|\\h*\\R(?:\\h*\\R){0,3}\\h*)([0-9]+[.,][0-9]{2})\\h*$");
  private static final Pattern OPEN_OFFER_TERMS = Pattern.compile(
      "(?iu)\\b(?:not part of this offer|not part of the total|not included|subject to|fees apply|charged separately|provisional)\\b");
  private static final Pattern SPOKEN_KILOS = Pattern.compile(
      "(?iu)\\b([0-9]{1,3}(?:,[0-9]{3})+|[0-9]+(?:\\.[0-9]+)?)\\h+kilos?\\b");
  private static final Pattern SPOKEN_EUROS = Pattern.compile(
      "(?iu)\\b([0-9]+)\\h+euros?\\h+and\\h+([0-9]{1,2})\\h+cents\\b");
  private static final String SPOKEN_AMOUNT = "EUR\\h+([0-9]+(?:[.,][0-9]{1,2})?)";
  private static final Pattern SPOKEN_PICKUP_BREAKDOWN = Pattern.compile(
      "(?iu)\\bthe\\s+base\\s+charge\\s+is\\s+" + SPOKEN_AMOUNT
          + ",\\s+the\\s+weight\\s+charge\\s+is\\s+" + SPOKEN_AMOUNT
          + ",\\s+so\\s+the\\s+total\\s+is\\s+" + SPOKEN_AMOUNT + "\\.(?=\\s|$)");
  private static final Pattern SPOKEN_PICKUP_WAIT = Pattern.compile(
      "(?iu)\\bwaiting\\s+time\\s+after\\s+the\\s+first\\s+([1-9]\\d{0,2})\\s+minutes\\s+is\\s+"
          + SPOKEN_AMOUNT + "\\s+for\\s+each\\s+hour,\\s+and\\s+that\\s+is\\s+not\\s+part\\s+of"
          + "\\s+the\\s+total\\.(?=\\s|$)");
  private static final Pattern SPOKEN_EXPIRY = Pattern.compile(
      "(?iu)\\b(?:valid until|(?:I|we) keep (?:this|the) price until)\\h+(?:the\\h+)?"
          + "([0-9]{1,2})(?:st|nd|rd|th)?\\h+of\\h+([a-z]{3,})(?:\\h+([0-9]{4}))?"
          + "\\h+(?:at\\h+)?([0-9]{2}:[0-9]{2})\\h+UTC\\b");
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

  private record Attachment(long messageId, long attachmentId, boolean recoverPacking) {}
  // Increment when a new reader can change a previous recovery result.
  private static final int PACKING_READER_VERSION = 1;

  private final Ledger ledger;
  private final FiveU upstream;
  private final Catalog catalog;
  private final Clock clock;
  private final Executor voiceExecutor;
  private final Executor edgeExecutor;
  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ShipmentDocuments.class);
  private final boolean packingTools;
  private final EdgeAttachmentReader edgeReader;
  private final Set<Attachment> pending = ConcurrentHashMap.newKeySet();
  private final int readerVersion;
  private final Map<Attachment, PackingSafety.Document> extracted =
      new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Attachment, PackingSafety.Document> entry) {
          return size() > 64;
        }
      };

  ShipmentDocuments(Ledger ledger, FiveU upstream, Catalog catalog, Clock clock, Executor voiceExecutor,
      boolean packingTools) {
    this(ledger, upstream, catalog, clock, voiceExecutor, packingTools, voiceExecutor, null, false);
  }

  ShipmentDocuments(Ledger ledger, FiveU upstream, Catalog catalog, Clock clock, Executor voiceExecutor,
      boolean packingTools, Executor edgeExecutor, EdgeAttachmentReader edgeReader, boolean edgeGateway) {
    this.ledger = ledger;
    this.upstream = upstream;
    this.catalog = catalog;
    this.clock = clock;
    this.voiceExecutor = voiceExecutor;
    this.packingTools = packingTools;
    this.edgeReader = edgeReader;
    this.edgeExecutor = edgeExecutor;
    this.readerVersion = edgeReader == null ? PACKING_READER_VERSION : (edgeGateway ? 4 : 3);
    if (packingTools || edgeReader != null) ledger.jdbc().execute("""
        CREATE TABLE IF NOT EXISTS packing_tool_read (
          message_id INTEGER, attachment_id INTEGER, reader_version INTEGER, text TEXT,
          PRIMARY KEY(message_id, attachment_id, reader_version))
        """);
  }

  private synchronized List<PackingSafety.Document> read(Map<String, Object> message, boolean packing) {
    var documents = new ArrayList<PackingSafety.Document>();
    for (Object raw : ledger.read(message.get("attachments"), List.class)) {
      var file = (Map<?, ?>) raw;
      var key = new Attachment(Ledger.id(message.get("id")), Ledger.id(file.get("id")),
          edgeReader != null || (packing && packingTools));
      try {
        if (!extracted.containsKey(key)) {
          String filename = Objects.toString(file.get("filename"), "");
          extracted.put(key, readAttachment(key, filename));
        }
        documents.add(extracted.get(key));
      } catch (RestClientException ex) {
        if (key.recoverPacking() && (ex instanceof ResourceAccessException
            || ex instanceof org.springframework.web.client.RestClientResponseException response
                && (response.getStatusCode().is5xxServerError() || response.getStatusCode().value() == 429))) {
          defer(key, downloadAction(key), ex);
          throw new WaveWorker.RetryLater("Retrying the attachment download.");
        }
        documents.add(null);
      } catch (IOException | XMLStreamException ex) {
        documents.add(null);
      }
    }
    return documents;
  }

  private PackingSafety.Document readAttachment(Attachment key, String filename)
      throws IOException, XMLStreamException {
    if (key.recoverPacking()) {
      if (pending.contains(key)) throw new WaveWorker.RetryLater("Reading the attachment.");
      var prior = ledger.jdbc().queryForList(
          "SELECT text FROM packing_tool_read WHERE message_id=? AND attachment_id=? AND reader_version=?",
          String.class, key.messageId(), key.attachmentId(), readerVersion);
      if (!prior.isEmpty()) return prior.getFirst() == null ? null : new PackingSafety.Document(prior.getFirst());
      var coolingDown = ledger.jdbc().queryForObject(
          "SELECT count(*) FROM retry WHERE action IN (?,?) AND after_epoch>?", Integer.class,
          downloadAction(key), edgeAction(key), clock.instant().getEpochSecond());
      if (coolingDown > 0) throw new WaveWorker.RetryLater("Waiting to retry the attachment.");
    }
    // A download error is transient. Let read() retry it on the next pass.
    byte[] bytes = upstream.bytes(key.messageId(), key.attachmentId());
    if (!key.recoverPacking()) return new PackingSafety.Document(PackingSafety.extract(filename, bytes));
    ledger.jdbc().update("DELETE FROM retry WHERE action=?", downloadAction(key));
    if (bytes == null || bytes.length == 0 || bytes.length > PackingSafety.MAX_BYTES) return null;

    boolean mislabeledPdf = !filename.toLowerCase(Locale.ROOT).endsWith(".pdf")
        && PackingAttachmentTools.hasPdfHeader(bytes);
    if (!mislabeledPdf) {
      try {
        String text = PackingSafety.extract(filename, bytes);
        if (!text.isBlank() && (edgeReader == null || text.indexOf('\0') < 0))
          return new PackingSafety.Document(text);
      } catch (IOException | XMLStreamException unreadable) {
        // Try a complete plain-text packing document; binary containers stay on hold.
      }
    }
    String recovered = null;
    if (mislabeledPdf) {
      try {
        recovered = PackingSafety.extract("packing.pdf", bytes);
      } catch (IOException | XMLStreamException unreadablePdf) {
        // Preserve the existing page, size and readability checks.
      }
    } else {
      recovered = PackingAttachmentTools.recoverPackingText(bytes).orElse(null);
    }
    if (recovered == null && edgeReader != null) {
      scheduleEdgeRead(key, filename, bytes);
      throw new WaveWorker.RetryLater("Reading the attachment.");
    }
    storeResult(key, recovered);
    return recovered == null ? null : new PackingSafety.Document(recovered);
  }

  private void storeResult(Attachment key, String text) {
    ledger.jdbc().update("INSERT OR IGNORE INTO packing_tool_read(message_id,attachment_id,reader_version,text) VALUES(?,?,?,?)",
        key.messageId(), key.attachmentId(), readerVersion, text);
  }

  private String downloadAction(Attachment key) {
    return "packing-download:" + key.messageId() + ":" + key.attachmentId();
  }

  private String edgeAction(Attachment key) {
    return "edge-read:" + readerVersion + ":" + key.messageId() + ":" + key.attachmentId();
  }

  private void defer(Attachment key, String action, Exception failure) {
    ledger.jdbc().update("INSERT INTO retry(action,after_epoch) VALUES(?,?) "
        + "ON CONFLICT(action) DO UPDATE SET after_epoch=excluded.after_epoch",
        action, clock.instant().getEpochSecond() + 15);
    log.warn("Attachment {}/{} will retry after {} ({})", key.messageId(), key.attachmentId(),
        action.startsWith("edge-read:") ? "reader failure" : "download failure", failure.getClass().getSimpleName());
  }

  private void scheduleEdgeRead(Attachment key, String filename, byte[] bytes) {
    if (pending.size() >= 2) throw new WaveWorker.RetryLater("Waiting for an attachment reader.");
    pending.add(key);
    try {
      edgeExecutor.execute(() -> {
        try {
          String result = edgeReader.read(filename, bytes);
          storeResult(key, result);
          ledger.jdbc().update("DELETE FROM retry WHERE action=?", edgeAction(key));
          if (result == null) log.info("Attachment {}/{} had no usable document.", key.messageId(), key.attachmentId());
        } catch (IOException | RuntimeException ex) {
          defer(key, edgeAction(key), ex);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
        } finally {
          pending.remove(key);
        }
      });
    } catch (RejectedExecutionException busy) {
      pending.remove(key);
      throw new WaveWorker.RetryLater("Waiting for an attachment reader.");
    }
  }

  Map<String, Object> withAttachedRequest(Map<String, Object> original) {
    if (hasAttachment(original, "voice-message.mp3")) {
      String transcript = voiceTranscript(original);
      if (transcript.isBlank()) return original;
      var message = withRequestText(original, SPOKEN_KILOS.matcher(transcript)
          .replaceAll(match -> match.group(1).replace(",", "") + " kg"));
      message.put("voice_transcript", transcript);
      return message;
    }
    if (!hasRequestAttachment(original) && (edgeReader == null || !hasSingleAttachment(original))) return original;
    var text = requestText(read(original, false));
    if (text == null) return original;
    return withRequestText(original, text);
  }

  private String voiceTranscript(Map<String, Object> message) {
    String transcript = Ledger.str(message, "voice_transcript");
    if (transcript.isBlank()) transcript = transcribe(message);
    if (transcript.isBlank() && edgeReader != null && !awaitingVoice(message)) {
      var files = read(message, false);
      if (files.size() == 1 && files.getFirst() != null) return files.getFirst().text();
    }
    return transcript;
  }

  private Map<String, Object> withRequestText(Map<String, Object> original, String text) {
    var message = new HashMap<>(original);
    message.put("body", MailParser.authoredText(Ledger.str(original, "body")) + "\n\n" + text);
    return message;
  }

  private String transcribe(Map<String, Object> message) {
    long id = Ledger.id(message.get("id"));
    String action = "voice-rfq:" + id;
    long now = clock.instant().getEpochSecond();
    int claimed = ledger.jdbc().update("""
        INSERT INTO retry(action,after_epoch) VALUES(?,?)
        ON CONFLICT(action) DO UPDATE SET after_epoch=excluded.after_epoch
        WHERE retry.after_epoch>0 AND retry.after_epoch<=?
          AND (SELECT voice_transcript FROM mail WHERE id=?) IS NULL
        """, action, now + 90, now, id);
    if (claimed == 0) return recordedVoice(id);
    try {
      var file = (Map<?, ?>) ledger.read(message.get("attachments"), List.class).getFirst();
      long attachment = Ledger.id(file.get("id"));
      voiceExecutor.execute(() -> {
        try {
          var text = upstream.transcribe(upstream.bytes(id, attachment));
          ledger.jdbc().update("UPDATE mail SET voice_transcript=? WHERE id=?", text, id);
        } catch (IOException | RestClientException ex) {
          ledger.jdbc().update("UPDATE retry SET after_epoch=0 WHERE action=?", action);
        }
      });
    } catch (RejectedExecutionException ex) {
      ledger.jdbc().update("DELETE FROM retry WHERE action=?", action);
    }
    return recordedVoice(id);
  }

  private String recordedVoice(long id) {
    return Objects.toString(ledger.jdbc().queryForObject(
        "SELECT voice_transcript FROM mail WHERE id=?", String.class, id), "");
  }

  boolean awaitingVoice(Map<String, Object> message) {
    if (!hasAttachment(message, "voice-message.mp3")
        || !Ledger.str(message, "voice_transcript").isBlank()) return false;
    long id = Ledger.id(message.get("id"));
    // A positive claim also covers a transcript committed after this read of the message.
    var attempts = ledger.jdbc().queryForList(
        "SELECT after_epoch FROM retry WHERE action=?", Long.class, "voice-rfq:" + id);
    return attempts.isEmpty() || attempts.getFirst() > 0;
  }

  private boolean hasRequestAttachment(Map<String, Object> message) {
    return hasAttachment(message, "quotation-request.pdf", "quotation-request.docx", "quotation-request.txt");
  }

  private boolean hasSingleAttachment(Map<String, Object> message) {
    var attachments = ledger.read(message.get("attachments"), List.class);
    return attachments != null && attachments.size() == 1 && attachments.getFirst() instanceof Map<?, ?>;
  }

  private boolean hasAttachment(Map<String, Object> message, String... names) {
    var attachments = ledger.read(message.get("attachments"), List.class);
    return attachments != null && attachments.size() == 1
        && attachments.getFirst() instanceof Map<?, ?> file
        && Set.of(names).contains(Ledger.str(file, "filename").toLowerCase(Locale.ROOT));
  }

  private static String requestText(List<PackingSafety.Document> files) {
    if (files.size() != 1 || files.getFirst() == null) return null;
    var file = files.getFirst();
    var text = file.text().strip();
    return file.type() == PackingSafety.DocumentType.UNKNOWN
        && text.lines().findFirst().orElse("").strip().matches(
            "(?i)(?:Request for quotation|Air freight quote(?: request)?(?:[ :].*)?)")
        ? text : null;
  }

  String attachedOffer(Map<String, Object> message, boolean truck) {
    if (hasAttachment(message, "voice-message.mp3")) {
      var transcript = voiceTranscript(message);
      var received = Ledger.instant(message.get("received"));
      if (transcript.isBlank() || received == null) return null;
      int year = received.atZone(java.time.ZoneOffset.UTC).getYear();
      var costs = SPOKEN_EUROS.matcher(transcript).replaceAll(match ->
          String.format(Locale.ROOT, "%s.%02d EUR", match.group(1), Integer.parseInt(match.group(2))));
      if (truck) costs = normalizeSpokenPickup(costs);
      if (OPEN_OFFER_TERMS.matcher(truck ? MailParser.withoutPickupWaitingCharge(costs) : costs).find())
        return null;
      return SPOKEN_EXPIRY.matcher(costs).replaceAll(match ->
          "Valid until " + match.group(1) + " " + match.group(2).substring(0, 3) + " "
              + (match.group(3) == null ? year : match.group(3)) + " " + match.group(4) + " UTC");
    }
    var attachments = ledger.read(message.get("attachments"), List.class);
    if (attachments == null || attachments.size() != 1
        || !(attachments.getFirst() instanceof Map<?, ?> file)
        || (edgeReader == null && !Ledger.str(file, "filename").toLowerCase(Locale.ROOT).matches(".*\\.(?:txt|pdf|docx)")))
      return null;
    var files = read(message, false);
    if (files.size() != 1 || files.getFirst() == null) return null;
    var text = files.getFirst().text();
    if (OPEN_OFFER_TERMS.matcher(truck ? MailParser.withoutPickupWaitingCharge(text) : text).find())
      return null;
    if (FIRM_PICKUP_TOTAL.matcher(text).find())
      text = PICKUP_BREAKDOWN.matcher(text).replaceAll("");
    if (text.contains("Amount (EUR)"))
      text = OFFER_TOTAL.matcher(text).replaceAll("Total EUR $1");
    return text;
  }

  private static String normalizeSpokenPickup(String text) {
    var prices = text.replaceAll("€\\h*", "EUR ")
        .replaceAll("(?iu)(?<![\\w.,])([0-9]+(?:[.,][0-9]{1,2})?)\\h+EUR(?:os?)?\\b", "EUR $1");
    var totals = SPOKEN_PICKUP_BREAKDOWN.matcher(prices).replaceAll(match -> {
      var base = new BigDecimal(match.group(1).replace(',', '.'));
      var weight = new BigDecimal(match.group(2).replace(',', '.'));
      var total = new BigDecimal(match.group(3).replace(',', '.'));
      return base.add(weight).compareTo(total) == 0
          ? "Total EUR " + total.toPlainString() + "." : match.group();
    });
    return SPOKEN_PICKUP_WAIT.matcher(totals).replaceAll(match -> {
      var fee = new BigDecimal(match.group(2).replace(',', '.')).setScale(2);
      return "\nNot part of this offer: waiting time at the pickup address after the first "
          + match.group(1) + " minutes, " + fee.toPlainString() + " EUR for each hour.\n";
    });
  }

  Decision inspect(Map<String, Object> original, List<Map<String, Object>> followUps,
      String confirmedPickupZone, String customerDeliveryAirport) {
    boolean voiceRequest = hasAttachment(original, "voice-message.mp3")
        && !Ledger.str(original, "voice_transcript").isBlank();
    var latestPacking = voiceRequest
        ? List.of(new PackingSafety.Document(Ledger.str(original, "voice_transcript"))) : read(original, true);
    var originalPacking = latestPacking;
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
      var attachments = read(message, true);
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
    if (latestPacking == originalPacking && (voiceRequest
        || ((hasRequestAttachment(original) || edgeReader != null) && requestText(latestPacking) != null))) {
      var parsed = MailParser.request(Ledger.str(original, "subject"), Ledger.str(original, "body"),
          false, catalog, confirmedPickupZone, customerDeliveryAirport);
      return new Decision(null, null, null, parsed.issue() == null ? parsed.shipment() : null);
    }
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
