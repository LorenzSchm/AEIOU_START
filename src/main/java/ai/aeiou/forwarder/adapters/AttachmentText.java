package ai.aeiou.forwarder.adapters;

import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xwpf.usermodel.*;

public final class AttachmentText {
  private AttachmentText() {}

  public static String extract(String filename, byte[] bytes) throws IOException {
    if (bytes.length > 12_000_000) throw new IOException("Attachment exceeds parser limit");
    String name = filename.toLowerCase(Locale.ROOT);
    if (name.endsWith(".pdf")) {
      try (var doc = Loader.loadPDF(bytes)) {
        var stripper = new PDFTextStripper();
        stripper.setSortByPosition(true);
        stripper.setEndPage(Math.min(doc.getNumberOfPages(), 6));
        String text = stripper.getText(doc);
        if (!text.isBlank()) return text;
        var renderer = new PDFRenderer(doc);
        var result = new StringBuilder();
        for (int i = 0; i < Math.min(doc.getNumberOfPages(), 6); i++)
          result.append(ocr(renderer.renderImageWithDPI(i, 150))).append('\n');
        return result.toString();
      }
    }
    if (name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg"))
      return ocr(ImageIO.read(new ByteArrayInputStream(bytes)));
    if (name.endsWith(".docx")) {
      try (var doc = new XWPFDocument(new ByteArrayInputStream(bytes));
          var reader = new org.apache.poi.xwpf.extractor.XWPFWordExtractor(doc)) {
        return reader.getText();
      }
    }
    if (name.endsWith(".xlsx")) {
      try (var book = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
        var text = new StringBuilder();
        var fmt = new DataFormatter(Locale.ROOT);
        for (var sheet : book)
          for (var row : sheet) {
            for (var cell : row) text.append(fmt.formatCellValue(cell)).append(' ');
            text.append('\n');
            if (text.length() > 100_000) return text.toString();
          }
        return text.toString();
      }
    }
    if (name.endsWith(".txt") || name.endsWith(".csv"))
      return new String(bytes, StandardCharsets.UTF_8);
    return "";
  }

  static String ocr(BufferedImage image) throws IOException {
    if (image == null) throw new IOException("Invalid image");
    Path dir = Files.createTempDirectory("freight-ocr-"),
        input = dir.resolve("page.png"),
        output = dir.resolve("text.txt");
    try {
      ImageIO.write(image, "png", input.toFile());
      var process =
          new ProcessBuilder(
                  "tesseract", input.toString(), dir.resolve("text").toString(), "--psm", "6")
              .redirectOutput(ProcessBuilder.Redirect.DISCARD)
              .redirectError(ProcessBuilder.Redirect.DISCARD)
              .start();
      try {
        if (!process.waitFor(20, TimeUnit.SECONDS)) {
          process.destroyForcibly();
          throw new IOException("OCR timeout");
        }
      } catch (InterruptedException e) {
        process.destroyForcibly();
        Thread.currentThread().interrupt();
        throw new IOException(e);
      }
      if (process.exitValue() != 0) throw new IOException("OCR failed");
      return Files.readString(output);
    } finally {
      Files.deleteIfExists(input);
      Files.deleteIfExists(output);
      Files.deleteIfExists(dir);
    }
  }
}
