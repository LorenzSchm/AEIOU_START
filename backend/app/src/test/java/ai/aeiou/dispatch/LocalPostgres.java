package ai.aeiou.dispatch;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A throwaway Postgres server for tests: initdb into a temporary directory and start it on a free
 * local port, TCP only. Uses the binaries in {@code PG_BIN} or a usual install location and returns
 * null when none is found, so callers can skip.
 */
final class LocalPostgres implements AutoCloseable {
  private static final AtomicInteger databases = new AtomicInteger();
  private final Path bin;
  private final Path data;
  private final int port;

  private LocalPostgres(Path bin, Path data, int port) {
    this.bin = bin;
    this.data = data;
    this.port = port;
  }

  static LocalPostgres start() throws Exception {
    Path bin = binaries();
    if (bin == null) return null;
    Path dir = Files.createTempDirectory("aeiou-pg");
    Path data = dir.resolve("data");
    run(bin.resolve("initdb").toString(), "-D", data.toString(), "-U", "test", "-A", "trust", "-E", "UTF8",
        "--no-sync");
    int port;
    try (var socket = new ServerSocket(0)) {
      port = socket.getLocalPort();
    }
    run(bin.resolve("pg_ctl").toString(), "-D", data.toString(), "-w", "-l", dir.resolve("log").toString(), "-o",
        "-p " + port + " -k '' -c listen_addresses=127.0.0.1 -c fsync=off", "start");
    return new LocalPostgres(bin, data, port);
  }

  /** A fresh, empty database on this server, as a postgresql:// URL. */
  String newDatabase() throws SQLException {
    String name = "t" + databases.incrementAndGet();
    try (var c = DriverManager.getConnection("jdbc:postgresql://127.0.0.1:" + port + "/postgres", "test", "");
        var st = c.createStatement()) {
      st.execute("CREATE DATABASE " + name);
    }
    return "postgresql://test@127.0.0.1:" + port + "/" + name;
  }

  @Override
  public void close() throws Exception {
    run(bin.resolve("pg_ctl").toString(), "-D", data.toString(), "-m", "immediate", "-w", "stop");
  }

  private static Path binaries() {
    var candidates = new ArrayList<Path>();
    Optional.ofNullable(System.getenv("PG_BIN")).ifPresent(p -> candidates.add(Path.of(p)));
    for (String v : List.of("18", "17", "16", "15", "14"))
      for (String root : List.of("/opt/homebrew/opt/postgresql@", "/usr/local/opt/postgresql@"))
        candidates.add(Path.of(root + v + "/bin"));
    for (String v : List.of("18", "17", "16", "15", "14")) candidates.add(Path.of("/usr/lib/postgresql/" + v + "/bin"));
    for (String p : System.getenv().getOrDefault("PATH", "").split(":")) if (!p.isEmpty()) candidates.add(Path.of(p));
    for (var dir : candidates)
      if (Files.isExecutable(dir.resolve("postgres")) && Files.isExecutable(dir.resolve("initdb"))
          && Files.isExecutable(dir.resolve("pg_ctl"))) return dir;
    return null;
  }

  private static void run(String... command) throws IOException, InterruptedException {
    var process = new ProcessBuilder(command).redirectErrorStream(true).start();
    String out = new String(process.getInputStream().readAllBytes());
    if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0)
      throw new IOException(String.join(" ", command) + " failed: " + out);
  }
}
