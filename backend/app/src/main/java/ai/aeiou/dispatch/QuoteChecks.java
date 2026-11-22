package ai.aeiou.dispatch;

import ai.aeiou.freight.MailParser;
import ai.aeiou.freight.Shipment;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

final class QuoteChecks {
  static final String REVIEW = "Verify all requested charges are included.";
  static final String PICKUP_REVIEW = "Confirm a pickup price without unresolved charges or timing conditions.";
  static final String SCHEDULE_REVIEW = "Confirm the pickup slot, flight cutoff and operating schedule before quoting arrival.";

  private static final Pattern ADDITIONAL_CHARGE = pattern(
      "\\b(?:customs (?:clearance|brokerage|fees?)|brokerage|dut(?:y|ies)|VAT|sales tax|"
          + "fuel surcharge|security (?:screening|surcharge)|terminal (?:handling|charges?)|"
          + "re[- ]?packing|crating|pallet wrapping|warehousing|storage fees?|delivery after arrival|last[ -]mile delivery|"
          + "hazmat surcharge|documentation (?:fee|charge)s?|handling (?:fees?|charges?)|"
          + "waiting(?: time)? (?:fees?|charges?|surcharges?|costs?))\\b");
  private static final Pattern UNKNOWN_CHARGE = pattern(
      "\\b(?:include|cover|factor in|allow for|add|inclusive of|including)\\h+"
          + "([^.!?;\\r\\n]{0,85}?\\b(?:fees?|charges?|surcharges?|costs?|tax(?:es)?))\\b");
  private static final Pattern PRICED_CHARGE = pattern(
      "\\b(?:(?:road |warehouse )?(?:pickup|collection|truck(?:ing)?|road transport) (?:fees?|charges?|costs?)|"
          + "(?:cargo )?insurance(?: (?:handling|administration|admin|issuance))? (?:fees?|charges?|costs?)|"
          + "minimum insurance charge|policy(?: (?:handling|administration|admin|issuance))? (?:fee|charge)|"
          + "fixed(?: (?:administration|admin|issuance))? fee|(?:air )?freight (?:fees?|charges?|costs?))\\b");
  private static final Pattern EXCLUDED = pattern(
      "\\b(?:exclude|without|do not include|don['’]t include|no need (?:for|to include))\\h+"
          + "(?:(?!\\b(?:but|however|plus|including|and (?:include|add|cover))\\b)[^,.!?;\\r\\n])+");
  private static final Pattern CUSTOMER_HANDLES_CUSTOMS = pattern(
      "\\b(?:we (?:will |can )?|we['’]ll |our broker will )(?:handle|arrange|take care of) (?:the )?customs"
          + "(?: clearance| brokerage)?\\b|\\b(?:customs clearance|duties(?: and taxes)?|VAT)"
          + "\\h+(?:is |are |will be )?(?:excluded|handled by us|paid separately)\\b");
  private static final Pattern FIXED_FEE = pattern("\\bfixed(?: (?:administration|admin|issuance))? fee\\b");
  private static final Pattern EXPLICIT_INCLUSION = pattern(
      "\\b(?:please|also|must|should|need to|please also)\\h+include\\h+(?:the |an? |our |your )?"
          + "([^,;.!?\\r\\n]{2,100})");
  private static final Pattern QUOTE_OR_KNOWN_SERVICE = pattern(
      "^(?:(?:all[ -]in |total |final )?(?:price|cost|rate|quote|quotation)\\b|"
          + "(?:arranging |arrangement of |organizing )?(?:the |a )?(?:truck(?:ing)?|pickup|collection|"
          + "air freight|(?:cargo |freight |transport )?insurance|policy fee)\\b|"
          + "(?:delivery|transport) to (?:the |our |your )?(?:departure )?airport\\b|"
          + "(?:(?:estimated|planned|expected|earliest|next)(?: available)? |available )?"
          + "(?:transit|arrival|departure|delivery)(?: (?:times?|dates?))?\\b|"
          + "(?:pdf|xlsx|xls|excel|word|docx|csv|txt|attachment|offer|carrier name|routing|"
          + "weight|volume|dimensions|service details|confirmation|all (?:fees|charges|costs))\\b)");
  private static final Pattern SCHEDULE = pattern(
      "\\b(?:cut[ -]?off|flight schedule|confirmed (?:flight|departure|pickup|collection|arrival) "
          + "(?:time|date|slot)|pickup (?:appointment|slot|time window)|collection (?:appointment|slot|time window)|"
          + "no (?:weekend|saturday|sunday) (?:pickup|collection|flights?|departures?|delivery)|"
          + "business days only|working days only|weekend (?:pickup|collection|delivery) only|"
          + "(?:pickup|collection|depart(?:ure)?|arriv(?:e|al))\\b[^.!?;\\r\\n]{0,40}\\b"
          + "(?:by|at|before|after)\\h+(?:\\d{1,2}:\\d{2}|\\d{1,2}\\h*(?:am|pm))\\b)");
  private static final Pattern CONDITIONAL_CARRIER = pattern(
      "\\b(?:business|working) days\\b|\\b(?:weekdays only|weekend service unavailable|"
          + "(?:subject to|depending on) (?:flight|capacity|availability|pickup|collection)|"
          + "only if|subject to an appointment)\\b");
  private static final Pattern LINKED_CHARGES = pattern("\\b(?:and|plus|as well as)\\b");
  private static final Pattern MATERIAL_CHANGE = pattern(
      "\\b(?:revised|updated|new|corrected|final) (?:packing list|shipment details|gross weight|volume|cargo ready date)\\b"
          + "|\\b(?:weight|volume|ready date|postcode|destination|pickup address) (?:has changed|is now)\\b");
  private static final Pattern SHIPPING_DETAILS = pattern(
      "\\b(?:postcode|pickup address|destination|ready date|arrival date|flight|packing list|"
          + "gross weight|volume|dimensions|kg|kgs|cbm|m³|pieces|pallets?)\\b");
  private static final Pattern PICKUP_EXTRAS = pattern(
      "\\b(?:(?:waiting|waiting time|detention) (?:fees?|charges?|surcharges?|costs?)|"
          + "(?:additional|extra|separate) (?:fees?|charges?|surcharges?)|"
          + "(?:exclud(?:es|ing)|plus) (?:the )?(?:waiting|detention|pickup charges|fuel|tolls)|"
          + "(?:waiting|detention) (?:is )?not included)\\b");

  static void preflight(Map<String, Object> message) {
    String body = MailParser.authoredText(Ledger.str(message, "body"));
    String text = Ledger.str(message, "subject") + "\n" + body;
    if (SCHEDULE.matcher(text).find()) throw new WaveWorker.Conflict(SCHEDULE_REVIEW);
    var chargeText = CUSTOMER_HANDLES_CUSTOMS.matcher(EXCLUDED.matcher(text).replaceAll("")).replaceAll("");
    if (FIXED_FEE.matcher(chargeText).find() && !CargoInsurance.mentioned(chargeText))
      throw new WaveWorker.Conflict(REVIEW + " Confirm what the fixed fee covers.");
    var unpriced = PRICED_CHARGE.matcher(chargeText).replaceAll("");
    var named = ADDITIONAL_CHARGE.matcher(unpriced);
    if (named.find()) throw new WaveWorker.Conflict(REVIEW + " Check " + named.group() + ".");
    var other = UNKNOWN_CHARGE.matcher(chargeText);
    while (other.find()) {
      String charge = other.group(1);
      if (!PRICED_CHARGE.matcher(charge).find() && !charge.matches("(?iu)(?:the )?(?:all|any|every)\\h+(?:fees?|charges?|costs?)"))
        throw new WaveWorker.Conflict(REVIEW + " Check " + charge + ".");
      if (PRICED_CHARGE.matcher(charge).find() && LINKED_CHARGES.matcher(charge).find()
          && !PRICED_CHARGE.matcher(charge).replaceAll("").isBlank()) {
        String remainder = PRICED_CHARGE.matcher(charge).replaceAll("").strip();
        if (!remainder.matches("(?iu)(?:the|any|all|both|and|plus|as well as|\\s|[,/])*"))
          throw new WaveWorker.Conflict(REVIEW + " Check " + charge + ".");
      }
    }
    var inclusion = EXPLICIT_INCLUSION.matcher(chargeText);
    while (inclusion.find()) {
      for (String part : LINKED_CHARGES.split(inclusion.group(1))) {
        String request = part.strip().replaceFirst("(?iu)^(?:the|a|an|our|your)\\h+", "");
        if (!QUOTE_OR_KNOWN_SERVICE.matcher(request).find())
          throw new WaveWorker.Conflict(REVIEW + " Check " + request + ".");
      }
    }
  }

  static void verify(CustomerClarifications.Details details, Shipment shipment, WaveWorker.Draft draft,
      String customerText) {
    verify(details, shipment, draft, customerText, CargoInsurance.from(details.message()));
  }

  static void verify(CustomerClarifications.Details details, Shipment shipment, WaveWorker.Draft draft,
      String customerText, CargoInsurance insurance) {
    preflight(details.message());
    if (!Objects.equals(insurance, draft.insurance()))
      throw new WaveWorker.Conflict(REVIEW + " Recalculate the cargo insurance.");
    boolean pickup = details.deliveryAirport() == null && !MailParser.selfDelivery(Ledger.str(details.message(), "body"))
        && MailParser.requestsPickup(Ledger.str(details.message(), "subject"), Ledger.str(details.message(), "body"));
    if ((pickup || shipment.pickupZone() != null) && (shipment.pickupZone() == null || draft.pickupEur() == null))
      throw new WaveWorker.Conflict(REVIEW + " Confirm the pickup price.");
    if (draft.pickupWaitingDisclosure() != null)
      throw new WaveWorker.Conflict(REVIEW + " Confirm whether pickup waiting charges apply.");
    var total = draft.offer().totalEur();
    var extras = (draft.pickupEur() == null ? BigDecimal.ZERO : draft.pickupEur())
        .add(insurance == null ? BigDecimal.ZERO : insurance.premiumEur());
    if (draft.airEur() == null || draft.airEur().signum() <= 0
        || draft.airEur().add(extras).compareTo(total) != 0
        || !customerText.contains("EUR " + total.toPlainString()))
      throw new WaveWorker.Conflict(REVIEW + " Confirm the air freight and customer total.");
  }

  static boolean conditionalCarrier(String body) {
    return SCHEDULE.matcher(body).find() || CONDITIONAL_CARRIER.matcher(body).find();
  }

  static boolean pickupExtras(String body) {
    return PICKUP_EXTRAS.matcher(body).find() || MailParser.pickupWaitingDisclosure(body) != null;
  }

  static boolean changesQuote(String body) {
    String text = MailParser.authoredText(body);
    return MailParser.requestsRequote(text) || MATERIAL_CHANGE.matcher(text).find()
        || CargoInsurance.changesTerms(text);
  }

  static boolean shippingTermsChanged(String body) {
    return SHIPPING_DETAILS.matcher(MailParser.authoredText(body)).find();
  }

  private static Pattern pattern(String expression) {
    return Pattern.compile(expression, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
  }
}
