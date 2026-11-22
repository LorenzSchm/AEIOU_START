package ai.aeiou.dispatch;

import ai.aeiou.freight.Catalog.Tariff;
import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.*;
import java.util.regex.Pattern;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

final class TariffSheet {
  private static final Pattern BREAK = Pattern.compile("-45|\\+(\\d+)|from (\\d+) kg");
  private static final Pattern CODE = Pattern.compile("^([A-Z]{3})(?:\\s.*)?$|\\(([A-Z]{3})\\)$");
  private static final Pattern SURCHARGE = Pattern.compile(
      "(.+): fuel (?:EUR )?([0-9.,]+) per kg, security (?:EUR )?([0-9.,]+) per kg");
  private static final Pattern FIELD = Pattern.compile(
      "^(Currency|Valid from|Valid to|Transit|Minimum|Fuel|Security|from \\d+ kg)\\s+(.+)$");
  private static final Pattern TRANSIT = Pattern.compile("(?:D\\+)?(\\d{1,2})(?: days?)?");
  private static final Set<String> STANDARD_TERMS = Set.of(
      "All rates are per kg of chargeable weight, in EUR.",
      "Chargeable weight is the higher of actual weight and volume weight (1 cbm = 167 kg).",
      "Fuel and security surcharges are charged per kg of the weight the freight is billed on.",
      "The minimum charge applies to the freight before surcharges.",
      "Surcharges per kg of billed weight");

  record Parsed(LocalDate from, LocalDate to, List<Tariff> rates) {}
  private record Lane(String origin, String destination) {}

  private static final class LaneRates {
    final NavigableMap<Integer, BigDecimal> breaks = new TreeMap<>();
    Integer days;
    BigDecimal minimum;
    BigDecimal fuel;
    BigDecimal security;

    void set(String field, String value) {
      switch (field) {
        case "Transit" -> {
          var transit = TRANSIT.matcher(value);
          require(days == null && transit.matches(), "Invalid or duplicate transit time.");
          days = Integer.parseInt(transit.group(1));
          require(days <= 90, "Transit time exceeds 90 days.");
        }
        case "Minimum" -> minimum = uniqueAmount(minimum, value);
        case "Fuel" -> fuel = uniqueAmount(fuel, value);
        case "Security" -> security = uniqueAmount(security, value);
        default -> {
          int kg = weightBreak(field);
          require(!breaks.containsKey(kg), "Duplicate weight break.");
          breaks.put(kg, amount(value));
        }
      }
    }
  }

  static Parsed read(String carrier, String filename, byte[] bytes) throws IOException {
    var suffix = filename.toLowerCase(Locale.ROOT);
    List<List<String>> rows;
    if (suffix.endsWith(".xlsx")) {
      rows = workbookRows(bytes);
    } else if (suffix.endsWith(".pdf")) {
      try (var document = Loader.loadPDF(bytes)) {
        require(document.getNumberOfPages() <= 40, "PDF exceeds 40 pages.");
        var stripper = new PDFTextStripper();
        stripper.setSortByPosition(true);
        rows = textRows(stripper.getText(document));
      }
    } else if (suffix.endsWith(".txt")) {
      rows = textRows(new String(bytes, StandardCharsets.UTF_8));
    } else {
      throw new IllegalArgumentException("This attachment format requires manual review.");
    }
    return parse(carrier, rows);
  }

  private static List<List<String>> workbookRows(byte[] bytes) throws IOException {
    List<List<String>> rows = new ArrayList<>();
    try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
      require(workbook.getNumberOfSheets() == 1 && !workbook.isSheetHidden(0)
          && !workbook.isSheetVeryHidden(0), "Expected one visible tariff tab.");
      for (var row : workbook.getSheetAt(0)) {
        require(row.getRowNum() < 1500 && row.getLastCellNum() <= 24, "Tariff tab is too large.");
        List<String> values = new ArrayList<>();
        for (int col = 0; col < row.getLastCellNum(); col++) {
          var cell = row.getCell(col);
          values.add(cell == null ? "" : switch (cell.getCellType()) {
            case BLANK -> "";
            case STRING -> cell.getStringCellValue().strip();
            case NUMERIC -> DateUtil.isCellDateFormatted(cell)
                ? cell.getLocalDateTimeCellValue().toLocalDate().toString()
                : BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros().toPlainString();
            default -> throw new IllegalArgumentException("Tariff cells must contain literal values.");
          });
        }
        if (values.stream().anyMatch(value -> !value.isBlank())) {
          require(!row.getZeroHeight(), "Hidden tariff row.");
          rows.add(values);
        }
      }
    }
    return rows;
  }

  private static List<List<String>> textRows(String text) {
    return text.lines().map(String::strip).filter(line -> !line.isBlank()).map(line -> {
      if (STANDARD_TERMS.contains(line)) return List.of(line);
      var field = FIELD.matcher(line);
      if (field.matches()) return List.of(field.group(1), field.group(2));
      if (line.matches("Lane\\s+Transit\\s+Minimum\\s+.+")) {
        List<String> header = new ArrayList<>(List.of("Lane", "Transit", "Minimum"));
        BREAK.matcher(line).results().forEach(match -> header.add(match.group()));
        return header;
      }
      var amounts = line.split("\\s+EUR\\s+");
      if (amounts.length > 2) {
        var prefix = Pattern.compile("(.+?)\\s+((?:D\\+)?\\d{1,2}(?: days?)?)").matcher(amounts[0]);
        if (prefix.matches()) {
          List<String> fields = new ArrayList<>(List.of(prefix.group(1), prefix.group(2)));
          fields.addAll(Arrays.asList(amounts).subList(1, amounts.length));
          return fields;
        }
      }
      return List.of(line);
    }).toList();
  }

  private static Parsed parse(String carrier, List<List<String>> rows) {
    require(!rows.isEmpty() && rows.size() <= 1500
        && rows.getFirst().getFirst().equalsIgnoreCase(carrier + " | Air export tariff"),
        "The tariff title must match the sending carrier.");
    Map<Lane, LaneRates> lanes = new LinkedHashMap<>();
    Map<String, String> metadata = new HashMap<>();
    List<String> columns = List.of();
    String origin = null;
    LaneRates current = null;
    for (var row : rows.subList(1, rows.size())) {
      var label = row.getFirst();
      if (STANDARD_TERMS.contains(label)) continue;
      if (Set.of("Currency", "Valid from", "Valid to").contains(label)) {
        require(row.size() >= 2 && metadata.putIfAbsent(label, row.get(1)) == null,
            "Missing or duplicate " + label.toLowerCase(Locale.ROOT) + ".");
      } else if (label.startsWith("Origin: ")) {
        origin = code(label.substring(8));
        require(origin != null, "Invalid origin airport.");
        columns = List.of();
        current = null;
      } else if (label.equals("Lane") || label.equals("Destination")) {
        columns = row.size() > 3 && BREAK.matcher(row.get(3)).matches() ? row : List.of();
      } else if (row.size() > 3 && BREAK.matcher(row.get(3)).matches() && label.isBlank()) {
        columns = row;
      } else {
        var surcharge = SURCHARGE.matcher(label);
        if (surcharge.matches()) {
          var rated = lanes.get(lane(surcharge.group(1)));
          require(rated != null, "Surcharge refers to a lane outside the rate table.");
          rated.set("Fuel", surcharge.group(2));
          rated.set("Security", surcharge.group(3));
        } else if (label.equals("Transit") || label.equals("Minimum") || label.equals("Fuel")
            || label.equals("Security") || BREAK.matcher(label).matches()) {
          require(current != null && row.size() >= 2, "Lane rate is missing its label or value.");
          current.set(label, row.get(1));
        } else {
          var key = lane(label);
          if (key == null && origin != null && code(label) != null) key = new Lane(origin, code(label));
          require(key != null && !lanes.containsKey(key), "Unrecognized or duplicate lane row.");
          current = new LaneRates();
          lanes.put(key, current);
          if (row.size() > 1) {
            require(columns.size() >= 4, "Missing weight-break headings.");
            require(row.size() <= columns.size() || row.subList(columns.size(), row.size()).stream()
                .allMatch(String::isBlank), "Lane has extra price columns.");
            current.set("Transit", row.get(1));
            current.set("Minimum", row.size() > 2 ? row.get(2) : "");
            for (int i = 3; i < columns.size(); i++) {
              if (!columns.get(i).isBlank()) current.set(columns.get(i), i < row.size() ? row.get(i) : "");
            }
          }
        }
      }
    }
    require("EUR".equals(metadata.get("Currency")), "The tariff must explicitly use EUR.");
    var from = date(metadata.get("Valid from"));
    var to = date(metadata.get("Valid to"));
    require(!lanes.isEmpty(), "No priced lanes were found.");
    var rates = lanes.entrySet().stream().map(entry -> {
      var key = entry.getKey();
      var rate = entry.getValue();
      require(rate.days != null && rate.minimum != null && rate.fuel != null
          && rate.security != null && rate.breaks.containsKey(0),
          "Missing transit, minimum, base rate or surcharges for " + key.origin + "–" + key.destination + ".");
      return new Tariff(carrier, key.origin, key.destination, rate.days,
          rate.minimum, rate.fuel, rate.security, rate.breaks);
    }).toList();
    return new Parsed(from, to, rates);
  }

  private static Lane lane(String text) {
    var ends = text.split("\\s+>\\s+|\\s+to\\s+|(?<=^[A-Z]{3})-(?=[A-Z]{3}$)", -1);
    if (ends.length != 2) return null;
    var origin = code(ends[0]);
    var destination = code(ends[1]);
    return origin == null || destination == null ? null : new Lane(origin, destination);
  }

  private static String code(String text) {
    var matcher = CODE.matcher(text);
    if (matcher.find()) return matcher.group(1) == null ? matcher.group(2) : matcher.group(1);
    return null;
  }

  private static int weightBreak(String field) {
    var matcher = BREAK.matcher(field);
    require(matcher.matches(), "Unrecognized weight-break heading.");
    if (field.equals("-45")) return 0;
    int kg = Integer.parseInt(matcher.group(1) == null ? matcher.group(2) : matcher.group(1));
    require(kg >= 0 && kg <= 100_000, "Weight break is out of range.");
    return kg;
  }

  private static BigDecimal uniqueAmount(BigDecimal existing, String value) {
    require(existing == null, "Duplicate price or surcharge.");
    return amount(value);
  }

  private static BigDecimal amount(String value) {
    require(value.matches("(?:EUR )?[0-9]{1,7}(?:[.,][0-9]{1,18})?"), "Missing or invalid EUR amount.");
    var amount = new BigDecimal(value.replaceFirst("^EUR ", "").replace(',', '.'));
    if (amount.scale() <= 6) return amount;
    var rounded = amount.setScale(6, java.math.RoundingMode.HALF_UP);
    require(amount.subtract(rounded).abs().compareTo(new BigDecimal("0.000000001")) < 0,
        "Unsupported price precision.");
    return rounded.stripTrailingZeros();
  }

  private static LocalDate date(String value) {
    require(value != null, "Both validity dates are required.");
    for (String format : List.of("uuuu-MM-dd", "d/M/uuuu", "d.M.uuuu")) {
      try {
        return LocalDate.parse(value, DateTimeFormatter.ofPattern(format).withResolverStyle(ResolverStyle.STRICT));
      } catch (DateTimeParseException ignored) {}
    }
    throw new IllegalArgumentException("Invalid validity date. Use a four-digit year.");
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new IllegalArgumentException(message);
  }
}
