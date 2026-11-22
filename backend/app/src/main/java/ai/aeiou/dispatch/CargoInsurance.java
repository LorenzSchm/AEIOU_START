package ai.aeiou.dispatch;

import ai.aeiou.freight.MailParser;
import ai.aeiou.freight.Offer;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.regex.Pattern;

record CargoInsurance(BigDecimal goodsValueEur, BigDecimal insuredPercent, BigDecimal premiumPercent,
    BigDecimal minimumPremiumEur, BigDecimal policyFeeEur) {
  static final String REVIEW = "Confirm the EUR goods value, insured percentage and cargo insurance premium rate.";
  static final String BOOKING_REVIEW = "Confirm cargo insurance placement before booking.";
  private static final String CURRENCY = "(?:EUR(?!\\p{L})|€)";
  private static final String AMOUNT = "(\\d+(?:(?:[.,]|\\h)\\d+)*)(?!\\d|[.,]\\d|\\h+\\d)";
  private static final String PERCENT = "(\\d+(?:[.,]\\d+)?)\\h*%";
  private static final String EUR = "(?:" + CURRENCY + "\\h*" + AMOUNT + "(?!\\p{L})|"
      + AMOUNT + "\\h*" + CURRENCY + ")";
  private static final String VALUE_SEPARATOR = "\\h*(?:(?:of|is)\\h+|[:=]\\h*)?";
  private static final String MINIMUM_LABEL =
      "\\b(?:min(?:imum)?\\.?\\h+(?:insurance\\h+)?premium|premium minimum|minimum insurance charge)\\b";
  private static final String FEE_LABEL = "\\b(?:(?<!minimum )(?:insurance|policy)(?: (?:administration|admin|handling|issuance))?"
      + " (?:fee|charge)|fixed(?: (?:administration|admin|issuance))? fee)\\b";
  private static final Pattern MINIMUM_MENTION = pattern(MINIMUM_LABEL);
  private static final Pattern FEE_MENTION = pattern(FEE_LABEL);
  private static final Pattern MINIMUM = pattern(MINIMUM_LABEL + VALUE_SEPARATOR + EUR);
  private static final Pattern FEE = pattern(FEE_LABEL + VALUE_SEPARATOR + EUR);
  private static final Pattern CONDITIONAL = pattern(
      "\\b(?:insurance|coverage|premium|policy)\\b[^!?\\r\\n]{0,110}\\b(?:subject to|deductible|"
          + "excess|tiered|maximum premium|may apply|to be confirmed)\\b");
  private static final Pattern REQUIRED = pattern(
      "\\b(?:(?:cargo|freight|transport) insurance|insurance (?:premium|rate|cover(?:age)?)|"
          + "insured (?:value|at|for)|(?:include|provide|add|quote|arrange|need|require) (?:the )?insurance)\\b"
          + "|\\binsurance\\h*:");
  private static final Pattern EXCLUDED = pattern(
      "\\b(?:no (?:need for )?|without |exclude |do not include |don['’]t include )"
          + "(?:(?:cargo|freight|transport) )?insurance\\b"
          + "|\\b(?:(?:cargo|freight|transport) )?insurance\\h*:?\\h*(?:is )?not (?:required|needed)\\b");
  private static final Pattern VALUE = pattern(
      "\\b(?:goods value|value of (?:the )?goods|(?:the )?goods (?:are|is) worth)"
          + "\\h*(?:(?:has changed to|is now|now|of|is)\\h+|[:=]\\h*)?" + EUR);
  private static final Pattern COVERAGE = pattern(
      "\\b(?:insured\\h+(?:at|for)\\h+|(?:(?:insured|insurance) (?:value|amount|coverage)|cover(?:age)?)\\h*"
          + "(?:(?:of|is|at)\\h+|[:=]\\h*)?)" + PERCENT);
  private static final Pattern PREMIUM = pattern(
      "\\b(?:premium(?: rate)?|insurance rate)" + VALUE_SEPARATOR + PERCENT);

  static boolean mentioned(String text) {
    return REQUIRED.matcher(MailParser.authoredText(text)).find();
  }

  static CargoInsurance from(Map<String, Object> message) {
    String body = Ledger.str(message, "body");
    return from(MailParser.currentSubject(Ledger.str(message, "subject"), body) + "\n" + body);
  }

  static CargoInsurance from(String text) {
    text = MailParser.authoredText(text);
    var positive = EXCLUDED.matcher(text).replaceAll("");
    if (!REQUIRED.matcher(positive).find()) return null;
    var value = unique(VALUE, text, true);
    var coverage = unique(COVERAGE, text, false);
    var premium = unique(PREMIUM, text, false);
    var minimum = optional(MINIMUM_MENTION, MINIMUM, text);
    var fee = optional(FEE_MENTION, FEE, text);
    if (!text.equals(positive) || value == null || value.signum() == 0 || coverage == null
        || coverage.signum() == 0 || premium == null || minimum == null || fee == null
        || CONDITIONAL.matcher(text).find()
        || coverage.compareTo(BigDecimal.valueOf(1000)) > 0 || premium.compareTo(BigDecimal.valueOf(100)) > 0)
      throw new WaveWorker.Conflict(REVIEW);
    return new CargoInsurance(value, coverage, premium, minimum, fee);
  }

  BigDecimal insuredValue() {
    return goodsValueEur.multiply(insuredPercent).movePointLeft(2);
  }

  BigDecimal premiumEur() {
    return insuredValue().multiply(premiumPercent).movePointLeft(2)
        .max(minimumPremiumEur == null ? BigDecimal.ZERO : minimumPremiumEur)
        .add(policyFeeEur == null ? BigDecimal.ZERO : policyFeeEur).setScale(2, RoundingMode.HALF_UP);
  }

  Offer addTo(Offer offer) {
    return new Offer(offer.airline(), offer.days(), offer.totalEur().add(premiumEur()), offer.billedKg(),
        offer.source(), offer.rateId(), offer.validUntil());
  }

  String disclosure(CustomerLanguage language) {
    if ((minimumPremiumEur != null && minimumPremiumEur.signum() > 0)
        || (policyFeeEur != null && policyFeeEur.signum() > 0))
      return language.text(
          "This includes EUR %s for cargo insurance under the agreed premium terms.",
          "Ce prix inclut EUR %s d’assurance transport selon les conditions convenues.",
          "Der Preis enthält EUR %s Transportversicherung zu den vereinbarten Bedingungen.",
          "其中包括按约定条款计算的货物保险费 EUR %s。").formatted(premiumEur().toPlainString());
    return language.text(
        "This includes a cargo insurance premium of EUR %s, at %s%% on an insured value of EUR %s.",
        "Ce prix inclut une prime d’assurance transport de EUR %s, à %s %% sur une valeur assurée de EUR %s.",
        "Der Preis beinhaltet eine Transportversicherungsprämie von EUR %s zu %s %% auf EUR %s Versicherungswert.",
        "其中包括货物保险费 EUR %s，费率为 %s%%，保险金额为 EUR %s。")
        .formatted(premiumEur().toPlainString(), premiumPercent.toPlainString(),
            insuredValue().setScale(2, RoundingMode.HALF_UP).toPlainString());
  }

  static boolean hasGoodsValue(String text) {
    return VALUE.matcher(text).find();
  }

  static boolean hasUnallocatedTerms(String text) {
    if (hasGoodsValue(text)) return true;
    for (String clause : text.split("\\R+|(?<=[.!?])\\h+")) {
      if ((MINIMUM_MENTION.matcher(clause).find() || FEE_MENTION.matcher(clause).find())
          && !pattern("\\b(?:per shipment|for each shipment)\\b").matcher(clause).find()) return true;
    }
    return false;
  }

  static boolean changesTerms(String text) {
    return mentioned(text) || hasGoodsValue(MailParser.authoredText(text))
        || pattern("\\b(?:declared|goods|insured) value\\b").matcher(MailParser.authoredText(text)).find();
  }

  static CargoInsurance revised(CargoInsurance prior, String reply) {
    String text = MailParser.authoredText(reply);
    if (!changesTerms(text)) return prior;
    if (prior != null && pattern("\\b(?:everything else (?:stays|remains) the same|"
        + "(?:the )?(?:same|unchanged) insurance terms|insurance terms (?:are|remain) unchanged)\\b")
        .matcher(text).find() && !mentioned(text) && hasGoodsValue(text)) {
      var value = unique(VALUE, text, true);
      if (value != null && value.signum() > 0)
        return new CargoInsurance(value, prior.insuredPercent, prior.premiumPercent,
            prior.minimumPremiumEur, prior.policyFeeEur);
    }
    var changed = from("Cargo insurance\n" + text);
    if (changed == null) throw new WaveWorker.Conflict(REVIEW);
    return changed;
  }

  private static BigDecimal optional(Pattern marker, Pattern amount, String text) {
    return marker.matcher(text).find() ? unique(amount, text, true) : BigDecimal.ZERO;
  }

  private static BigDecimal unique(Pattern pattern, String text, boolean amount) {
    var values = pattern.matcher(text).results().map(match -> {
      String number = match.group(1);
      if (number == null) number = match.group(2);
      if (amount) {
        if (number.matches("\\d{1,3}(?:\\h\\d{3})+(?:[.,]\\d{1,2})?")) number = number.replaceAll("\\h", "");
        if (number.matches("\\d{1,3}(?:,\\d{3})+(?:\\.\\d{1,2})?")) number = number.replace(",", "");
        else if (number.matches("\\d{1,3}(?:\\.\\d{3})+(?:,\\d{1,2})?")) number = number.replace(".", "");
        else if (!number.matches("\\d+(?:[.,]\\d{1,2})?")) return null;
      }
      if (number.length() > 18) return null;
      return new BigDecimal(number.replace(',', '.')).stripTrailingZeros();
    }).distinct().toList();
    return values.size() == 1 ? values.getFirst() : null;
  }

  private static Pattern pattern(String expression) {
    return Pattern.compile(expression, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
  }
}
