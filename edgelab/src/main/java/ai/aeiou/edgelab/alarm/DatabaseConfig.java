package ai.aeiou.edgelab.alarm;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Turns DATABASE_URL (postgresql://user:password@host:port/db) into a JDBC URL that forces
 * a read-only session. The URL comes from the environment or from the repo-root .env.
 */
public record DatabaseConfig(String jdbcUrl, String user, String password) {

    /** Every session starts read-only: the server rejects any write in it. */
    static final String READ_ONLY_OPTION = "options=-c%20default_transaction_read_only%3Don";

    public static DatabaseConfig fromUrl(String databaseUrl) {
        if (databaseUrl == null || databaseUrl.isBlank()) throw new IllegalArgumentException("DATABASE_URL is empty");
        String raw = databaseUrl.trim();
        if (raw.startsWith("jdbc:")) raw = raw.substring(5);
        URI uri = URI.create(raw.replaceFirst("^postgres(ql)?://", "postgresql://"));
        if (!"postgresql".equals(uri.getScheme())) {
            throw new IllegalArgumentException("DATABASE_URL is not a postgresql:// URL");
        }
        String user = null;
        String password = null;
        if (uri.getRawUserInfo() != null) {
            String[] up = uri.getRawUserInfo().split(":", 2);
            user = decode(up[0]);
            password = up.length > 1 ? decode(up[1]) : null;
        }
        String query = uri.getRawQuery();
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        String jdbc = "jdbc:postgresql://" + uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "")
                + path + "?" + (query == null || query.isBlank() ? "" : query + "&") + READ_ONLY_OPTION;
        return new DatabaseConfig(jdbc, user, password);
    }

    /** DATABASE_URL from the environment, else from the first .env found walking up from {@code start}. */
    public static DatabaseConfig load(Path start) throws IOException {
        String env = System.getenv("DATABASE_URL");
        if (env != null && !env.isBlank()) return fromUrl(env);
        for (Path dir = start.toAbsolutePath(); dir != null; dir = dir.getParent()) {
            Path dotenv = dir.resolve(".env");
            if (Files.isRegularFile(dotenv)) {
                Optional<String> url = readKey(Files.readAllLines(dotenv, StandardCharsets.UTF_8), "DATABASE_URL");
                if (url.isPresent() && !url.get().isBlank()) return fromUrl(url.get());
            }
        }
        throw new IllegalStateException("DATABASE_URL not found in the environment or in a .env file");
    }

    static Optional<String> readKey(List<String> lines, String key) {
        for (String line : lines) {
            String l = line.trim();
            if (l.startsWith("export ")) l = l.substring(7).trim();
            if (!l.startsWith(key + "=")) continue;
            String v = l.substring(key.length() + 1).trim();
            boolean quoted = v.length() >= 2
                    && (v.startsWith("\"") && v.endsWith("\"") || v.startsWith("'") && v.endsWith("'"));
            if (quoted) v = v.substring(1, v.length() - 1);
            return Optional.of(v);
        }
        return Optional.empty();
    }

    private static String decode(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    /** Never print the password. */
    @Override
    public String toString() {
        return "DatabaseConfig[" + jdbcUrl + ", user=" + user + "]";
    }
}
