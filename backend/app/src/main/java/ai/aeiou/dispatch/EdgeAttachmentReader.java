package ai.aeiou.dispatch;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

@FunctionalInterface
interface EdgeAttachmentReader {
  String read(String filename, byte[] bytes) throws IOException, InterruptedException;

  static EdgeAttachmentReader fromLibrary(String library) {
    if (library == null || library.isBlank()) return null;
    var log = org.slf4j.LoggerFactory.getLogger(EdgeAttachmentReader.class);
    String classpath;
    try {
      Path dir = Path.of(library).toAbsolutePath();
      try (var jars = Files.newDirectoryStream(dir, "edgelab-*.jar")) {
        if (!jars.iterator().hasNext()) throw new IOException("missing reader JAR");
      }
      classpath = dir + java.io.File.separator + "*";
    } catch (IOException | java.nio.file.InvalidPathException unavailable) {
      log.warn("Edge reader library unavailable. Using the standard attachment workflow.");
      return null;
    }
    return (filename, bytes) -> {
      Path dir = Files.createTempDirectory("dispatch-edge-");
      Path input = dir.resolve("input");
      Path output = dir.resolve("output");
      Process child = null;
      try {
        Files.write(input, bytes);
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        child = new ProcessBuilder(java, "-Xmx384m", "-Djava.awt.headless=true", "-cp",
            classpath, "ai.aeiou.edgelab.WorkerRead", filename, input.toString(), output.toString())
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        if (!child.waitFor(30, TimeUnit.SECONDS)) {
          log.warn("Attachment reader exceeded its 30-second deadline.");
          return null;
        }
        if (child.exitValue() != 0 || !Files.isRegularFile(output) || Files.size(output) > 400_000) return null;
        return Files.readString(output);
      } finally {
        if (child != null && child.isAlive()) {
          child.destroyForcibly();
          child.waitFor(2, TimeUnit.SECONDS);
        }
        Files.deleteIfExists(output);
        Files.deleteIfExists(input);
        Files.deleteIfExists(dir);
      }
    };
  }
}
