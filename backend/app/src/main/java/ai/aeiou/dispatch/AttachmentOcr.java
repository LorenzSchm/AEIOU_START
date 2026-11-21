package ai.aeiou.dispatch;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;

/** Local OCR for scanned pages. Time and file size are bounded before text enters the parser. */
final class AttachmentOcr {
  private static final String EXE = executable();

  private static String executable() {
    String configured = System.getenv("DISPATCH_TESSERACT");
    if (configured != null && !configured.isBlank()) return configured;
    for (String path : new String[] {"/opt/homebrew/bin/tesseract", "/usr/local/bin/tesseract",
        "/usr/bin/tesseract"}) {
      if (Files.isExecutable(Path.of(path))) return path;
    }
    return "tesseract";
  }

  static String read(byte[] image, String extension, long stopAt) throws IOException {
    Path input = Files.createTempFile("dispatch-scan-", "." + extension);
    try {
      Files.write(input, image);
      return read(input, stopAt);
    } finally {
      Files.deleteIfExists(input);
    }
  }

  static String read(BufferedImage image, long stopAt) throws IOException {
    Path input = Files.createTempFile("dispatch-scan-", ".png");
    try {
      if (!ImageIO.write(image, "png", input.toFile())) throw new IOException("Cannot render scan");
      return read(input, stopAt);
    } finally {
      Files.deleteIfExists(input);
    }
  }

  private static String read(Path input, long stopAt) throws IOException {
    long remaining = stopAt - System.nanoTime();
    if (remaining <= 0) throw new IOException("Scan deadline exceeded");
    Path output = Files.createTempFile("dispatch-ocr-", ".txt");
    Process process = null;
    try {
      process = new ProcessBuilder(EXE, input.toString(), "stdout")
          .redirectOutput(output.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
      if (!process.waitFor(remaining, TimeUnit.NANOSECONDS) || process.exitValue() != 0
          || Files.size(output) > PackingSafety.MAX_BYTES) throw new IOException("Scan could not be read");
      String text = Files.readString(output, StandardCharsets.UTF_8);
      if (text.isBlank()) throw new IOException("No text found in scan");
      return text;
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new IOException("Scan interrupted", ex);
    } finally {
      if (process != null && process.isAlive()) process.destroyForcibly();
      Files.deleteIfExists(output);
    }
  }

  static long deadline() {
    return System.nanoTime() + Duration.ofSeconds(8).toNanos();
  }
}
