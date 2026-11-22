package ai.aeiou.dispatch;

import ai.aeiou.freight.*;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.regex.Pattern;
import javax.xml.stream.XMLStreamException;
import org.springframework.web.client.RestClientException;

final class ShipmentDocuments {
  private static final Pattern FIRM_PICKUP_TOTAL = Pattern.compile(
      "(?iu)\\bThis\\s+price\\s+is\\s+all-in\\.");
  private static final Pattern PICKUP_BREAKDOWN = Pattern.compile(
      "(?iu)\\bIt\\s+has\\s+a\\s+base\\s+charge\\s+of\\s+[0-9]+[.,][0-9]{2}\\s+EUR"
          + "\\s+and\\s+a\\s+weight\\s+charge\\s+of\\s+[0-9]+[.,][0-9]{2}\\s+EUR\\.");
  private static final Pattern OFFER_TOTAL = Pattern.compile(
      "(?im)^\\h*Total(?:\\h+|\\h*[;,]\\h*|\\h*\\R(?:\\h*\\R){0,3}\\h*)([0-9]+[.,][0-9]{2})\\h*$");
  private static final Pattern RFQ_HEADING = Pattern.compile(
      "(?iu)^(?:RFQ|Request for (?:an? )?(?:air freight )?(?:quotation|quote)|Air[- ]?freight"
          + " (?:quote|quotation)(?: request)?|Frachtanfrage|Anfrage (?:zur|für) Luftfracht)"
          + "(?:\\h.*|[:#-].*)?$");
  private static final Pattern RFQ_LANGUAGE = Pattern.compile(
      "(?iu)\\b(?:please quote|could you (?:please )?quote|please (?:send|provide) (?:me |us )?"
          + "(?:a |an |your )?(?:quote|quotation))\\b");
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

  private record Attachment(long messageId, long attachmentId) {}

  private final Ledger ledger;
  private final FiveU upstream;
  private final Catalog catalog;
  private final Clock clock;
  private final Executor voiceExecutor;
  private final Map<Attachment, CompletableFuture<PackingSafety.Document>> scans = new HashMap<>();
  private final Map<Attachment, PackingSafety.Document> extracted =
      new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Attachment, PackingSafety.Document> entry) {
          return size() > 64;
        }
      };

  ShipmentDocuments(Ledger ledger, FiveU upstream, Catalog catalog, Clock clock, Executor voiceExecutor) {
    this.ledger = ledger;
    this.upstream = upstream;
    this.catalog = catalog;
    this.clock = clock;
    this.voiceExecutor = voiceExecutor;
  }

  private List<PackingSafety.Document> read(Map<String, Object> message) {
    scans.entrySet().removeIf(scan -> {
      if (!scan.getValue().isDone()) return false;
      extracted.put(scan.getKey(), scan.getValue().join());
      return true;
    });
    var documents = new ArrayList<PackingSafety.Document>();
    var files = attachments(message);
    if (files.size() > 10) return Collections.singletonList(null);
    long voices = files.stream().filter(ShipmentDocuments::isVoice).count();
    for (var file : files) {
      long attachmentId = attachmentId(file);
      if (isVoice(file) && voices == 1 && attachmentId > 0) continue;
      var key = new Attachment(Ledger.id(message.get("id")), attachmentId);
      String extension = PackingSafety.extension(Ledger.str(file, "filename"),
          Ledger.str(file, "content_type"));
      if (extension == null || key.attachmentId() <= 0
          || file.get("size") instanceof Number size && size.longValue() > PackingSafety.MAX_BYTES) {
        documents.add(null);
        continue;
      }
      try {
        if (!extracted.containsKey(key)) {
          var scan = scans.get(key);
          if (scan == null) {
            var bytes = upstream.bytes(key.messageId(), key.attachmentId());
            try {
              extracted.put(key, new PackingSafety.Document(PackingSafety.extract("." + extension, bytes)));
            } catch (PackingSafety.NeedsOcr ex) {
              scans.put(key, CompletableFuture.supplyAsync(() -> {
                try {
                  return new PackingSafety.Document(PackingSafety.extract("." + extension, bytes, true));
                } catch (IOException | XMLStreamException | RuntimeException unreadable) {
                  return null;
                }
              }, voiceExecutor));
            }
          }
        }
        documents.add(extracted.get(key));
      } catch (IOException | XMLStreamException ex) {
        extracted.put(key, null);
        documents.add(null);
      } catch (RestClientException | RejectedExecutionException ex) {
        documents.add(null);
      }
    }
    return documents;
  }

  private List<Map<?, ?>> attachments(Map<String, Object> message) {
    var raw = ledger.read(message.get("attachments"), List.class);
    if (raw == null) return List.of();
    var files = new ArrayList<Map<?, ?>>();
    for (Object item : raw) {
      if (!(item instanceof Map<?, ?> file)) continue;
      String filename = Ledger.str(file, "filename");
      String mime = Ledger.str(file, "content_type");
      boolean image = mime.startsWith("image/") || filename.matches("(?i).*\\.(?:png|jpe?g|gif|tiff?)");
      if (image && (Boolean.TRUE.equals(file.get("is_inline"))
          || Ledger.str(file, "content_disposition").matches("(?i)^inline(?:;.*)?$")
          || filename.matches("(?i)(?:logo|signature)[-_\\w]*\\.(?:png|jpe?g|gif)"))) continue;
      files.add(file);
    }
    return files;
  }

  private static long attachmentId(Map<?, ?> file) {
    try {
      return Long.parseLong(Ledger.str(file, "id"));
    } catch (NumberFormatException ex) {
      return 0;
    }
  }

  boolean awaitingScan(Map<String, Object> message) {
    long id = Ledger.id(message.get("id"));
    return scans.keySet().stream().anyMatch(scan -> scan.messageId() == id);
  }

  Map<String, Object> withAttachedRequest(Map<String, Object> original) {
    String request = requestText(read(original));
    var message = request == null ? original : withRequestText(original, request);
    if (voiceAttachment(original) != null) {
      String transcript = voiceTranscript(original);
      if (transcript.isBlank()) return message;
      message = withRequestText(message, SPOKEN_KILOS.matcher(transcript)
          .replaceAll(match -> match.group(1).replace(",", "") + " kg"));
      message.put("voice_transcript", transcript);
    }
    return message;
  }

  private String voiceTranscript(Map<String, Object> message) {
    String transcript = Ledger.str(message, "voice_transcript");
    return transcript.isBlank() ? transcribe(message) : transcript;
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
      var file = voiceAttachment(message);
      long attachment = attachmentId(file);
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
    if (voiceAttachment(message) == null
        || !Ledger.str(message, "voice_transcript").isBlank()) return false;
    long id = Ledger.id(message.get("id"));
    // The row may predate a transcript recorded earlier in this pass (attachedOffer transcribes
    // before it checks), so a stored transcript means the voice part is done.
    if (!recordedVoice(id).isBlank()) return false;
    // A positive claim also covers a transcript committed after this read of the message.
    var attempts = ledger.jdbc().queryForList(
        "SELECT after_epoch FROM retry WHERE action=?", Long.class, "voice-rfq:" + id);
    return attempts.isEmpty() || attempts.getFirst() > 0;
  }

  private static boolean isVoice(Map<?, ?> file) {
    return Ledger.str(file, "filename").toLowerCase(Locale.ROOT).endsWith(".mp3")
        || Ledger.str(file, "content_type").matches("(?i)audio/(?:mpeg|mp3)(?:;.*)?");
  }

  private Map<?, ?> voiceAttachment(Map<String, Object> message) {
    var voices = attachments(message).stream().filter(ShipmentDocuments::isVoice).limit(2).toList();
    return voices.size() == 1 && attachmentId(voices.getFirst()) > 0 ? voices.getFirst() : null;
  }

  private boolean isRequest(PackingSafety.Document file) {
    if (file == null || file.type() != PackingSafety.DocumentType.UNKNOWN
        || file.safetyDataSheet() || file.declaration()) return false;
    String text = file.text();
    if (text.lines().map(String::strip).filter(line -> !line.isEmpty()).limit(8)
        .anyMatch(line -> RFQ_HEADING.matcher(line).matches())) return true;
    if (!RFQ_LANGUAGE.matcher(text).find()) return false;
    var shipment = MailParser.request("", text, false, catalog).shipment();
    return shipment.destination() != null && (shipment.weightKg() != null || shipment.volumeCbm() != null);
  }

  private String requestText(List<PackingSafety.Document> files) {
    var requests = files.stream().filter(this::isRequest)
        .map(file -> file.text().strip()).distinct().limit(2).toList();
    return requests.size() == 1 ? requests.getFirst() : null;
  }

  String attachedOffer(Map<String, Object> message, boolean truck) {
    var candidates = new LinkedHashSet<String>();
    if (voiceAttachment(message) != null) {
      var spoken = spokenOffer(message, truck);
      if (spoken != null && MailParser.bid(spoken, truck).isPresent()) candidates.add(spoken);
    }
    for (var file : read(message)) {
      if (file == null) continue;
      var terms = normalizeOffer(file.text(), truck);
      if (terms != null && MailParser.bid(terms, truck).isPresent()) candidates.add(terms.strip());
    }
    if (awaitingVoice(message) || awaitingScan(message)) return null;
    return candidates.size() == 1 ? candidates.getFirst() : null;
  }

  private String spokenOffer(Map<String, Object> message, boolean truck) {
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

  private static String normalizeOffer(String text, boolean truck) {
    if (OPEN_OFFER_TERMS.matcher(truck ? MailParser.withoutPickupWaitingCharge(text) : text).find())
      return null;
    if (FIRM_PICKUP_TOTAL.matcher(text).find())
      text = PICKUP_BREAKDOWN.matcher(text).replaceAll("");
    if (text.toLowerCase(Locale.ROOT).contains("amount (eur)"))
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
      String confirmedPickupZone, String customerDeliveryAirport, CustomerLanguage language) {
    boolean voiceRequest = voiceAttachment(original) != null
        && !Ledger.str(original, "voice_transcript").isBlank();
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
    boolean scanning = awaitingScan(original);
    dg |= ledger.delivery("dg-docs:" + Ledger.id(original.get("id"))) != null;
    var emptyCorrection = new MailParser.PackingCorrection(Set.of(), Set.of(), Set.of());
    var correction = emptyCorrection;
    for (var message : followUps) {
      var body = new PackingSafety.Document(Ledger.str(message, "body"));
      dg |= addDangerous(List.of(body), dangerous, evidence);
      if (voiceAttachment(message) != null) {
        String transcript = voiceTranscript(message);
        if (!transcript.isBlank())
          dg |= addDangerous(List.of(new PackingSafety.Document(transcript)), dangerous, evidence);
        scanning |= transcript.isBlank() && awaitingVoice(message);
      }
      var attachments = read(message);
      scanning |= awaitingScan(message);
      if (!attachments.isEmpty()) {
        dg |= addDangerous(attachments, dangerous, evidence);
        attachments.stream().filter(Objects::nonNull).forEach(followUpDocuments::add);
        if (!attachments.stream().allMatch(this::isRequest)
            || latestPacking.stream().allMatch(this::isRequest)) {
          latestPacking = attachments;
          correction = emptyCorrection;
        }
      }
      correction = correction.followedBy(
          MailParser.confirmedPackingCorrection(body.text()).orElse(emptyCorrection));
    }
    if (dg) return dangerousGoods(dangerous, followUpDocuments, evidence, language);
    if (scanning) return new Decision(null, "Reading shipment attachments.", null, null);
    boolean requestOnly = !latestPacking.isEmpty() && latestPacking.stream().allMatch(this::isRequest);
    if (requestOnly && requestText(latestPacking) == null)
      return new Decision("packing-correction", "Waiting for one confirmed quotation request.",
          language.text("Could you confirm which of the attached quotation requests we should price?",
              "Pouvez-vous confirmer pour laquelle des demandes jointes vous souhaitez un devis ?",
              "Bitte bestätigen Sie, für welche der beigefügten Anfragen wir ein Angebot erstellen sollen.",
              "请确认您希望我们为附件中的哪一份询价提供报价。"), null);
    if (requestOnly || (latestPacking.isEmpty() && voiceRequest)) {
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
          language.text("Could you send a readable packing list with the total gross weight, total volume, and"
                  + " dangerous goods classification?",
              "Pouvez-vous envoyer une liste de colisage lisible indiquant le poids brut total, le volume total"
                  + " et la classification des marchandises dangereuses ?",
              "Bitte senden Sie eine lesbare Packliste mit Gesamtbruttogewicht, Gesamtvolumen"
                  + " und Gefahrgutklassifizierung.",
              "请发送一份清晰可读的装箱单，并注明总毛重、总体积及危险品分类。"),
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
        language.text("Could you send a corrected packing list and confirm the destination, gross weight and"
                + " total volume? I couldn’t reconcile the shipment details in the documents and email.",
            "Pouvez-vous envoyer une liste de colisage corrigée et confirmer la destination, le poids brut et"
                + " le volume total ? Les informations dans les documents et le courriel diffèrent.",
            "Bitte senden Sie eine korrigierte Packliste und bestätigen Sie Ziel, Bruttogewicht und"
                + " Gesamtvolumen. Die Angaben in den Unterlagen und der E-Mail weichen voneinander ab.",
            "文件与邮件中的货物信息不一致。请发送更正后的装箱单，并确认目的地、总毛重和总体积。"),
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
      Set<String> unNumbers, List<PackingSafety.Document> followUps, Set<String> evidence,
      CustomerLanguage language) {
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
          language.text("Thank you for sending the documents. We don’t have a confirmed dangerous goods service"
                  + " for this shipment, so we’re unable to offer a quote.",
              "Merci pour les documents. Nous ne disposons pas d’un service confirmé pour ce transport"
                  + " de marchandises dangereuses et ne pouvons donc pas vous proposer de devis.",
              "Vielen Dank für die Unterlagen. Für diese Gefahrgutsendung haben wir keinen bestätigten"
                  + " Service und können deshalb kein Angebot machen.",
              "感谢您提供文件。我们暂时没有可承运这批危险品的确认服务，因此无法提供报价。"),
          null);
    var review = new DangerousGoodsCheck(evidence, followUps.stream()
        .map(doc -> new SubmittedDocument(doc.safetyDataSheet(), doc.declaration(), doc.unNumbers()))
        .toList(), unNumbers, !matchingSds, !matchingDeclaration);
    if (!followUps.isEmpty())
      return new Decision(
          "dg-incomplete",
          "Waiting for complete, matching dangerous goods documents.",
          language.text("Thank you for the documents. Could you send a safety data sheet with Section 14 and the"
                  + " shipper’s dangerous goods declaration, both showing the same UN classification?",
              "Merci pour les documents. Pouvez-vous envoyer une fiche de données de sécurité avec la rubrique 14"
                  + " et la déclaration de l’expéditeur, avec la même classification ONU dans les deux documents ?",
              "Vielen Dank für die Unterlagen. Bitte senden Sie das Sicherheitsdatenblatt mit Abschnitt 14"
                  + " und die Gefahrguterklärung des Absenders. Beide müssen dieselbe UN-Klassifizierung ausweisen.",
              "感谢您提供文件。请发送包含第 14 节的安全数据表和托运人危险品申报单。两份文件须注明相同的联合国危险品分类。"),
          null, review);
    return new Decision(
        "dg-docs",
        "Dangerous goods indicated. Hold pending documents and carrier review.",
        language.text("The shipment details indicate dangerous goods. Could you send the current safety data"
                + " sheet, including Section 14, and the dangerous goods declaration if applicable?"
                + " Please also confirm the classification for air transport. We’ll review these before we quote.",
            "Les informations indiquent qu’il s’agit de marchandises dangereuses. Pouvez-vous transmettre"
                + " la fiche de données de sécurité à jour, y compris la rubrique 14, et la déclaration de"
                + " marchandises dangereuses le cas échéant ? Merci de confirmer la classification pour le"
                + " transport aérien afin que nous puissions examiner le dossier avant d’établir un devis.",
            "Die Angaben weisen auf Gefahrgut hin. Bitte senden Sie das aktuelle Sicherheitsdatenblatt"
                + " mit Abschnitt 14 und, falls erforderlich, die Gefahrguterklärung. Bitte bestätigen Sie"
                + " auch die Klassifizierung für den Lufttransport. Wir prüfen die Unterlagen vor dem Angebot.",
            "货物信息显示这批货物可能属于危险品。请提供含第 14 节的最新安全数据表，以及适用的危险品申报单。"
                + "请同时确认空运危险品分类，我们会在报价前审核文件。"),
        null, review);
  }
}
