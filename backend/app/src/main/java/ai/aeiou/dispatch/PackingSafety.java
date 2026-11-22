package ai.aeiou.dispatch;

import java.io.*;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.ZipInputStream;
import javax.xml.stream.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;

final class PackingSafety {
  static final int MAX_BYTES = 2_000_000;
  private static final Map<String, String> MIME_EXTENSIONS = Map.ofEntries(
      Map.entry("application/pdf", "pdf"),
      Map.entry("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx"),
      Map.entry("text/plain", "txt"),
      Map.entry("text/csv", "csv"),
      Map.entry("text/tab-separated-values", "tsv"),
      Map.entry("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx"),
      Map.entry("application/rtf", "rtf"), Map.entry("text/rtf", "rtf"),
      Map.entry("image/png", "png"), Map.entry("image/jpeg", "jpg"),
      Map.entry("image/tiff", "tiff"));
  private static final Set<String> EXTENSIONS = Set.of(
      "pdf", "docx", "txt", "csv", "tsv", "xlsx", "rtf", "png", "jpg", "jpeg", "tif", "tiff");
  private static final Pattern DG =
      Pattern.compile(
          "(?iu)\\bUN[\\s-]*[0-9]{4}\\b|\\b(?:dangerous\\s+goods|hazmat|gefahrgut)\\s*[:=]?\\s*(?:yes|ja|class\\s+[1-9]|klasse\\s+[1-9])\\b|\\b(?:hazard|DG)\\s+class\\s*[:=]?\\s*[1-9]");

  static boolean declaresDangerousGoods(String text) {
    return DG.matcher(text).find();
  }

  enum DocumentType {
    GENERAL,
    DANGEROUS,
    UNKNOWN
  }

  private static final Pattern GENERAL =
      Pattern.compile(
          "(?im)^\\h*(?:dangerous goods|hazmat|gefahrgut)\\h*[:=]?\\s*(?:no|none(?:\\h*\\(non[- ]DG\\))?|nein|n/a|not"
              + " (?:regulated|restricted)|non-dangerous)\\h*[.]?\\h*$|\\b(?:general cargo\\s*,?\\s*non[-"
              + " ]dangerous|non[- ]dangerous\\s+goods)\\b");
  private static final Pattern PACKING =
      Pattern.compile(
          "(?iu)\\b(?:packing (?:list|specification)|shipper.s letter of"
              + " instruction|packliste)\\b");
  private static final Pattern DESTINATION =
      Pattern.compile("(?im)^\\h*destination airport\\h*[:=]?\\s*([A-Z]{3})\\b");
  private static final Pattern GROSS =
      Pattern.compile(
          "(?im)^\\h*total gross weight(?:\\h*\\(kg\\)\\h*[:=]?\\s*([0-9]+(?:[.,][0-9]+)?)"
              + "|\\h*[:=]?\\s*([0-9]+(?:[.,][0-9]+)?)\\h*kg)\\h*$");
  private static final Pattern VOLUME =
      Pattern.compile(
          "(?im)^\\h*total volume(?:\\h*\\((?:cbm|m3|m³)\\)\\h*[:=]?\\s*([0-9]+(?:[.,][0-9]+)?)"
              + "|\\h*[:=]?\\s*([0-9]+(?:[.,][0-9]+)?)\\h*(?:cbm|m3|m³))\\h*$");
  private static final Pattern UN_NUMBER = Pattern.compile("(?iu)\\bUN[\\s-]*([0-9]{4})\\b");

  record Document(String text) {
    DocumentType type() {
      if (declaresDangerousGoods(text)) return DocumentType.DANGEROUS;
      if (PACKING.matcher(text).find() && GENERAL.matcher(text).find()) return DocumentType.GENERAL;
      return DocumentType.UNKNOWN;
    }

    Set<String> destinations() {
      return values(DESTINATION, text);
    }

    Set<BigDecimal> grossWeights() {
      return decimals(values(GROSS, text));
    }

    Set<BigDecimal> volumes() {
      return decimals(values(VOLUME, text));
    }

    Set<String> unNumbers() {
      return values(UN_NUMBER, text);
    }

    boolean safetyDataSheet() {
      return Pattern.compile("(?iu)\\bsafety data sheet\\b").matcher(text).find()
          && Pattern.compile("(?iu)\\bsection\\h+14\\b").matcher(text).find();
    }

    boolean declaration() {
      return Pattern.compile(
              "(?iu)\\b(?:declaration for dangerous goods|dangerous goods declaration)\\b")
          .matcher(text)
          .find();
    }
  }

  private static Set<String> values(Pattern pattern, String text) {
    var results = new HashSet<String>();
    pattern
        .matcher(text)
        .results()
        .map(m -> (m.group(1) != null ? m.group(1) : m.group(2)).toUpperCase(Locale.ROOT))
        .forEach(results::add);
    return results;
  }

  private static Set<BigDecimal> decimals(Set<String> values) {
    var results = new HashSet<BigDecimal>();
    values.stream()
        .map(value -> new BigDecimal(value.replace(',', '.')).stripTrailingZeros())
        .forEach(results::add);
    return results;
  }

  static String extension(String filename, String contentType) {
    String name = filename.strip().toLowerCase(Locale.ROOT);
    String ext = name.substring(name.lastIndexOf('.') + 1);
    if (EXTENSIONS.contains(ext)) return ext;
    String mime = contentType.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
    return MIME_EXTENSIONS.get(mime);
  }

  static String extract(String filename, byte[] content) throws IOException, XMLStreamException {
    return extract(filename, content, false);
  }

  static final class NeedsOcr extends IOException {}

  static String extract(String filename, byte[] content, boolean ocr)
      throws IOException, XMLStreamException {
    if (content == null || content.length == 0 || content.length > MAX_BYTES)
      throw new IOException("Empty or oversized attachment");
    String name = filename.toLowerCase(Locale.ROOT);
    if (name.endsWith(".txt") || name.endsWith(".csv") || name.endsWith(".tsv"))
      return plainText(content);
    if (name.endsWith(".pdf")) return pdfText(content, ocr);
    if (name.endsWith(".docx")) return wordText(content);
    if (name.endsWith(".rtf")) return rtfText(content);
    if (name.matches(".*\\.(?:png|jpe?g|tiff?)")) {
      if (!ocr) throw new NeedsOcr();
      return AttachmentOcr.read(content, name.substring(name.lastIndexOf('.') + 1),
          AttachmentOcr.deadline());
    }
    if (!name.endsWith(".xlsx")) throw new IOException("Unsupported packing attachment");

    var factory = xmlFactory();
    var sheets = new ArrayList<byte[]>();
    var sharedStrings = new ArrayList<String>();
    int remaining = MAX_BYTES;
    try (var zip = new ZipInputStream(new ByteArrayInputStream(content))) {
      for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
        byte[] data = zip.readNBytes(remaining + 1);
        if (data.length > remaining) throw new IOException("Oversized expanded workbook");
        remaining -= data.length;
        String path = entry.getName();
        if (path.matches("xl/worksheets/sheet[0-9]+\\.xml")) sheets.add(data);
        else if (path.equals("xl/sharedStrings.xml")) {
          var reader = factory.createXMLStreamReader(new ByteArrayInputStream(data));
          try {
            while (reader.hasNext()) {
              if (reader.next() == XMLStreamConstants.START_ELEMENT
                  && reader.getLocalName().equals("si"))
                sharedStrings.add(readCell(reader, "si", "t"));
            }
          } finally {
            reader.close();
          }
        }
      }
    }

    var text = new StringBuilder();
    for (byte[] sheet : sheets) {
      var reader = factory.createXMLStreamReader(new ByteArrayInputStream(sheet));
      try {
        while (reader.hasNext()) {
          int event = reader.next();
          if (event == XMLStreamConstants.END_ELEMENT && reader.getLocalName().equals("row"))
            text.append('\n');
          if (event != XMLStreamConstants.START_ELEMENT || !reader.getLocalName().equals("c"))
            continue;
          String type = reader.getAttributeValue(null, "t");
          String cell = readCell(reader, "c", "inlineStr".equals(type) ? "t" : "v");
          if ("s".equals(type)) {
            try {
              cell = sharedStrings.get(Integer.parseInt(cell));
            } catch (IllegalArgumentException | IndexOutOfBoundsException ex) {
              throw new IOException("Invalid packing workbook string reference", ex);
            }
          }
          text.append(cell).append(' ');
        }
      } finally {
        reader.close();
      }
    }
    if (text.toString().isBlank()) throw new IOException("No readable packing sheet");
    return text.toString();
  }

  private static String plainText(byte[] content) throws IOException {
    var bytes = ByteBuffer.wrap(content);
    Charset charset = StandardCharsets.UTF_8;
    if (content.length >= 2) {
      int prefix = Short.toUnsignedInt(bytes.getShort(0));
      if (prefix == 0xFEFF || prefix == 0xFFFE) charset = StandardCharsets.UTF_16;
    }
    String text = charset.newDecoder().decode(bytes).toString();
    if (text.startsWith("\uFEFF")) text = text.substring(1);
    if (text.isBlank() || text.indexOf('\0') >= 0) throw new IOException("No readable text");
    return text;
  }

  private static String rtfText(byte[] content) throws IOException {
    if (!new String(content, 0, Math.min(content.length, 5), StandardCharsets.US_ASCII)
        .equals("{\\rtf")) throw new IOException("Invalid RTF attachment");
    var reader = new javax.swing.text.rtf.RTFEditorKit();
    var document = reader.createDefaultDocument();
    try {
      reader.read(new ByteArrayInputStream(content), document, 0);
      String text = document.getText(0, document.getLength());
      if (text.isBlank() || text.length() > MAX_BYTES) throw new IOException("No readable RTF text");
      return text;
    } catch (javax.swing.text.BadLocationException ex) {
      throw new IOException("Invalid RTF attachment", ex);
    }
  }

  private static XMLInputFactory xmlFactory() {
    var factory = XMLInputFactory.newFactory();
    factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
    return factory;
  }

  private static String wordText(byte[] content) throws IOException, XMLStreamException {
    int remaining = MAX_BYTES;
    try (var zip = new ZipInputStream(new ByteArrayInputStream(content))) {
      for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
        byte[] data = zip.readNBytes(remaining + 1);
        if (data.length > remaining) throw new IOException("Oversized expanded Word document");
        remaining -= data.length;
        if (!entry.getName().equals("word/document.xml")) continue;
        var reader = xmlFactory().createXMLStreamReader(new ByteArrayInputStream(data));
        try {
          var text = new StringBuilder();
          while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT && reader.getLocalName().equals("t"))
              text.append(reader.getElementText());
            else if (event == XMLStreamConstants.START_ELEMENT
                && Set.of("br", "cr", "tab").contains(reader.getLocalName()))
              text.append(reader.getLocalName().equals("tab") ? '\t' : '\n');
            else if (event == XMLStreamConstants.END_ELEMENT && reader.getLocalName().equals("p"))
              text.append('\n');
          }
          if (!text.toString().isBlank()) return text.toString();
        } finally {
          reader.close();
        }
        break;
      }
    }
    throw new IOException("No readable Word document");
  }

  private static String readCell(XMLStreamReader reader, String closingTag, String valueTag)
      throws XMLStreamException {
    var value = new StringBuilder();
    while (reader.hasNext()) {
      int event = reader.next();
      if (event == XMLStreamConstants.END_ELEMENT && reader.getLocalName().equals(closingTag))
        break;
      if (event == XMLStreamConstants.START_ELEMENT && reader.getLocalName().equals(valueTag))
        value.append(reader.getElementText());
    }
    return value.toString();
  }

  private static String pdfText(byte[] content, boolean ocr) throws IOException {
    try (var document = Loader.loadPDF(content)) {
      int pages = document.getNumberOfPages();
      if (pages == 0 || pages > 20) throw new IOException("Unsupported packing PDF length");
      var stripper = new PDFTextStripper();
      stripper.setSortByPosition(true);
      var renderer = new PDFRenderer(document);
      renderer.setSubsamplingAllowed(true);
      long stopAt = AttachmentOcr.deadline();
      var text = new StringBuilder();
      for (int page = 1; page <= pages; page++) {
        stripper.setStartPage(page);
        stripper.setEndPage(page);
        String pageText = stripper.getText(document);
        if (pageText.isBlank()) {
          if (!ocr) throw new NeedsOcr();
          var box = document.getPage(page - 1).getCropBox();
          float longestSide = Math.max(box.getWidth(), box.getHeight());
          if (!(longestSide > 0) || System.nanoTime() >= stopAt)
            throw new IOException("PDF scan could not be read in time");
          pageText = AttachmentOcr.read(renderer.renderImage(page - 1,
              Math.min(2.5f, 2_400 / longestSide)), stopAt);
        }
        if (text.length() + pageText.length() > MAX_BYTES)
          throw new IOException("Packing PDF page has no readable text or exceeds the text limit");
        text.append(pageText).append('\n');
      }
      return text.toString();
    }
  }
}
