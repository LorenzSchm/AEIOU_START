package ai.aeiou.freight;

import java.math.BigDecimal;
import java.time.*;
import java.time.format.*;
import java.util.*;
import java.util.regex.*;

public final class MailParser {
  private static final String NUM = "(?<![\\w.,-])(\\d+(?:[.,]\\d+)*)(?![.,]\\d)";
  // Korean particles attach directly to Latin units, as in “cubic feet입니다”.
  private static final String UNIT_END = "(?![\\p{L}\\p{N}_&&[^\\p{IsHangul}]])";
  private static final String METRIC_WEIGHT = "kgs?|kilograms?";
  private static final String IMPERIAL_WEIGHT = "lbs?\\.?|pounds?";
  private static final String METRIC_VOLUME = "cbm|m³|m3|cubic met(?:er|re)s?";
  private static final String IMPERIAL_VOLUME = "cubic (?:feet|foot)|cu\\.?\\h+ft\\.?|ft(?:³|\\^?3)";
  private static final Pattern WEIGHT_QUANTITY = quantityPattern(METRIC_WEIGHT, IMPERIAL_WEIGHT);
  private static final Pattern VOLUME_QUANTITY = quantityPattern(METRIC_VOLUME, IMPERIAL_VOLUME);
  private static final BigDecimal KG_PER_POUND = new BigDecimal("0.45359237");
  private static final BigDecimal CBM_PER_CUBIC_FOOT = new BigDecimal("0.028316846592");
  public static final String ARRIVAL_DATES_ISSUE = "Confirm the cargo ready date and latest arrival date.";
  private static final String ISO_DATE = "\\d{4}-\\d{2}-\\d{2}(?![\\p{L}\\d])";
  private static final Pattern READY_DATE = pattern(
      "\\b(?:ready(?:\\h+for\\h+(?:pickup|collection|drop[ -]?off))?|available)\\h+(?:(?:on|from)\\h+)?("
          + ISO_DATE + ")");
  private static final Pattern READY_AT_LOCATION_DATE = pattern(
      "\\bready for (?:pickup|collection) at\\h+[^.!?;\\r\\n]{1,120}?\\bon\\h+(" + ISO_DATE + ")");
  private static final Pattern GERMAN_READY_DATE = pattern(
      "\\b(?:steht|stehen|ist|sind)\\h+(?:am|ab)\\h+(" + ISO_DATE + ")\\h+(?:zur Abholung\\h+)?(?:abhol)?bereit\\b");
  private static final Pattern TURKISH_READY_DATE = pattern(
      "\\b(" + ISO_DATE + ")\\h+tarihinde\\h+(?:teslime\\h+)?haz[ıi]r\\b");
  private static final Pattern KOREAN_PICKUP_READY_DATE = pattern(
      "픽업(?:은|이)?\\h+(\\d{4}-\\d{2}-\\d{2})(?:부터|에)\\h*가능");
  private static final Pattern KOREAN_READY_AT_LOCATION_DATE = pattern(
      "(\\d{4}-\\d{2}-\\d{2})(?:부터|에)\\h+[^.!?;\\r\\n]{0,120}?픽업(?:은|이)?\\h*가능");
  private static final Pattern REQUOTE_REQUEST = pattern(
      "\\b(?:update|revise|reprice) (?:your |the |our |this )?(?:offer|quote)\\b"
          + "|\\b(?:send|provide|issue) (?:us |me )?(?:an? )?(?:updated|revised|new) (?:offer|quote)\\b");
  private static final Pattern OTHER_REVISION = pattern(
      "\\b(?:new|different|changed|updated|revised|corrected) (?:destination|origin|airport|postcode|address|"
          + "delivery date|ready date|pickup date|deadline|departure|route|service|cargo type)\\b"
          + "|\\b(?:arrange|cancel|change|switch) (?:the |our |to )?(?:pickup|collection|destination|service)\\b"
          + "|\\b(?:dangerous goods|hazardous|lithium|dry ice|refrigerated|temperature.controlled)\\b");
  private static final Pattern ARRIVAL_DEADLINE = pattern(
      "\\b(?:arriv(?:e|es|al)|reach|delivered|delivery)\\b[^.!?;\\r\\n]{0,65}?\\b"
          + "(?:no later than|on or before|by(?=\\h+(?:\\d|today\\b|tomorrow\\b|next\\b|"
          + "(?:mon|tue|wed|thu|fri|sat|sun|jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)\\p{L}*\\b)))"
          + "\\h+(" + ISO_DATE + ")?");
  private static final Pattern GERMAN_ARRIVAL_DEADLINE = pattern(
      "\\b(?:muss|müssen|soll|sollen|Ankunft|Zustellung)\\b[^.!?;\\r\\n]{0,60}?\\bsp[aä]testens\\h+"
          + "(?:(?:am|bis)\\h+)?(" + ISO_DATE + ")?"
          + "(?=[^.!?;\\r\\n]{0,95}\\b(?:ankommen|eintreffen|erreichen|angeliefert|zugestellt)\\b)");
  private static final Pattern DEADLINE_TIME =
      pattern("^\\h+(?:(?:at|um)\\h+)?\\d{1,2}(?::\\d{2}|\\h*(?:am|pm|Uhr)\\b)");

  private static final Pattern STREET_POSTCODE =
      Pattern.compile(
          "(?iu:(?:\\b(?:PLZ|post\\s*code|postal code|zip(?: code)?)|邮编|郵編|郵遞區號)\\s*"
              + "(?:is\\s+|ist\\s+|[:：#-]\\s*)?)([0-9]{5})(?![0-9])"
              + "|(?<![\\p{L}\\d.,])(?:D-)?([0-9]{5})\\h+\\(?(?=\\p{Lu}\\p{Ll})"
              + "|(?:픽업(?:지)?(?:의)?\\h*(?:주소\\h*)?(?:(?:5|다섯)\\h*자리\\h*)?우편번호(?:는|가)?\\h*[:：]?\\h*"
              + "|(?iu:Germany|Deutschland|독일)\\h+(?:[\\p{L}.-]+\\h+){0,3}(?=[0-9]{5}(?:\\h+소재|에\\h+있는)))"
              + "([0-9]{5})(?![0-9])");

  public static Optional<String> pickupPostcode(String text, String zone) {
    var candidates =
        STREET_POSTCODE
            .matcher(text)
            .results()
            .map(m -> m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2) : m.group(3))
            .filter(code -> code.startsWith(zone))
            .distinct()
            .toList();
    return candidates.size() == 1 ? Optional.of(candidates.getFirst()) : Optional.empty();
  }

  /** Every distinct five-digit pickup postcode written in the text, in order. */
  public static List<String> pickupPostcodes(String text) {
    return STREET_POSTCODE
        .matcher(text)
        .results()
        .map(m -> m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2) : m.group(3))
        .distinct()
        .toList();
  }

  public static String authoredText(String body) {
    return body.split(
        "(?im)^\\h*(?:>|On .+ wrote:|Am .+ schrieb|Le .+ a [ée]crit\\h*:|"
            + "[- ]{2,}(?:(?:Original|Forwarded) Message|Message (?:d['’]origine|transf[ée]r[ée])|"
            + "Urspr[üu]ngliche Nachricht)[- ]{2,})",
        2)[0].strip();
  }

  public static String currentSubject(String subject, String body) {
    if (has(subject, "^\\s*(?:re:\\s*)*(?:fw|fwd|wg):")
        && !authoredText(body).equals(body.strip())) return "Air freight RFQ";
    return subject;
  }

  public static boolean selfDelivery(String body) {
    return has(body, "\\bwe(?: will|'ll|’ll) deliver[^.!?\\n]{0,100}\\bourselves\\b|"
        + "\\bwe(?: will|'ll|’ll) (?:deliver|bring|drop off|truck)[^.!?\\n]{0,80}\\b(?:directly )?to (?:the )?"
        + "(?:[\\p{L}.'’ -]{0,40}\\b(?:airport|Flughafen)\\b|airport\\b)|"
        + "\\bnous (?:la |le |les )?(?:d[ée]poserons|livrerons|apporterons)[^.!?\\n]{0,100}\\bnous-mêmes\\b|"
        + "\\bwir (?:liefern|bringen)[^.!?\\n]{0,100}\\bselbst\\b|"
        + "\\b(?:biz|kendimiz)\\h+teslim\\h+edece[ğg]iz\\b|"
        + "(?:저희가|당사가|저희\\h*쪽에서)\\h*직접[^.!?\\n]{0,40}(?:반입|입고|운송|전달|가져가)|"
        + "(?:我们|我司)(?:将|会)?(?:自行|亲自)(?:送|运|交)|(?:弊社|当社)(?:で|が)[^。！？\\n]{0,40}持(?:ち)?込");
  }

  public static boolean requestsPickup(String subject, String body) {
    String text = (subject + "\n" + body).replaceAll(
        "(?iu)\\b(?:(?:no need to arrange|no need for) (?:a |the )?(?:truck )?(?:pick[ -]?up|collection)"
            + "|no (?:warehouse |truck |site |door )?(?:pick[ -]?up|collection)(?: service)?"
            + "(?: (?:is|will be) (?:needed|required|necessary))?"
            + "|(?:pick[ -]?up|collection) (?:is|will be) not (?:needed|required|necessary)"
            + "|(?:do|does|will) not (?:need|require) (?:a |any |the )?(?:truck |warehouse )?(?:pick[ -]?up|collection)"
            + "|(?:don['’]t|doesn['’]t|won['’]t) (?:need|require) (?:a |any |the )?(?:truck |warehouse )?(?:pick[ -]?up|collection)"
            + "|without (?:a |any )?(?:truck |warehouse )?(?:pick[ -]?up|collection)"
            + "|(?:pas besoin d['’]|aucun )enl[èe]vement|keine? (?:abholung|vorlauf))\\b", "");
    if (selfDelivery(body)) text = text.replaceAll("(?iu)\\bready for pick[ -]?up\\b", "");
    return has(text, "pick[ -]?up|collect(?:ion)?\\b|truck(?:ing| pickup)? from|arrange (?:the |a )?truck|door["
        + " -]to|abhol|vorlauf|enl[èe]vement|collecte|上门提货|上門提貨|安排提货|安排提貨|"
        + "픽업|ex[ -]?works|\\bEXW\\b");
  }

  private static Pattern pattern(String s) {
    return Pattern.compile(s, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
  }

  private static boolean has(String s, String regex) {
    return pattern(regex).matcher(s).find();
  }

  private static List<String> captures(String text, String regex) {
    return pattern(regex).matcher(text).results().map(m -> m.group(1)).toList();
  }

  private static BigDecimal number(String raw) {
    if (raw == null) return null;
    if (raw.matches("[1-9]\\d*[.,]\\d{3}")) return null;
    if (raw.matches("\\d{1,3}(,\\d{3})+\\.\\d{1,2}")) raw = raw.replace(",", "");
    if (raw.matches("\\d{1,3}(\\.\\d{3})+,\\d{1,2}")) raw = raw.replace(".", "");
    try {
      return new BigDecimal(raw.replace(',', '.'));
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  private static String only(Collection<String> values) {
    var unique = new HashSet<>(values);
    return unique.size() == 1 ? unique.iterator().next() : null;
  }

  private static Pattern quantityPattern(String metric, String imperial) {
    return pattern(NUM + "\\s*(?:(?:" + metric + ")|(" + imperial + "))" + UNIT_END);
  }

  private static BigDecimal quantity(String text, Pattern pattern, BigDecimal imperialFactor) {
    var values = pattern.matcher(text).results().map(match -> {
      var value = number(match.group(1));
      return value == null || match.group(2) == null ? value : value.multiply(imperialFactor).stripTrailingZeros();
    }).toList();
    if (values.isEmpty() || values.getFirst() == null) return null;
    BigDecimal first = values.getFirst();
    return values.stream().allMatch(value -> value != null && first.compareTo(value) == 0) ? first : null;
  }

  public static boolean hasImperialWeight(String body) {
    return WEIGHT_QUANTITY.matcher(authoredText(body)).results().anyMatch(match -> match.group(2) != null);
  }

  public static boolean hasImperialVolume(String body) {
    return VOLUME_QUANTITY.matcher(authoredText(body)).results().anyMatch(match -> match.group(2) != null);
  }

  private static boolean perPieceQuantity(String text, String units) {
    String unit = "(?:" + units + ")" + UNIT_END;
    return has(text, "\\b\\d+\\s*[x×]\\s*" + NUM + "\\s*" + unit
        + "|" + NUM + "\\s*" + unit + "\\s*(?:(?:each|per (?:piece|pallet|crate))\\b|씩|당)"
        + "|\\b(?:each|per piece)\\s*[:=]?\\s*" + NUM + "\\s*" + unit);
  }

  private static BigDecimal dimensions(String text) {
    var dims =
        pattern(
                "(\\d+(?:[.,]\\d+)?)\\s*[x×]\\s*(\\d+(?:[.,]\\d+)?)\\s*[x×]\\s*(\\d+(?:[.,]\\d+)?)\\s*(cm|mm|m)\\b")
            .matcher(text)
            .results()
            .toList();
    var counts =
        captures(
            text,
            "\\b(\\d+)\\s*(?:pallets?|pieces?|pcs|crates?|cartons?|boxes|box|colli|paletten|kisten)\\b");
    if (dims.size() != 1 || counts.size() != 1) return null;
    var match = dims.getFirst();
    int scale =
        switch (match.group(4).toLowerCase(Locale.ROOT)) {
          case "mm" -> 9;
          case "cm" -> 6;
          default -> 0;
        };
    var total = new BigDecimal(counts.getFirst());
    for (int i = 1; i <= 3; i++) {
      var n = number(match.group(i));
      if (n == null) return null;
      total = total.multiply(n);
    }
    return total.movePointLeft(scale);
  }

  private static final Map<String, String> CITIES =
      Map.ofEntries(
          Map.entry("Frankfurt", "FRA"),
          Map.entry("Munich|München", "MUC"),
          Map.entry("Stuttgart", "STR"),
          Map.entry("Hamburg", "HAM"),
          Map.entry("Düsseldorf|Duesseldorf", "DUS"),
          Map.entry("Cologne|Köln", "CGN"),
          Map.entry("Los Angeles", "LAX"),
          Map.entry("Chicago", "ORD"),
          Map.entry("Miami", "MIA"),
          Map.entry("Toronto", "YYZ"),
          Map.entry("Dallas", "DFW"),
          Map.entry("Hong Kong", "HKG"),
          Map.entry("Singapore", "SIN"),
          Map.entry("Nairobi", "NBO"),
          Map.entry("Johannesburg", "JNB"),
          Map.entry("Lima", "LIM"),
          Map.entry("Bangkok", "BKK"),
          Map.entry("Dubai", "DXB"),
          Map.entry("Sydney", "SYD"),
          Map.entry("Melbourne", "MEL"),
          Map.entry("Mexico City", "MEX"));

  private static List<String> cityCodes(String text, Collection<String> valid) {
    return CITIES.entrySet().stream()
        .filter(e -> valid.contains(e.getValue()) && has(text, "\\b(?:" + e.getKey() + ")\\b"))
        .map(Map.Entry::getValue)
        .distinct()
        .toList();
  }

  public record LotTotals(BigDecimal kg, BigDecimal cbm) {}

  // Weight and volume have independent scopes: "together" is already a line total.
  private static final String LOT_NUMBER = "(\\d+(?:[.,]\\d+)?)";
  private static final Pattern PACKED_LOT =
      pattern(
          "Lot \\d+:\\h*([1-9]\\d*)\\h+(?:drums?|cartons?|cases?|pieces?|pallets?|crates?|boxes)\\h*,\\h*"
              + "(each|together)\\h+"
              + LOT_NUMBER
              + "\\h*kg\\h+gross(?:\\h*\\(\\d+(?:[.,]\\d+)?\\h*kg\\h+net\\))?\\h*,\\h*"
              + "(each|together)\\h+"
              + LOT_NUMBER
              + "(?:\\h*(cbm|m3|m³)|\\h*[x×]\\h*"
              + LOT_NUMBER
              + "\\h*[x×]\\h*"
              + LOT_NUMBER
              + "\\h*(cm|mm|m))\\h*[.]?");

  private static LotTotals lotTotals(String line) {
    var packed = PACKED_LOT.matcher(line.strip());
    if (packed.matches()) {
      var count = new BigDecimal(packed.group(1));
      var kg = number(packed.group(3));
      var cbm = number(packed.group(5));
      if (kg == null || cbm == null) return null;
      if (packed.group(6) == null) {
        var width = number(packed.group(7));
        var height = number(packed.group(8));
        if (width == null || height == null) return null;
        int scale =
            switch (packed.group(9).toLowerCase(Locale.ROOT)) {
              case "cm" -> 6;
              case "mm" -> 9;
              default -> 0;
            };
        cbm = cbm.multiply(width).multiply(height).movePointLeft(scale);
      }
      return new LotTotals(
          packed.group(2).equalsIgnoreCase("each") ? kg.multiply(count) : kg,
          packed.group(4).equalsIgnoreCase("each") ? cbm.multiply(count) : cbm);
    }
    if (!has(line, "\\b(?:each|per|net)\\b") && !has(line, EACH_TERM) && !has(line, NET_TERM)) {
      var kg = quantity(line, WEIGHT_QUANTITY, KG_PER_POUND);
      var cbm = quantity(line, VOLUME_QUANTITY, CBM_PER_CUBIC_FOOT);
      if (kg != null && cbm != null) return new LotTotals(kg, cbm);
    }
    return scopedLot(line);
  }

  // Multilingual lot lines: per-piece ("each") figures are multiplied by the piece count, lot totals
  // are taken as given, net weights are ignored and volume falls back to dimensions times pieces.
  private static final String LOT_LABEL =
      "(?:(?:Lot|Lote|Los|Partie|Posten|Teillieferung|로트|ロット|批次)\\h*(?:Nr\\.?|No\\.?|#)?\\h*\\d+"
          + "|第\\h*\\d+\\h*(?:批|组|組))\\h*[:：]";
  private static final Pattern LOT_LINE = pattern("(?m)^\\h*(?:[-*•]\\h*)?" + LOT_LABEL + "[^\\r\\n]*");
  private static final String CONSOLIDATED =
      "together as (?:one|a single) consignment|under (?:one|a single) air ?waybill"
          + "|\\b(?:one|a single|the same) (?:air ?waybill|AWB)\\b|\\bship(?:ped|ping)? (?:all lots )?together\\b"
          + "|\\b(?:einem|einen|demselben|gemeinsamen) (?:Luftfrachtbrief|AWB)\\b|\\bals eine Sendung\\b"
          + "|\\b(?:une seule|la même|même) (?:LTA|lettre de transport)|\\ben un seul envoi\\b"
          + "|항공화물운송장\\h*한\\h*장|운송장\\h*한\\h*장|나누지\\h*않"
          + "|一(?:张|份|票)(?:空运)?(?:提单|运单)|同一(?:张|份|票)?(?:空运)?(?:提单|运单)|不(?:拆分|分开)"
          + "|一(?:枚|通)の(?:AWB|航空貨物運送状|運送状)|同一の?AWB|分けずに"
          + "|\\btek (?:bir )?(?:konşimento|AWB)\\b";
  private static final String NET_TERM =
      "\\bnet(?:to)?\\b|\\bnett\\b|\\bN\\.?W\\.?(?!\\p{L})|순중량|净重|淨重|正味";
  private static final String EACH_TERM =
      "\\b(?:each|apiece|per (?:piece|pc|unit|item|package|carton|case|drum|pallet|crate|box|bag)|je(?:weils)?"
          + "|pro (?:Stück|Stk|Karton|Kiste|Palette|Fass|Einheit|Colli)|chacun(?:e)?|par (?:pièce|colis|carton"
          + "|caisse|fût|palette|unité)|cada (?:uno|una|pieza|bulto)|her biri|adet başına)(?!\\p{L})"
          + "|/\\h*(?:pc|pcs|piece|unit|ea|carton|pallet|Stk|Stück)\\b|每(?:件|箱|个|個|桶|托|只)|单件|單件|各|각|씩"
          + "|개당|あたり|当たり";
  private static final String TOTAL_TERM =
      "\\b(?:total|totals|together|combined|in total|altogether|gesamt|insgesamt|zusammen|au total|ensemble"
          + "|cumulée?s?|totale?s?|toplam)\\b|합산|합계|총합|合计|合計|总计|總計|共计|共計|合わせて";
  private static final String LOT_PIECES =
      "(?<![\\d.,])(\\d+)\\h*(?:(?:pieces?|pcs?\\.?|pkgs?|packages?|units?|cartons?|cases?|drums?|pallets?|crates?"
          + "|boxes|box|bags?|bundles?|rolls?|colli|coils?|kartons?|kisten?|fässer|fass|paletten?|stück|stk\\.?"
          + "|caisses?|fûts?|colis|palettes?|bidons?|cajas?|bultos?|piezas?|tambores?|parcels?|skids?|koli|adet"
          + "|varil|sandık)(?!\\p{L})|개|個|个|件|箱|只|桶|托|ケース|カートン)";
  private static final Pattern LOT_WEIGHT = pattern(
      "(?<![\\d.,])(\\d+(?:[.,]\\d+)?)\\h*(?:(kgs?|kilo(?:gram(?:me)?s?)?|公斤|千克|킬로그램|킬로|キロ(?:グラム)?)"
          + "|(lbs?\\.?|pounds?))(?![A-Za-z])");
  private static final Pattern LOT_VOLUME = pattern(
      "(?<![\\d.,])(\\d+(?:[.,]\\d+)?)\\h*(?:(cbm|m³|m3|㎥|cubic met(?:er|re)s?|立方米|立方|루베)"
          + "|(cubic (?:feet|foot)|cu\\.?\\h*ft\\.?|ft(?:³|\\^?3)))(?![A-Za-z])");
  private static final Pattern LOT_DIMENSIONS = pattern(
      "(?<![\\d.,])(\\d+(?:[.,]\\d+)?)\\h*(?:cm)?\\h*[x×*]\\h*(\\d+(?:[.,]\\d+)?)\\h*(?:cm)?\\h*[x×*]\\h*"
          + "(\\d+(?:[.,]\\d+)?)\\h*(cm|mm|m|in(?:ch(?:es)?)?|\"|厘米|公分|毫米|センチ|㎝)(?![A-Za-z])");

  private static BigDecimal dimensionScale(String unit) {
    return switch (unit.toLowerCase(Locale.ROOT)) {
      case "mm", "毫米" -> new BigDecimal("1E-9");
      case "m" -> BigDecimal.ONE;
      case "in", "inch", "inches", "\"" -> new BigDecimal("0.000016387064");
      default -> new BigDecimal("1E-6");
    };
  }

  private static LotTotals scopedLot(String line) {
    String body = pattern("^\\h*(?:[-*•]\\h*)?" + LOT_LABEL).matcher(line).replaceFirst(" ");
    body = pattern("[(（][^)）]*[)）]").matcher(body)
        .replaceAll(m -> has(m.group(), NET_TERM) ? " " : Matcher.quoteReplacement(m.group()));
    var pieces = new HashSet<>(captures(body, LOT_PIECES));
    BigDecimal count = pieces.size() == 1 ? new BigDecimal(pieces.iterator().next()) : null;
    // A figure without a scope next to per-piece figures is ambiguous unless the lot is a single piece.
    boolean unscopedAmbiguous = has(body, EACH_TERM) && (count == null || count.compareTo(BigDecimal.ONE) != 0);
    var kgs = new ArrayList<BigDecimal>();
    var cbms = new ArrayList<BigDecimal>();
    var dims = new ArrayList<BigDecimal>();
    for (String segment : body.split("(?<!\\d),|,(?!\\d)|;|，|；|、|\\h/\\h|\\h[-–—]\\h")) {
      if (has(segment, NET_TERM)) continue;
      boolean each = has(segment, EACH_TERM);
      boolean total = !each && has(segment, TOTAL_TERM);
      if (each && count == null) return null;
      for (var m : LOT_WEIGHT.matcher(segment).results().toList()) {
        var kg = number(m.group(1));
        if (kg == null || (!each && !total && unscopedAmbiguous)) return null;
        if (m.group(3) != null) kg = kg.multiply(KG_PER_POUND);
        kgs.add(each ? kg.multiply(count) : kg);
      }
      for (var m : LOT_VOLUME.matcher(segment).results().toList()) {
        var cbm = number(m.group(1));
        if (cbm == null || (!each && !total && unscopedAmbiguous)) return null;
        if (m.group(3) != null) cbm = cbm.multiply(CBM_PER_CUBIC_FOOT);
        cbms.add(each ? cbm.multiply(count) : cbm);
      }
      for (var m : LOT_DIMENSIONS.matcher(segment).results().toList()) {
        var cbm = dimensionScale(m.group(4));
        for (int i = 1; i <= 3; i++) {
          var n = number(m.group(i));
          if (n == null) return null;
          cbm = cbm.multiply(n);
        }
        // Dimensions describe one piece unless the customer says they are a lot total.
        if (!total) {
          if (count == null) return null;
          cbm = cbm.multiply(count);
        }
        dims.add(cbm);
      }
    }
    var kg = agreed(kgs);
    var cbm = agreed(cbms.isEmpty() ? dims : cbms);
    return kg == null || cbm == null ? null : new LotTotals(kg.stripTrailingZeros(), cbm.stripTrailingZeros());
  }

  private static BigDecimal agreed(List<BigDecimal> values) {
    if (values.isEmpty()) return null;
    var first = values.getFirst();
    return values.stream().allMatch(v -> v.compareTo(first) == 0) ? first : null;
  }

  /** Gross totals of a consolidated multi-lot shipment on one air waybill, or empty if it is not one. */
  public static Optional<LotTotals> consolidatedLots(String body) {
    body = authoredText(body);
    var lots = LOT_LINE.matcher(body).results().map(MatchResult::group).toList();
    if (lots.size() < 2) return Optional.empty();
    var outside = LOT_LINE.matcher(body).replaceAll("");
    if (!has(body, CONSOLIDATED)
        || has(outside, "\\b(?:" + METRIC_WEIGHT + "|" + METRIC_VOLUME + ")")
        || WEIGHT_QUANTITY.matcher(outside).find() || VOLUME_QUANTITY.matcher(outside).find())
      return Optional.empty();
    BigDecimal kg = BigDecimal.ZERO, cbm = BigDecimal.ZERO;
    for (String line : lots) {
      var lot = lotTotals(line);
      if (lot == null || lot.kg().signum() <= 0 || lot.cbm().signum() <= 0) return Optional.empty();
      kg = kg.add(lot.kg());
      cbm = cbm.add(lot.cbm());
    }
    return Optional.of(new LotTotals(kg, cbm));
  }

  public record Parsed(Shipment shipment, String issue) {}

  public record ArrivalWindow(LocalDate readyOn, LocalDate arriveBy) {
    public boolean complete() {
      return readyOn != null && arriveBy != null && !arriveBy.isBefore(readyOn);
    }

    public LocalDate estimatedArrival(LocalDate today, double transitDays) {
      return (readyOn.isBefore(today) ? today : readyOn).plusDays((long) Math.ceil(transitDays));
    }

    public boolean allows(LocalDate today, double transitDays) {
      return complete() && !estimatedArrival(today, transitDays).isAfter(arriveBy);
    }
  }

  public static ArrivalWindow arrivalWindow(String body) {
    String text = authoredText(body);
    var deadlines = java.util.stream.Stream.concat(ARRIVAL_DEADLINE.matcher(text).results(),
        GERMAN_ARRIVAL_DEADLINE.matcher(text).results()).toList();
    if (deadlines.isEmpty()) return null;
    // Published transit is in days. Time-of-day deadlines require a confirmed schedule.
    var latest = deadlines.stream().map(match ->
        DEADLINE_TIME.matcher(text.substring(match.end())).find() ? null : match.group(1)).toList();
    return new ArrivalWindow(cargoReadyDate(text), onlyDate(latest));
  }

  public static LocalDate cargoReadyDate(String body) {
    String text = authoredText(body);
    return onlyDate(java.util.stream.Stream.of(READY_DATE, READY_AT_LOCATION_DATE, GERMAN_READY_DATE,
        TURKISH_READY_DATE, KOREAN_PICKUP_READY_DATE, KOREAN_READY_AT_LOCATION_DATE)
        .flatMap(pattern -> pattern.matcher(text).results()).map(match -> match.group(1)).toList());
  }

  public static boolean requestsRequote(String body) {
    return REQUOTE_REQUEST.matcher(authoredText(body)).find();
  }

  public record ShipmentRevision(Shipment shipment, Integer pieces) {}

  public static Optional<ShipmentRevision> shipmentRevision(Shipment prior, String body) {
    if (!requestsRequote(body) || prior == null) return Optional.empty();
    String text = authoredText(body);
    if (OTHER_REVISION.matcher(text).find() || arrivalWindow(text) != null || cargoReadyDate(text) != null
        || perPieceQuantity(text, METRIC_WEIGHT + "|" + IMPERIAL_WEIGHT)
        || perPieceQuantity(text, METRIC_VOLUME + "|" + IMPERIAL_VOLUME)) return Optional.empty();
    boolean hasWeight = WEIGHT_QUANTITY.matcher(text).find();
    boolean hasVolume = VOLUME_QUANTITY.matcher(text).find();
    if (!hasWeight && !hasVolume) return Optional.empty();
    var weight = hasWeight ? quantity(text, WEIGHT_QUANTITY, KG_PER_POUND) : prior.weightKg();
    var volume = hasVolume ? quantity(text, VOLUME_QUANTITY, CBM_PER_CUBIC_FOOT) : prior.volumeCbm();
    if (weight == null || volume == null) return Optional.empty();
    String pieces = only(captures(text, "\\b([1-9]\\d{0,5})\\h+(?:pieces|pcs|cartons|boxes|pallets)\\b"));
    return Optional.of(new ShipmentRevision(new Shipment(prior.origin(), prior.destination(), weight,
        volume, prior.preference(), prior.pickupZone(), null, 0), pieces == null ? null : Integer.valueOf(pieces)));
  }

  private static LocalDate onlyDate(List<String> dates) {
    String date = only(dates);
    try {
      return date == null ? null : LocalDate.parse(date);
    } catch (DateTimeParseException ex) {
      return null;
    }
  }

  private static final Pattern SEPARATE_SHIPMENTS = pattern(
      "\\b(?:both|two|three|multiple|several|separate|each of (?:these|the)) (?:\\w+ )?shipments\\b"
          + "|\\bboth quotes\\b[^.!?\\n]{0,110}\\b(?:same|one|single) (?:email|reply|message)\\b"
          + "|\\bseparate (?:all[ -]in )?(?:prices?|quotes?)\\b[^.!?\\n]{0,90}\\b(?:each shipment|both shipments)\\b");
  private static final Pattern SHIPMENT_COUNT = pattern("\\b(both|two|three|four|five|six|seven|eight|[2-8])"
      + " (?:\\w+ )?shipments\\b");
  private static final Pattern SHIPMENT_ROW =
      Pattern.compile("(?m)^\\h*(\\d{1,2})[.)]\\h+([^\\r\\n]+)");
  private static final Pattern ROUTE_AT_START = Pattern.compile(
      "^(?:(?i:(?:shipment\\h+)?to)\\h+|(?i:destination(?:\\h+airport)?)\\h*[:–—-]?\\h*)?([A-Z]{3})\\b");
  private static final Pattern PIECES = pattern("\\b(\\d{1,7})\\h+(?:pieces?|pcs)\\b");

  public record QuoteShipment(Shipment shipment, String text, Integer pieces) {}
  public record ShipmentEvidence(String source, String destination, BigDecimal weightKg,
      BigDecimal volumeCbm, Integer pieces, boolean pickupRequired) {}
  public record SeparateShipments(List<QuoteShipment> shipments, String issue, String sharedText) {
    SeparateShipments(List<QuoteShipment> shipments, String issue) {
      this(shipments, issue, "");
    }
  }

  public static SeparateShipments separateShipments(String subject, String body, boolean attachments,
      Catalog catalog) {
    subject = currentSubject(subject, body);
    body = authoredText(body);
    var rows = SHIPMENT_ROW.matcher(body).results().toList();
    String request = subject + "\n" + body;
    boolean requested = SEPARATE_SHIPMENTS.matcher(request).find();
    if (!requested && rows.stream().filter(row -> {
      var route = ROUTE_AT_START.matcher(row.group(2));
      return route.find() && catalog.destinations.contains(route.group(1));
    }).count() < 2) return null;
    if (attachments)
      return new SeparateShipments(List.of(), "Review the packing documents for each shipment.");
    if (!matchesShipmentCount(request, rows.size()))
      return new SeparateShipments(List.of(), "Confirm every separately priced shipment and its cargo details.");

    String shared = body.substring(0, rows.getFirst().start()) + "\n" + body.substring(rows.getLast().end());
    var shipments = new ArrayList<QuoteShipment>();
    for (var row : rows) {
      int index = shipments.size();
      var route = ROUTE_AT_START.matcher(row.group(2));
      if (Integer.parseInt(row.group(1)) != index + 1 || !route.find()
          || !catalog.destinations.contains(route.group(1)))
        return new SeparateShipments(List.of(), "Confirm the destination of each separately priced shipment.");
      if (index > 0 && !body.substring(rows.get(index - 1).end(), row.start()).isBlank())
        return new SeparateShipments(List.of(), "Review the additional instructions for each shipment.");
      String destination = route.group(1);
      String text = shared + "\n" + row.group(2);
      var parsed = request("Air freight quote to " + destination, text, false, catalog);
      if (parsed.issue() != null)
        return new SeparateShipments(List.of(), destination + ". " + parsed.issue());
      var pieces = PIECES.matcher(row.group(2));
      shipments.add(new QuoteShipment(parsed.shipment(), text, pieces.find() ? Integer.valueOf(pieces.group(1)) : null));
    }
    return new SeparateShipments(List.copyOf(shipments), null, shared);
  }

  public static SeparateShipments verifySeparateShipments(String body, List<ShipmentEvidence> evidence,
      Catalog catalog) {
    body = authoredText(body);
    if (!matchesShipmentCount(body, evidence.size())) return null;

    int previousEnd = 0;
    var shared = new StringBuilder();
    for (var entry : evidence) {
      String source = entry.source();
      if (source == null || source.isBlank()) return null;
      int start = body.indexOf(source, previousEnd);
      if (start < 0 || body.indexOf(source) != start || body.indexOf(source, start + 1) >= 0) return null;
      int end = start + source.length();
      int lineStart = body.lastIndexOf('\n', start - 1) + 1;
      int lineEnd = body.indexOf('\n', end);
      if (lineEnd < 0) lineEnd = body.length();
      if (!body.substring(lineStart, start).isBlank() || !body.substring(end, lineEnd).isBlank()) return null;
      shared.append(body, previousEnd, start).append('\n');
      previousEnd = end;
    }
    shared.append(body.substring(previousEnd));
    String sharedText = shared.toString();
    if (WEIGHT_QUANTITY.matcher(sharedText).find() || VOLUME_QUANTITY.matcher(sharedText).find()
        || PIECES.matcher(sharedText).find() || dimensions(sharedText) != null) return null;

    boolean pickupRequested = requestsPickup("", body);
    var ready = cargoReadyDate(body);
    var arrival = arrivalWindow(body);
    var shipments = new ArrayList<QuoteShipment>();
    for (var entry : evidence) {
      var row = request("Air freight quote", entry.source(), false, catalog).shipment();
      String text = sharedText + "\n" + entry.source();
      var parsed = request("Air freight quote", text, false, catalog);
      var shipment = parsed.shipment();
      var pieces = PIECES.matcher(entry.source()).results()
          .map(match -> Integer.valueOf(match.group(1))).distinct().toList();
      if (parsed.issue() != null || !Objects.equals(row.destination(), entry.destination())
          || !Objects.equals(shipment.destination(), entry.destination())
          || entry.weightKg() == null || row.weightKg() == null || row.weightKg().compareTo(entry.weightKg()) != 0
          || entry.volumeCbm() == null || row.volumeCbm() == null || row.volumeCbm().compareTo(entry.volumeCbm()) != 0
          || shipment.weightKg().compareTo(entry.weightKg()) != 0 || shipment.volumeCbm().compareTo(entry.volumeCbm()) != 0
          || pieces.size() > 1 || !Objects.equals(pieces.isEmpty() ? null : pieces.getFirst(), entry.pieces())
          || entry.pickupRequired() != (shipment.pickupZone() != null)
          || (pickupRequested && !entry.pickupRequired())
          || (entry.pickupRequired() && pickupPostcode(text, shipment.pickupZone()).isEmpty())
          || (ready != null && !ready.equals(cargoReadyDate(text)))
          || (arrival != null && !arrival.equals(arrivalWindow(text)))) return null;
      shipments.add(new QuoteShipment(shipment, text, entry.pieces()));
    }
    return new SeparateShipments(List.copyOf(shipments), null, sharedText);
  }

  private static boolean matchesShipmentCount(String text, int count) {
    return count >= 2 && count <= 8 && SHIPMENT_COUNT.matcher(text).results().allMatch(match -> {
      int stated = switch (match.group(1).toLowerCase(Locale.ROOT)) {
        case "both", "two" -> 2;
        case "three" -> 3;
        case "four" -> 4;
        case "five" -> 5;
        case "six" -> 6;
        case "seven" -> 7;
        case "eight" -> 8;
        default -> Integer.parseInt(match.group(1));
      };
      return count == stated;
    });
  }

  public static Parsed request(String subject, String body, boolean attachments, Catalog catalog) {
    return request(subject, body, attachments, catalog, null);
  }

  public static Parsed request(
      String subject, String body, boolean attachments, Catalog catalog, String confirmedPickupZone) {
    return request(subject, body, attachments, catalog, confirmedPickupZone, null);
  }

  public static Parsed request(String subject, String body, boolean attachments, Catalog catalog,
      String confirmedPickupZone, String customerDeliveryAirport) {
    subject = currentSubject(subject, body);
    body = authoredText(body);
    String text = subject + "\n" + body;
    var codes = captures(text.toUpperCase(Locale.ROOT), "\\b([A-Z]{3})\\b");
    var origins = codes.stream().filter(catalog.zones::containsValue).distinct().toList();
    var destinations = codes.stream().filter(catalog.destinations::contains).distinct().toList();
    if (origins.isEmpty()) origins = cityCodes(text, catalog.zones.values());
    if (destinations.isEmpty()) destinations = cityCodes(text, catalog.destinations);
    boolean selfDelivery = selfDelivery(body);
    boolean pickup = requestsPickup(subject, body);
    var zones =
        new ArrayList<>(
            captures(
                text,
                "\\b(?:zone|PLZ|postal code|post ?code|zip(?:"
                    + " code)?)\\s*[:#-]?\\s*(\\d{2})(?:\\d{3})?\\b"));
    if (pickup) {
      var postcode = pickupPostcode(text, "");
      if (postcode.isPresent()) zones.add(postcode.get().substring(0, 2));
      else if (zones.isEmpty()) zones.addAll(captures(text, "\\b(\\d{2})\\d{3}\\b"));
    }
    String zone = confirmedPickupZone == null ? only(zones) : confirmedPickupZone;
    String origin = origins.isEmpty() ? catalog.zones.get(zone) : only(origins);
    if (customerDeliveryAirport != null) {
      origin = customerDeliveryAirport;
      zone = null;
    }
    String priority = text.replaceAll(
        "(?iu)(?:\\b(?:rather than|instead of|au lieu de|plut[oô]t que|statt|anstelle)\\h+[^.!?\\r\\n]+"
            + "|而不是[^。！？\\r\\n]+|가장\\h*(?:빠른|저렴한)\\h+(?:옵션|서비스)보다(?:는)?)", "");
    boolean fast = has(priority, "fastest|quickest|shortest|schnellst|prioriti[sz]e speed|"
        + "\\b(?:le|la|les) plus rapides?\\b|\\ben h[ıi]zl[ıi]\\b|最快|时效最短|時效最短|가장\\h*빠른");
    boolean cheap =
        has(
            priority,
            "cheapest|lowest|economical|günstig|preiswert|best price|most economical|prioriti[sz]e"
                + " (?:price|cost)|\\b(?:le|la|les) moins ch[èe]r(?:e|s|es)?\\b|"
                + "\\b(?:le|la|les) plus [ée]conomiques?\\b|\\ben (?:ucuz|ekonomik)\\b|最便宜|最低价|最低價|最经济|最經濟"
                + "|가장\\h*저렴한|최저가");
    var arrival = arrivalWindow(body);
    if (arrival != null && has(priority,
        "\\b(?:(?:price|cost) (?:will determine (?:our|the) choice|(?:is|will be) (?:the )?"
            + "(?:deciding|determining) factor)|(?:decide|decision|choice) based on "
            + "(?:the )?(?:(?:all[ -]in|total) )?(?:price|cost))\\b"))
      cheap = true;
    var volume = quantity(body, VOLUME_QUANTITY, CBM_PER_CUBIC_FOOT);
    if (volume == null && !VOLUME_QUANTITY.matcher(body).find() && !has(body, "\\b(?:cbm|m³|m3)\\b"))
      volume = dimensions(body);
    var weight = quantity(body, WEIGHT_QUANTITY, KG_PER_POUND);
    var lots = LOT_LINE.matcher(body).results().toList();
    boolean incompleteLots = false;
    if (lots.size() >= 2) {
      weight = null;
      volume = null;
      incompleteLots = true;
      var consolidated = consolidatedLots(body);
      if (consolidated.isPresent()) {
        weight = consolidated.get().kg();
        volume = consolidated.get().cbm();
        incompleteLots = false;
      }
    }
    if (lots.size() < 2 && perPieceQuantity(body, IMPERIAL_VOLUME)) volume = null;
    var shipment =
        new Shipment(
            origin,
            only(destinations),
            weight,
            volume,
            fast == cheap ? null : fast ? "fastest" : "cheapest",
            zone,
            null,
            0);
    String issue = catalog.problem(shipment);
    if (attachments) issue = "Review the attached shipment specifications.";
    else if (has(
        text,
        "dangerous|hazardous|lithium|temperature.control|dry ice|gefahrgut|perishable|refrigerat"))
      issue = "Special handling needs a confirmed price.";
    else if (customerDeliveryAirport == null && selfDelivery && (pickup || zone != null))
      issue = "Confirm whether collection or customer delivery to the airport is required.";
    else if (customerDeliveryAirport == null && (pickup || !zones.isEmpty()) && zone == null)
      issue = "Pickup requested. Confirm the pickup postcode or zone.";
    else if (incompleteLots) issue = "Confirm gross weight and volume for every lot.";
    else if (lots.size() < 2 && perPieceQuantity(body, METRIC_WEIGHT + "|" + IMPERIAL_WEIGHT))
      issue = "Confirm total gross weight. The request gives a per-piece weight.";
    else if (arrival != null && !arrival.complete()) issue = ARRIVAL_DATES_ISSUE;
    else if (!has(text, FREIGHT_TERMS))
      issue = "Confirm that this message requests a freight quote.";
    return new Parsed(shipment, issue);
  }

  private static final String FREIGHT_TERMS =
      "\\b(?:rfq|quot(?:e|ation)|rat(?:e|es)|pric(?:e|ing)|angebot|anfrage|luftfracht(?:anfrage)?|"
          + "freight|airfreight|versand|devis|fret a[ée]rien|hava navlun|hava kargo)\\b|空运|空運|항공\\h*(?:운송|화물)|견적";
  private static final String CARGO_TERMS =
      "\\b(?:kgs?|kilograms?|cbm|m3|m³|pallets?|paletten|pieces|pcs|crates?|kisten|cartons?|colli|awb"
          + "|air ?waybill|consignment|shipment|sendung|cargo)\\b";
  // Credential requests, secrecy pressure and sales outreach never belong in a customer RFQ.
  private static final String SOLICITATION_TERMS =
      "\\b(?:api[ -]?keys?|gateway keys?|passwor(?:d|t)s?|credentials|log[ -]?in details|gift ?cards?"
          + "|wire transfer|bank details|keep (?:it|this) between us|don'?t (?:loop|tell|cc) anyone"
          + "|book (?:a |\\d+ )?(?:min(?:ute)?s?|call|demo)|demo|schedule a (?:call|meeting)"
          + "|unsubscribe|newsletter|webinar|seo)\\b";

  /**
   * A new message with no shipment facts at all that is either outreach or not about freight.
   * Such mail stays in the inbox for an operator but never opens a case or gets an automatic reply.
   */
  public static boolean unsolicited(String subject, String body, boolean attachments, Shipment parsed) {
    if (attachments) return false;
    if (parsed != null
        && (parsed.origin() != null || parsed.destination() != null || parsed.weightKg() != null
            || parsed.volumeCbm() != null || parsed.pickupZone() != null)) return false;
    subject = currentSubject(subject, body);
    body = authoredText(body);
    String text = subject + "\n" + body;
    if (has(text, CARGO_TERMS) || requestsPickup(subject, body)) return false;
    return has(text, SOLICITATION_TERMS) || !has(text, FREIGHT_TERMS);
  }

  public record Bid(BigDecimal euros, Double days) {}

  public record PackingCorrection(Set<String> destinations, Set<String> weightsKg, Set<String> volumesCbm) {
    public PackingCorrection followedBy(PackingCorrection later) {
      return new PackingCorrection(
          later.destinations.isEmpty() ? destinations : later.destinations,
          later.weightsKg.isEmpty() ? weightsKg : later.weightsKg,
          later.volumesCbm.isEmpty() ? volumesCbm : later.volumesCbm);
    }
  }

  public static Optional<PackingCorrection> confirmedPackingCorrection(String body) {
    String text = authoredText(body);
    String packing = "(?:attached (?:document|packing specification|packing list)|"
        + "packing (?:specification|list)(?: already attached)?)";
    if (!has(text, "(?:^|[.!?]\\s+|\\R)\\h*(?:(?:sorry for the confusion|to clarify):\\h*)?"
        + "(?:(?:the )?" + packing + " is (?:right|correct|accurate)|"
        + "please (?:use|refer to) (?:the )?" + packing + "(?: for the correct totals)?)"
        + "\\h*(?:[.!;]|$|\\R)")) return Optional.empty();
    var destinations = captures(text,
        "\\b(?:correct )?destination(?: airport)?\\h*(?:[:=]\\h*|is\\h+)([A-Z]{3})\\b");
    return Optional.of(new PackingCorrection(
        Set.copyOf(destinations.stream().map(s -> s.toUpperCase(Locale.ROOT)).toList()),
        packingTotals(text, "kg|kgs|kilograms?"),
        packingTotals(text, "cbm|m³|m3|cubic met(?:er|re)s?")));
  }

  private static Set<String> packingTotals(String text, String unit) {
    return Set.copyOf(captures(text, NUM + "\\h*(?:" + unit
        + ")\\b(?!\\h*(?:each|per (?:piece|pallet|item|carton|crate))\\b)"));
  }

  public static BigDecimal packingNumber(String value, BigDecimal documented) {
    var result = number(value);
    if (result != null) return result.stripTrailingZeros();
    // A single separator followed by three digits can be decimal or grouping.
    if (documented == null || !value.matches("[1-9]\\d*[.,]\\d{3}")) return null;
    return documented.compareTo(new BigDecimal(value.replace(',', '.'))) == 0
            || documented.compareTo(new BigDecimal(value.replaceAll("[.,]", ""))) == 0
        ? documented : null;
  }

  private static final Pattern BID_EXPIRY =
      pattern("\\b(?:valid (?:until|through)|we keep this price until):?\\s+"
          + "(\\d{1,2} [A-Z]{3} \\d{4} \\d{2}:\\d{2}) UTC\\b");
  private static final DateTimeFormatter BID_EXPIRY_FORMAT =
      new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("d MMM uuuu HH:mm")
          .toFormatter(Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT);

  public static Instant bidValidUntil(String body) {
    var dates = BID_EXPIRY.matcher(authoredText(body)).results().map(m -> m.group(1));
    return dates.map(date -> LocalDateTime.parse(date, BID_EXPIRY_FORMAT).toInstant(ZoneOffset.UTC))
        .min(Instant::compareTo).orElse(null);
  }

  private static final Pattern EXCLUDES_COLLECTION_WAIT =
      pattern("(?:\\h*[,;]\\h*)?\\bexcluding\\h+(?:(?:the|any)\\h+)?wait(?:ing\\h+time)?"
          + "\\h+(?:before|until)\\h+(?:pickup|collection)\\b");
  private static final Pattern PICKUP_BOOKING_NOT_REQUIRED =
      pattern("\\bno\\h+need\\h+to\\h+book\\h+(?:the\\h+)?(?:pickup|collection)\\h+separately\\b");
  private static final Pattern PICKUP_WAITING_CHARGE = pattern(
      "(?m)^\\h*Not\\s+part\\s+of\\s+this\\s+offer:\\s+waiting\\s+time\\s+at\\s+the\\s+pickup"
          + "\\s+address\\s+after\\s+the\\s+first\\s+([1-9]\\d{0,2})\\s+minutes,\\s+"
          + "([0-9]+[.,][0-9]{2})\\s+EUR\\s+for\\s+each\\s+hour\\.\\h*$");

  private static final String ROAD_QUOTE_AIRPORT =
      "\\b(?:pickup address to|road delivery (?:to|at)|delivery at)\\h+((?-i:[A-Z]{3}))\\b";
  private static final Pattern EXCLUDES_ONWARD_AIR_TRANSIT = pattern(
      "(?:^|(?<=[.!?])\\h+|\\R)\\h*onward (?:air )?transit to ((?-i:[A-Z]{3})) is not included"
          + "(?=[.!?]|\\h*(?:\\R|$))");

  public static String withoutPickupWaitingCharge(String body) {
    return PICKUP_WAITING_CHARGE.matcher(body).replaceAll("");
  }

  public static String pickupWaitingDisclosure(String body) {
    return PICKUP_WAITING_CHARGE.matcher(authoredText(body)).results()
        .findFirst()
        .map(charge -> "The first " + charge.group(1) + " minutes of waiting at pickup are included."
            + " Longer waits incur a charge of EUR " + number(charge.group(2)).toPlainString()
            + " per hour.")
        .orElse(null);
  }

  public static boolean confirmsPickupArea(String body, String area) {
    String text = EXCLUDES_COLLECTION_WAIT.matcher(authoredText(body)).replaceAll("");
    if (!has(text, "\\ball[- ]in\\b")) return false;
    if (has(text, "\\b(?:except|excluding|only|subject to|depending on|conditional|"
        + "(?:postcode|address) surcharge (?:applies|may apply))\\b")) return false;
    String town = Pattern.quote(area);
    return has(text, "(?:^|[.!?]\\h+|\\R)\\h*(?:(?:this|the|our)\\h+"
        + "(?:(?:all-in|fixed|firm)\\h+)?(?:rate|price|quote)(?:\\h+and\\h+transit(?:\\h+time)?)?\\h+)?"
        + "(?:(?:is\\h+)?valid\\h+(?:for\\h+all\\h+of|throughout)|"
        + "(?:apply|applies|covers)\\h+(?:to\\h+)?"
        + "(?:all\\h+addresses\\h+in|all\\h+of|anywhere\\h+in|throughout))\\h+" + town
        + "(?:,\\h*(?:Germany|Deutschland))?\\h*(?:[.!]|$|\\R)");
  }

  private static final String FROM_PICKUP_TO_DELIVERY =
      "(?:from (?:actual )?(?:pickup|collection) to (?:airport )?delivery"
          + "(?: at (?:the airport|(?-i:[A-Z]{3})(?: airport)?))?|"
          + "(?:pickup|collection)[ -]to[ -](?:airport[ -])?delivery(?: time)?)";
  private static final String TRUCK_TRANSIT_LABEL =
      "\\b(?:(?:(?:road|truck(?:ing)?) )?transit(?: time)?(?: "
          + FROM_PICKUP_TO_DELIVERY + ")?|" + FROM_PICKUP_TO_DELIVERY + ")";
  private static final String TIMING_SEPARATOR = "\\s*(?:is\\s+|of\\s+|takes\\s+|[:=]\\s*)?";
  private static final String DURATION_NUMBER = "\\d{1,4}(?:[.,]\\d{1,2})?";
  private static final String DURATION_UNIT = "hours?|hrs?|h|minutes?|mins?|days?|tage?";
  private static final String DURATION =
      "(" + DURATION_NUMBER + ")\\s*(" + DURATION_UNIT + ")\\b";
  private static final String DURATION_RANGE =
      DURATION_NUMBER + "(?:\\h*(?:" + DURATION_UNIT + ")\\b)?\\h*(?:[-–—]|to)\\h*"
          + DURATION_NUMBER + "\\h*(?:" + DURATION_UNIT + ")\\b";
  private static final Pattern TRUCK_TRANSIT =
      pattern(TRUCK_TRANSIT_LABEL + TIMING_SEPARATOR + DURATION);
  private static final Pattern TRUCK_TRANSIT_AFTER_DURATION =
      pattern(DURATION + "\\s+" + FROM_PICKUP_TO_DELIVERY);
  private static final Pattern TRUCK_TRANSIT_RANGE =
      pattern(TRUCK_TRANSIT_LABEL + TIMING_SEPARATOR + DURATION_RANGE
          + "|" + DURATION_RANGE + "\\s+" + FROM_PICKUP_TO_DELIVERY);
  private static final Pattern TRUCK_DURATION = pattern(DURATION);
  private static final Pattern ANY_DURATION_RANGE = pattern(DURATION_RANGE);
  private static final Pattern ROAD_CONTEXT = pattern(
      "\\b(?:driving|drive|on the road|road (?:leg|journey|travel)|fahrtzeit|fahrtdauer)\\b");
  private static final Pattern AFTER_COLLECTION = pattern(
      "\\b(?:after|once)\\b[^.!?]{0,60}\\b(?:pick[ -]?up|picked up|collect(?:ed|ion)?|loaded|loading)\\b");
  private static final Pattern DELIVERY_CONTEXT = pattern(
      "\\b(?:reach(?:es)?|arrive[sd]?|deliver(?:y|ed|s)?|airport|flughafen)\\b");
  private static final Pattern QUALIFIED_TIMING = pattern(
      "\\b(?:cannot|can[’']t|not|unconfirmed|unavailable|subject to|if|unless|depending on|or)\\b");
  private static final Pattern COLLECTION_WAIT_CONTEXT = pattern(
      "\\b(?:wait(?:ing)?|(?:until|before|time to|drive to) (?:actual )?(?:pick[ -]?up|collection)"
          + "|from (?:cargo |shipment )?readiness|(?:pick[ -]?up|collection) (?:in|within))\\b");
  private static final Pattern AMBIGUOUS_PICKUP_TIMING = pattern(
      "\\b(?:(?:pick[ -]?up|collection) (?:lead )?time|time to (?:pick[ -]?up|collection)|"
          + "lead time before (?:pick[ -]?up|collection)|collect(?:ion)? (?:in|within))"
          + "\\b[^.!?;\\r\\n]{0,32}?" + DURATION);
  private static final String TRUCK_TIMING_DESCRIPTION =
      "(?:(?:road |airport |truck(?:ing)? )?transit(?: time)?(?: " + FROM_PICKUP_TO_DELIVERY + ")?"
          + "|(?:the )?(?:total )?time to delivery(?: at [A-Z]{3} airport)?"
          + "|(?:pickup|collection|delivery) time|(?:time )?" + FROM_PICKUP_TO_DELIVERY + ")";
  private static final Pattern UNCONFIRMED_TRUCK_TIMING =
      pattern("\\b(?:(?:cannot|can[’']t|unable to) confirm\\h+(?:(?:a|an|the|our)\\h+)?"
          + "(?:(?:separate\\h+)?estimate\\h+(?:for\\h+)?)?"
          + "(?:exact |estimated )?" + TRUCK_TIMING_DESCRIPTION
          + "|" + TRUCK_TIMING_DESCRIPTION + "\\h+(?:is (?:unavailable|unconfirmed)|cannot be confirmed))"
          + "(?=\\h*(?:[.!?;\\r\\n]|$))");

  private static Double truckTransit(String text) {
    if (TRUCK_TRANSIT_RANGE.matcher(text).find()) return null;
    var durations = new HashSet<Double>();
    for (var expression : List.of(TRUCK_TRANSIT, TRUCK_TRANSIT_AFTER_DURATION)) {
      for (var match : expression.matcher(text).results().toList()) {
        durations.add(durationDays(match));
      }
    }
    if (durations.size() != 1) return null;
    double days = durations.iterator().next();
    return days <= 90 ? days : null;
  }

  private static double durationDays(MatchResult match) {
    double duration = Double.parseDouble(match.group(1).replace(',', '.'));
    String unit = match.group(2).toLowerCase(Locale.ROOT);
    if (unit.startsWith("h")) return duration / 24;
    if (unit.startsWith("min")) return duration / (24 * 60);
    return duration;
  }

  public static boolean quotesAmbiguousPickupTiming(String body) {
    return AMBIGUOUS_PICKUP_TIMING.matcher(authoredText(body)).find();
  }

  /** Candidate clauses for resolving unusual road timing in an otherwise usable truck bid. */
  public static Map<String, Double> roadTransitEvidence(String body) {
    var candidates = new LinkedHashMap<String, Double>();
    String text = authoredText(body);
    if (text.length() > 3500 || TRUCK_TRANSIT_RANGE.matcher(text).find()) return candidates;
    for (String raw : text.split("\\R+|(?<=[.!?])\\h+")) {
      String clause = raw.strip();
      if (clause.length() > 240 || QUALIFIED_TIMING.matcher(clause).find()
          || COLLECTION_WAIT_CONTEXT.matcher(clause).find()) continue;
      boolean road = DELIVERY_CONTEXT.matcher(clause).find()
          && (ROAD_CONTEXT.matcher(clause).find() || AFTER_COLLECTION.matcher(clause).find());
      if (!road) continue;
      if (ANY_DURATION_RANGE.matcher(clause).find()) return Map.of();
      var durations = TRUCK_DURATION.matcher(clause).results().toList();
      if (durations.size() != 1) continue;
      double days = durationDays(durations.getFirst());
      if (days > 0 && days <= 90) candidates.put(clause, days);
    }
    return candidates;
  }

  /**
   * Never promote a per-kg, provisional, mixed-currency or multi-option number to an all-in price.
   */
  public static Optional<Bid> bid(String body, boolean truck) {
    String text = authoredText(body);
    if (truck) {
      if (PICKUP_WAITING_CHARGE.matcher(text).results().limit(2).count() > 1) return Optional.empty();
      text = withoutPickupWaitingCharge(text);
    }
    String priceTerms = text;
    if (truck) {
      priceTerms = UNCONFIRMED_TRUCK_TIMING.matcher(priceTerms).replaceAll("");
      priceTerms = EXCLUDES_COLLECTION_WAIT.matcher(priceTerms).replaceAll("");
      priceTerms = PICKUP_BOOKING_NOT_REQUIRED.matcher(priceTerms).replaceAll("");
      String roadAirport = only(captures(text, ROAD_QUOTE_AIRPORT));
      if (roadAirport != null) {
        priceTerms = EXCLUDES_ONWARD_AIR_TRANSIT.matcher(priceTerms)
            .replaceAll(match -> roadAirport.equals(match.group(1)) ? match.group() : "");
      }
    }
    if (has(
        priceTerms,
        "\\b(?:USD|GBP|CHF|plus|excluding|excluded|estimated (?:price|cost|rate|total)|"
            + "estimate(?:d)? (?:of )?(?:EUR|price|rate|cost)|(?:price|rate|cost) (?:is )?(?:an )?estimate|"
            + (!truck ? "estimate|" : "")
            + "indicative|provisional|unconfirmed|unavailable|unable|cannot|can[’']t|decline|separately|additional"
            + " charges|starting at|extras"
            + " apply)\\b|\\+\\s*(?:VAT|fuel|security)|(?:per|/)\\s*(?:kg|km|pallet)|"
            + "\\bnot\\s+(?:part\\s+of\\s+(?:this\\s+offer|the\\s+total)|included)\\b|\\bsubject"
            + " to\\b|\\b(?:rate|price|cost|total) (?:is )?(?:not (?:yet )?confirmed|pending confirmation)\\b"))
      return Optional.empty();
    var amounts = new ArrayList<>(captures(text, "(?:\\bEUR\\s*|€\\s*)" + NUM));
    amounts.addAll(captures(text, NUM + "\\s*(?:EUR\\b|€)"));
    var price = number(only(amounts));
    if (price == null || price.signum() <= 0) return Optional.empty();
    if (!truck && has(text,
        "\\d+\\s*(?:-|–|to)\\s*\\d+\\s*(?:business |working )?(?:days?|tage?|hours?|hrs?|h)"))
      return Optional.empty();
    String timing =
        pattern(
                "(?:valid(?:ity)?|gültig)\\s*(?:for|of|:)?\\s*\\d+\\s*(?:days?|tage?|hours?|hrs?|h)\\b")
            .matcher(priceTerms)
            .replaceAll("");
    if (truck) return Optional.of(new Bid(price, truckTransit(timing)));
    var transit =
        captures(
            timing, "\\b(\\d{1,2})\\s*(?:business |working |calendar )?(?:days?|tag(?:e|en)?)\\b");
    if (transit.isEmpty()) transit = captures(timing, "\\bD\\+(\\d{1,2})\\b");
    if (transit.isEmpty() && has(timing, "same[ -]day|am selben tag")) transit = List.of("0");
    var hours =
        captures(
            timing,
            "\\b(?:pickup|collection|delivery|transit|lead)(?:\\s+time)?\\s*(?:is|of|in|:|=)?\\s*(\\d{1,4})\\s*(?:hours?|hrs?|h)\\b");
    if (!hours.isEmpty()) {
      if (hours.size() != 1 || !transit.isEmpty()) return Optional.empty();
      int duration = Integer.parseInt(hours.getFirst());
      return duration <= 90 * 24 ? Optional.of(new Bid(price, duration / 24.0)) : Optional.empty();
    }
    if (transit.size() != 1) return Optional.empty();
    int days = Integer.parseInt(transit.getFirst());
    return days <= 90 ? Optional.of(new Bid(price, (double) days)) : Optional.empty();
  }
}
