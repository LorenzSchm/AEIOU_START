package ai.aeiou.dispatch;

import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

@EnabledIfSystemProperty(named = "test.edge.lib", matches = ".+")
class EdgeReaderProcessTest {
  @TempDir Path temp;
  private final Path fixtures = Path.of("../../edgelab/src/test/resources").toAbsolutePath();

  @Test void gatewayNeedsWorkerOptInEvenWithAnInheritedFlagAndRepoKey() throws Exception {
    Path image = fixtures.resolve("photo/danube-wings-cargo-tariff-2026-11-p1.jpg");
    Files.writeString(temp.resolve(".env"), "TEAM_API_KEY=test-only");
    Path work = Files.createDirectory(temp.resolve("worker"));
    var requests = new AtomicInteger();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      requests.incrementAndGet();
      exchange.sendResponseHeaders(503, -1);
      exchange.close();
    });
    server.start();
    try {
      for (boolean allow : new boolean[]{false, true}) {
        var command = command(image, allow).directory(work.toFile());
        command.environment().remove("TEAM_API_KEY");
        command.environment().remove("EDGELAB_ENV_FILE");
        command.environment().put("EDGELAB_GATEWAY_ENABLED", "true");
        command.environment().put("EDGELAB_GATEWAY_URL", "http://127.0.0.1:" + server.getAddress().getPort());
        assertEquals(allow ? 75 : 0, run(command));
        assertEquals(allow ? 1 : 0, requests.get());
      }
    } finally {
      server.stop(0);
    }
  }

  @Test void missingModelIsRetryableAndNeverDownloadedDuringAnAttachmentRead() throws Exception {
    var command = command(fixtures.resolve("voice/rfq-en.mp3"), false);
    Path cache = temp.resolve("empty-model-cache");
    command.environment().put("EDGELAB_WHISPER_DIR", cache.toString());
    assertEquals(75, run(command));
    assertFalse(Files.exists(cache));
  }

  private ProcessBuilder command(Path input, boolean allow) {
    assertTrue(Files.isRegularFile(input));
    String cp = System.getProperty("test.edge.lib") + java.io.File.separator + "*";
    return EdgeAttachmentReader.command(cp, allow, input.getFileName().toString(), input, temp.resolve("output"));
  }

  private static int run(ProcessBuilder command) throws Exception {
    Process child = command.start();
    try {
      assertTrue(child.waitFor(30, TimeUnit.SECONDS));
      return child.exitValue();
    } finally {
      child.destroyForcibly();
    }
  }
}
