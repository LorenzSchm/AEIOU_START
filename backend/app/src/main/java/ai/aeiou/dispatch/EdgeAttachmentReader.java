package ai.aeiou.dispatch;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

@FunctionalInterface
interface EdgeAttachmentReader {
  String read(String filename, byte[] bytes) throws IOException, InterruptedException;

  static EdgeAttachmentReader fromLibrary(String library, boolean allowGateway) {
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
        child = command(classpath, allowGateway, filename, input, output).start();
        if (!child.waitFor(30, TimeUnit.SECONDS)) {
          throw new java.net.SocketTimeoutException("attachment reader exceeded 30 seconds");
        }
        if (child.exitValue() != 0) {
          log.warn("Attachment reader exited with code {}.", child.exitValue());
          throw new IOException("reader exit " + child.exitValue());
        }
        if (!Files.isRegularFile(output)) return null;
        if (Files.size(output) > 400_000) throw new IOException("invalid reader output");
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

  static ProcessBuilder command(String classpath, boolean allowGateway, String filename, Path input, Path output) {
    String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    return new ProcessBuilder(java, "-Xmx384m", "-Djava.awt.headless=true",
        "-Dedgelab.gateway.enabled=" + allowGateway, "-cp", classpath,
        "ai.aeiou.edgelab.WorkerRead", filename, input.toString(), output.toString())
        .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
  }
}
