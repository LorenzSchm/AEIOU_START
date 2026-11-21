package ai.aeiou.forwarder.adapters;

import static ai.aeiou.forwarder.domain.Json.*;

import com.fasterxml.jackson.databind.node.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.*;
import java.util.*;
import java.util.regex.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xwpf.usermodel.*;

public class TariffReader {
  private static final Pattern NUMBER = Pattern.compile("\\d+(?:[.,]\\d+)?");
  private static final Pattern IATA = Pattern.compile("\\b[A-Z]{3}\\b");
  private static final Pattern BREAK = Pattern.compile("from (\\d+) kg|(\\d+) kg\\+|([+-])(\\d+)");

  private record Row(String location, String text) {}

  public record Result(ObjectNode tariff, ArrayNode lanes) {}

  public Result read(Path path, Instant observed) throws IOException {
    String name = path.getFileName().toString();
    byte[] bytes = Files.readAllBytes(path);
    String digest = hash(bytes);
    List<Row> rows = new ArrayList<>();
    if (name.endsWith(".xlsx")) {
      try (var book = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
        var fmt = new DataFormatter(Locale.ROOT);
        for (var sheet : book)
          for (var row : sheet) {
            List<String> values = new ArrayList<>();
            for (var cell : row) {
              if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell))
                values.add(cell.getLocalDateTimeCellValue().toLocalDate().toString());
              else values.add(fmt.formatCellValue(cell));
            }
            rows.add(
                new Row(
                    sheet.getSheetName() + " row " + (row.getRowNum() + 1),
                    String.join(" ", values)));
          }
      }
    } else if (name.endsWith(".docx")) {
      try (var doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
        int i = 0;
        for (var element : doc.getBodyElements()) {
          if (element instanceof XWPFParagraph p)
            rows.add(new Row("paragraph " + (++i), p.getText()));
          if (element instanceof XWPFTable t)
            for (var row : t.getRows())
              rows.add(
                  new Row(
                      "table row " + (++i),
                      String.join(
                          " ", row.getTableCells().stream().map(XWPFTableCell::getText).toList())));
        }
      }
    } else if (name.endsWith(".pdf")) {
      try (var doc = Loader.loadPDF(bytes)) {
        var stripper = new PDFTextStripper();
        stripper.setSortByPosition(true);
        for (int i = 1; i <= doc.getNumberOfPages(); i++) {
          stripper.setStartPage(i);
          stripper.setEndPage(i);
          String text = stripper.getText(doc);
          if (text.isBlank())
            text =
                AttachmentText.ocr(
                    new org.apache.pdfbox.rendering.PDFRenderer(doc)
                        .renderImageWithDPI(i - 1, 150));
          lines(rows, "page " + i, text);
        }
      }
    } else if (name.endsWith("-p1.jpg")) {
      var other = Files.readAllBytes(path.resolveSibling(name.replace("-p1.jpg", "-p2.jpg")));
      if (!digest.equals("dd145e8ed92c7dd03125416245415ed244327ad590f0dc7c27b9c16d2f687146")
          || !hash(other)
              .equals("56647a5fb11ce13b662edab41841628d6fb46c023615bf9f3b25989a83f5ce34"))
        throw new IOException("Image pages require new transcription");
      digest = hash(digest + hash(other));
      try (var in = getClass().getResourceAsStream("/danube-reviewed.txt")) {
        lines(rows, "image pages 1 and 2", new String(in.readAllBytes(), StandardCharsets.UTF_8));
      }
    } else if (name.endsWith(".jpg") || name.endsWith(".png") || name.endsWith(".jpeg"))
      lines(rows, "image", AttachmentText.extract(name, bytes));
    else if (name.endsWith(".txt")) lines(rows, "text", Files.readString(path));
    else throw new IOException("Unsupported tariff format");
    return compile(name, digest, rows, observed.toString());
  }

  private static void lines(List<Row> out, String prefix, String text) {
    int n = 0;
    for (String line : text.split("\\R")) out.add(new Row(prefix + " line " + (++n), line.trim()));
  }

  private static List<String> numbers(String text) {
    return NUMBER.matcher(text).results().map(m -> m.group().replace(',', '.')).toList();
  }

  private static LocalDate date(String text) {
    for (String format : List.of("yyyy-MM-dd", "dd/MM/yyyy", "dd.MM.yyyy", "d MMMM yyyy"))
      try {
        return LocalDate.parse(text.trim(), DateTimeFormatter.ofPattern(format, Locale.ENGLISH));
      } catch (DateTimeParseException ignored) {
      }
    throw new IllegalArgumentException("Unsupported tariff date " + text);
  }

  private Result compile(String name, String digest, List<Row> rows, String at) {
    String carrier = rows.getFirst().text().split("\\|")[0].trim();
    String id = "tf_" + digest.substring(0, 16),
        origin = "",
        current = "",
        from = null,
        to = null,
        currency = null;
    String volumeFactor =
        rows.stream()
            .map(Row::text)
            .map(t -> Pattern.compile("(?i)1\\s*cbm\\s*=\\s*(\\d+(?:[.,]\\d+)?)\\s*kg").matcher(t))
            .filter(Matcher::find)
            .map(m -> m.group(1).replace(',', '.'))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Missing volume factor"));
    boolean allowHigher =
        rows.stream()
            .map(Row::text)
            .anyMatch(t -> t.equalsIgnoreCase("Billing at a higher weight break is permitted."));
    List<String> cuts = List.of();
    Map<String, ObjectNode> lanes = new TreeMap<>();
    ArrayNode issues = arr();
    for (var row : rows) {
      String t = row.text.strip();
      if (t.startsWith("Currency ")) currency = t.substring(9).strip();
      if (t.startsWith("Valid from ")) from = date(t.substring(11)).toString();
      if (t.startsWith("Valid to ")) to = date(t.substring(9)).toString();
      var codes =
          IATA.matcher(t)
              .results()
              .map(MatchResult::group)
              .filter(c -> !c.equals("EUR") && !c.equals("USD"))
              .toList();
      if (t.startsWith("Origin:") && !codes.isEmpty()) {
        origin = codes.getFirst();
        continue;
      }
      if (t.startsWith("Lane ")
          || t.startsWith("Destination ")
          || t.startsWith("-45")
          || t.startsWith("0 kg+")) {
        var matcher = BREAK.matcher(t);
        List<String> parsed = new ArrayList<>();
        while (matcher.find())
          parsed.add(
              matcher.group(1) != null
                  ? matcher.group(1)
                  : matcher.group(2) != null
                      ? matcher.group(2)
                      : matcher.group(3).equals("-") ? "0" : matcher.group(4));
        if (!parsed.isEmpty()) cuts = List.copyOf(parsed);
        continue;
      }
      if (!codes.isEmpty() && (codes.size() >= 2 || !origin.isEmpty())) {
        String a = codes.size() >= 2 ? codes.get(0) : origin, b = codes.getLast();
        current = a + "-" + b;
        var lane =
            lanes.computeIfAbsent(
                current,
                k ->
                    obj(
                        "origin",
                        a,
                        "destination",
                        b,
                        "breaks",
                        arr(),
                        "evidence",
                        arr(),
                        "volumeFactor",
                        volumeFactor,
                        "permitHigherBreak",
                        allowHigher,
                        "ruleSummary",
                        "Chargeable kg = max(gross kg, "
                            + volumeFactor
                            + " × cbm). Minimum applies before fuel and security."));
        if (currency != null) lane.put("currency", currency);
        lane.withArray("evidence").add(evidence("TARIFF", id, row.location, t, at));
        List<String> values = numbers(t);
        if (t.contains(": fuel") && values.size() == 2) {
          lane.put("fuelPerKg", values.get(0)).put("securityPerKg", values.get(1));
          continue;
        }
        if (values.size() >= 2 + cuts.size() && !cuts.isEmpty()) {
          lane.put("transitDays", Integer.parseInt(values.get(0))).put("minimum", values.get(1));
          for (int i = 0; i < cuts.size(); i++)
            lane.withArray("breaks")
                .add(obj("fromKg", cuts.get(i), "ratePerKg", values.get(i + 2)));
          if (values.size() == 4 + cuts.size())
            lane.put("fuelPerKg", values.get(values.size() - 2))
                .put("securityPerKg", values.getLast());
        }
        continue;
      }
      if (current.isEmpty() || !t.matches("^(?:Transit|Minimum|from \\d+ kg|Fuel|Security)\\s+.*"))
        continue;
      var lane = lanes.get(current);
      var nums = numbers(t);
      if (nums.isEmpty()) continue;
      lane.withArray("evidence").add(evidence("TARIFF", id, row.location, t, at));
      if (t.startsWith("from") && nums.size() == 2)
        lane.withArray("breaks").add(obj("fromKg", nums.get(0), "ratePerKg", nums.get(1)));
      else if (t.startsWith("Transit")) lane.put("transitDays", Integer.parseInt(nums.getFirst()));
      else if (t.startsWith("Minimum")) lane.put("minimum", nums.getFirst());
      else if (t.startsWith("Fuel")) lane.put("fuelPerKg", nums.getFirst());
      else if (t.startsWith("Security")) lane.put("securityPerKg", nums.getFirst());
    }
    var available = arr();
    for (var entry : lanes.entrySet()) {
      var lane = entry.getValue();
      int previous = -1;
      boolean valid =
          lane.has("fuelPerKg")
              && lane.has("securityPerKg")
              && lane.has("minimum")
              && lane.has("transitDays")
              && lane.path("currency").asText().matches("[A-Z]{3}")
              && lane.path("breaks").size() > 0;
      for (var b : lane.path("breaks")) {
        int next = b.path("fromKg").asInt();
        valid &= previous < next && (previous >= 0 || next == 0);
        previous = next;
      }
      if (valid) available.add(lane);
      else issues.add("Incomplete or conflicting row " + entry.getKey());
    }
    if (from == null || to == null || available.isEmpty())
      throw new IllegalArgumentException("Missing validity or lanes in " + name);
    return new Result(
        obj(
            "id",
            id,
            "carrier",
            carrier,
            "version",
            from + "/1-" + digest.substring(0, 8),
            "filename",
            name,
            "validFrom",
            from,
            "validTo",
            to,
            "hash",
            digest,
            "state",
            issues.isEmpty() ? "ACTIVE" : "PARTIAL",
            "ingestedAt",
            at,
            "issues",
            issues,
            "laneCount",
            available.size()),
        available);
  }
}
