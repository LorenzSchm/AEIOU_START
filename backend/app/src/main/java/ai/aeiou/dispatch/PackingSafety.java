package ai.aeiou.dispatch;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
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
    var text = new StringBuilder();
    boolean hasSheet = false;
    int remaining = MAX_BYTES;
    try (var zip = new ZipInputStream(new ByteArrayInputStream(content))) {
      for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
        byte[] data = zip.readNBytes(remaining + 1);
        if (data.length > remaining) throw new IOException("Oversized expanded workbook");
        remaining -= data.length;
        String path = entry.getName();
        boolean sheet = path.matches("xl/worksheets/sheet[0-9]+\\.xml");
        if (!sheet && !path.equals("xl/sharedStrings.xml")) continue;
        hasSheet |= sheet;
        var reader = factory.createXMLStreamReader(new ByteArrayInputStream(data));
        try {
          while (reader.hasNext()) {
            if (reader.next() == XMLStreamConstants.START_ELEMENT) {
              String tag = reader.getLocalName();
              if (tag.equals("t") || tag.equals("v"))
                text.append(reader.getElementText()).append('\n');
            }
          }
        } finally {
          reader.close();
        }
      }
    }
    if (!hasSheet || text.toString().isBlank()) throw new IOException("No readable packing sheet");
    return text.toString();
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
