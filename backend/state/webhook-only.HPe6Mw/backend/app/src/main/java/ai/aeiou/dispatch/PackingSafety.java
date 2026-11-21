package ai.aeiou.dispatch;

import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.ZipInputStream;
import javax.xml.stream.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;

final class PackingSafety {
  private static final int MAX_BYTES = 2_000_000;
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
          "(?im)^\\h*(?:dangerous goods|hazmat|gefahrgut)\\h*[:=]?\\h*(?:no|none|nein|n/a|not"
              + " (?:regulated|restricted)|non-dangerous)\\h*[.]?\\h*$|\\b(?:general cargo\\s*,?\\s*non[-"
              + " ]dangerous|non[- ]dangerous\\s+goods)\\b");
  private static final Pattern PACKING =
      Pattern.compile(
          "(?iu)\\b(?:packing (?:list|specification)|shipper.s letter of"
              + " instruction|packliste)\\b");
  private static final Pattern DESTINATION =
      Pattern.compile("(?im)^\\h*destination airport\\h*[:=]?\\h*([A-Z]{3})\\b");
  private static final Pattern GROSS =
      Pattern.compile(
          "(?im)^\\h*total gross weight\\h*\\(kg\\)\\h*[:=]?\\h*([0-9]+(?:[.,][0-9]+)?)\\h*$");
  private static final Pattern VOLUME =
      Pattern.compile(
          "(?im)^\\h*total volume\\h*\\((?:cbm|m3|m³)\\)\\h*[:=]?\\h*([0-9]+(?:[.,][0-9]+)?)\\h*$");
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
        .map(m -> m.group(1).toUpperCase(Locale.ROOT))
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

  static String extract(String filename, byte[] content) throws IOException, XMLStreamException {
    if (content == null || content.length == 0 || content.length > MAX_BYTES)
      throw new IOException("Empty or oversized attachment");
    String name = filename.toLowerCase(Locale.ROOT);
    if (name.endsWith(".txt") || name.endsWith(".csv"))
      return StandardCharsets.UTF_8
          .newDecoder()
          .decode(java.nio.ByteBuffer.wrap(content))
          .toString();
    if (name.endsWith(".pdf")) return pdfText(content);
    if (!name.endsWith(".xlsx")) throw new IOException("Unsupported packing attachment");

    var factory = XMLInputFactory.newFactory();
    factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
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

  private static String pdfText(byte[] content) throws IOException {
    try (var document = Loader.loadPDF(content)) {
      int pages = document.getNumberOfPages();
      if (pages == 0 || pages > 20) throw new IOException("Unsupported packing PDF length");
      var stripper = new PDFTextStripper();
      stripper.setSortByPosition(true);
      var text = new StringBuilder();
      for (int page = 1; page <= pages; page++) {
        stripper.setStartPage(page);
        stripper.setEndPage(page);
        String pageText = stripper.getText(document);
        if (pageText.isBlank() || text.length() + pageText.length() > MAX_BYTES)
          throw new IOException("Packing PDF page has no readable text or exceeds the text limit");
        text.append(pageText).append('\n');
      }
      return text.toString();
    }
  }
}
