package ai.aeiou.dispatch;

import ai.aeiou.freight.MailParser;
import ai.aeiou.freight.Shipment;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

final class QuoteAttachments {
  private static final Pattern OFFER = Pattern.compile(
      "(?iu)\\b(?:quote|quotation|offer|bid|devis|offre|cotation|angebot|offerte)\\b|报价|報價");
  private static final Pattern OUTPUT_REQUEST = Pattern.compile(
      "(?iu)\\b(?:attach|send|provide|submit|include|reply|return|deliver|need|needs|require|requires|"
          + "joindre|joignez|envoyer|envoyez|transmettre|fournir|schicken|senden|anhängen|beifügen|"
          + "benötigen|brauchen|übermitteln)\\b|请|請|需要");
  private static final Pattern REQUESTED_FORMAT = Pattern.compile(
      "(?iu)\\b(?:as (?:a |an )?|in (?:a |an )?|format|only|als|im|en|au)\\h+"
          + "(?:\\.?)(?:pdf|docx|word|xlsx|xls|excel|spreadsheet|csv|txt|plain text|png|jpe?g)\\b"
          + "|(?:pdf|docx|word|xlsx|xls|excel|spreadsheet|csv|txt|plain text|png|jpe?g)[ -]+(?:file|format|offer|quote|quotation|datei|angebot)"
          + "|(?:pdf|docx|word|xlsx|xls|excel|csv|txt|png|jpe?g)\\h*(?:格式|文件|附件)|(?:格式|附件)\\h*(?:为|為)?\\h*(?:pdf|docx|word|xlsx|xls|excel|csv|txt|png|jpe?g)");
  private static final Pattern INBOUND_REQUEST = Pattern.compile(
      "(?iu)\\b(?:rfq|quotation request|quote request|our request|find attached|please find|"
          + "we (?:have )?attached|vous trouverez|anbei)\\b|随附|隨附");
  private static final Pattern EXPLICIT_XLSX = Pattern.compile("(?iu)\\bxlsx\\b");
  private static final Pattern PLAIN_EMAIL = Pattern.compile(
      "(?iu)\\b(?:in (?:the )?(?:email|mail) body|no attachments?|without attachments?|sans pièce jointe)\\b"
          + "|keine anhänge|无需附件|無需附件");
  private static final DateTimeFormatter UTC =
      DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);

  enum Format {
    PDF("application/pdf", "pdf"),
    DOCX("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx"),
    XLSX("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx"),
    XLS("application/vnd.ms-excel", "xls"),
    CSV("text/csv", "csv"),
    TXT("text/plain", "txt"),
    PNG("image/png", "png"),
    JPG("image/jpeg", "jpg");

    final String contentType;
    final String extension;
    final Pattern mention;

    Format(String contentType, String extension) {
      this.contentType = contentType;
      this.extension = extension;
      String names = switch (extension) {
        case "docx" -> "docx|word(?: document)?";
        case "xlsx" -> "xlsx|excel|spreadsheet";
        case "txt" -> "txt|plain text|text file";
        case "jpg" -> "jpe?g";
        default -> extension;
      };
      mention = Pattern.compile("(?iu)(?<![a-z0-9])(?:" + names + ")(?![a-z0-9])");
    }
  }

  private record Field(String name, String value) {}

  static EnumSet<Format> requested(String subject, String body) {
    var result = EnumSet.noneOf(Format.class);
    String text = subject + "\n" + MailParser.authoredText(body);
    for (var clause : text.split("\\R+|(?<=[.!?。！？])(?:\\h+|(?=\\p{IsHan}))")) {
      if (!OFFER.matcher(clause).find() || PLAIN_EMAIL.matcher(clause).find()) continue;
      boolean formatRequirement = REQUESTED_FORMAT.matcher(clause).find();
      if (!OUTPUT_REQUEST.matcher(clause).find() && !formatRequirement) continue;
      if (INBOUND_REQUEST.matcher(clause).find() && !OUTPUT_REQUEST.matcher(clause).find()) continue;
      for (var format : Format.values()) {
        if (!format.mention.matcher(clause).find()) continue;
        if (format == Format.XLSX && Format.XLS.mention.matcher(clause).find()
            && !EXPLICIT_XLSX.matcher(clause).find()) continue;
        result.add(format);
      }
    }
    return result;
  }

  static List<Map<String, String>> generate(long id, Map<String, Object> request, Shipment shipment,
      WaveWorker.Draft draft) {
    var formats = requested(Ledger.str(request, "subject"), Ledger.str(request, "body"));
    if (formats.isEmpty()) return List.of();
    if (formats.size() > 5) throw new WaveWorker.Conflict("5U accepts at most five quote attachments.");
    var offer = draft.offer();
    String reference = "AEIOU-" + id;
    String title = "AEIOU | Air freight offer";
    var fields = new ArrayList<>(List.of(new Field("Reference", reference),
        new Field("Route", shipment.origin() + " to " + shipment.destination()),
        new Field("Carrier", offer.airline()),
        new Field("Gross weight (kg)", shipment.weightKg().stripTrailingZeros().toPlainString()),
        new Field("All-in total (EUR)", offer.totalEur().toPlainString())));
    if (offer.validUntil() != null) fields.add(new Field("Valid until", UTC.format(offer.validUntil())));
    fields.add(new Field("Offer details", draft.body()));

    var lines = new ArrayList<String>(List.of(title, ""));
    for (var field : fields) {
      if (field.name().equals("Offer details")) {
        lines.add("");
        lines.addAll(field.value().lines().toList());
      } else {
        lines.add(field.name() + "  " + field.value());
      }
    }
    var attachments = new ArrayList<Map<String, String>>();
    for (var format : formats) {
      try {
        var content = switch (format) {
          case PDF -> pdf(lines);
          case DOCX -> docx(lines);
          case XLSX -> spreadsheet(fields, new XSSFWorkbook());
          case XLS -> spreadsheet(fields, new HSSFWorkbook());
          case CSV -> csv(fields);
          case TXT -> String.join("\n", lines).getBytes(StandardCharsets.UTF_8);
          case PNG, JPG -> image(lines, format.extension);
        };
        attachments.add(Map.of("filename", reference + "-offer." + format.extension,
            "content_type", format.contentType, "content_base64", Base64.getEncoder().encodeToString(content)));
      } catch (IOException | IllegalArgumentException ex) {
        throw new WaveWorker.Conflict("Could not generate the requested ." + format.extension + " offer.");
      }
    }
    return attachments;
  }

  private static byte[] pdf(List<String> lines) throws IOException {
    try (var document = new PDDocument(); var bytes = new ByteArrayOutputStream()) {
      var normal = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
      var heading = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
      var content = newPdfPage(document);
      try {
        float y = 780;
        for (int i = 0; i < lines.size(); i++) {
          String line = lines.get(i);
          if (line.isEmpty()) {
            y -= 10;
            continue;
          }
          var font = i == 0 ? heading : normal;
          int size = i == 0 ? 19 : 11;
          if (encodable(font, line)) {
            for (var part : wrap(line, 498, value -> pdfWidth(font, size, value))) {
              if (y < 60) {
                content.close();
                content = newPdfPage(document);
                y = 780;
              }
              content.beginText();
              content.setFont(font, size);
              content.newLineAtOffset(48, y);
              content.showText(part);
              content.endText();
              y -= i == 0 ? 24 : 17;
            }
          } else {
            var image = render(List.of("", line), 1050, 20, 20);
            int height = Math.round(image.getHeight() / 2.1f);
            if (y - height < 34) {
              content.close();
              content = newPdfPage(document);
              y = 780;
            }
            content.drawImage(LosslessFactory.createFromImage(document, image), 40, y - height + 26,
                500, height);
            y -= Math.max(17, height - 19);
          }
        }
      } finally {
        content.close();
      }
      document.save(bytes);
      return bytes.toByteArray();
    }
  }

  private static PDPageContentStream newPdfPage(PDDocument document) throws IOException {
    var page = new PDPage(PDRectangle.A4);
    document.addPage(page);
    return new PDPageContentStream(document, page);
  }

  private static boolean encodable(PDType1Font font, String line) throws IOException {
    try {
      font.encode(line);
      return true;
    } catch (IllegalArgumentException ex) {
      return false;
    }
  }

  private static double pdfWidth(PDType1Font font, int fontSize, String line) {
    try {
      return font.getStringWidth(line) * fontSize / 1000.0;
    } catch (IOException ex) {
      throw new IllegalArgumentException("Could not measure PDF text.", ex);
    }
  }

  private static byte[] docx(List<String> lines) throws IOException {
    try (var document = new XWPFDocument(); var bytes = new ByteArrayOutputStream()) {
      for (int i = 0; i < lines.size(); i++) {
        var run = document.createParagraph().createRun();
        run.setText(lines.get(i));
        if (i == 0) {
          run.setBold(true);
          run.setFontSize(18);
        }
      }
      document.write(bytes);
      return bytes.toByteArray();
    }
  }

  private static byte[] spreadsheet(List<Field> fields, Workbook workbook) throws IOException {
    try (workbook; var bytes = new ByteArrayOutputStream()) {
      var sheet = workbook.createSheet("Air freight offer");
      sheet.setColumnWidth(0, 24 * 256);
      sheet.setColumnWidth(1, 86 * 256);
      var wrapped = workbook.createCellStyle();
      wrapped.setWrapText(true);
      for (int i = 0; i < fields.size(); i++) {
        var field = fields.get(i);
        var row = sheet.createRow(i);
        row.createCell(0).setCellValue(field.name());
        var value = row.createCell(1);
        value.setCellValue(field.value());
        value.setCellStyle(wrapped);
        if (field.name().equals("Offer details")) {
          long lines = field.value().lines().mapToLong(line -> Math.max(1, (line.length() + 74) / 75)).sum();
          row.setHeightInPoints(Math.min(390, 15 * lines));
        }
      }
      workbook.write(bytes);
      return bytes.toByteArray();
    }
  }

  private static byte[] csv(List<Field> fields) {
    var csv = new StringBuilder("Field,Value\r\n");
    for (var field : fields)
      csv.append(csvCell(field.name())).append(',').append(csvCell(field.value())).append("\r\n");
    return csv.toString().getBytes(StandardCharsets.UTF_8);
  }

  private static String csvCell(String value) {
    if (value.stripLeading().matches("(?s)^[=+@-].*")) value = "'" + value;
    return "\"" + value.replace("\"", "\"\"") + "\"";
  }

  private static byte[] image(List<String> lines, String type) throws IOException {
    try (var bytes = new ByteArrayOutputStream()) {
      if (!ImageIO.write(render(lines, 1200, 23, 68), type, bytes))
        throw new IOException("Missing image encoder.");
      return bytes.toByteArray();
    }
  }

  private static BufferedImage render(List<String> lines, int width, int fontSize, int margin) {
    var normal = new Font(Font.SANS_SERIF, Font.PLAIN, fontSize);
    var heading = normal.deriveFont(Font.BOLD, fontSize * 1.5f);
    var metrics = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB).createGraphics();
    var rows = new ArrayList<String>();
    try {
      for (int i = 0; i < lines.size(); i++) {
        var measure = metrics.getFontMetrics(i == 0 ? heading : normal);
        rows.addAll(wrap(lines.get(i), width - 2 * margin, measure::stringWidth));
      }
    } finally {
      metrics.dispose();
    }
    int spacing = (int) (fontSize * 1.5);
    var image = new BufferedImage(width, 2 * margin + spacing * (rows.size() + 1),
        BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = image.createGraphics();
    try {
      graphics.setColor(Color.WHITE);
      graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
      graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
          RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
      graphics.setColor(new Color(29, 41, 56));
      for (int i = 0; i < rows.size(); i++) {
        graphics.setFont(i == 0 ? heading : normal);
        graphics.drawString(rows.get(i), margin, margin + (i + 1) * spacing);
      }
    } finally {
      graphics.dispose();
    }
    return image;
  }

  private static List<String> wrap(String line, int width, ToDoubleFunction<String> measure) {
    var parts = new ArrayList<String>();
    while (measure.applyAsDouble(line) > width) {
      int end = 0;
      int space = -1;
      while (end < line.length()) {
        int next = end + Character.charCount(line.codePointAt(end));
        if (measure.applyAsDouble(line.substring(0, next)) > width) break;
        if (Character.isWhitespace(line.codePointAt(end))) space = end;
        end = next;
      }
      int split = space > 0 ? space : Math.max(end, Character.charCount(line.codePointAt(0)));
      parts.add(line.substring(0, split));
      line = line.substring(split).stripLeading();
    }
    parts.add(line);
    return parts;
  }
}
