package ai.aeiou.edgelab.alarm;

import java.io.IOException;
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

    /**
     * Parsed by hand, not with {@link java.net.URI}: real URLs have hosts with underscores and
     * passwords with unencoded '@', '#', '/' or '?', where URI gives a null host. The last '@'
     * ends the user info. Errors name what is wrong but never quote the URL (it holds the password).
     */
    public static DatabaseConfig fromUrl(String databaseUrl) {
        if (databaseUrl == null || databaseUrl.isBlank()) throw new IllegalArgumentException("DATABASE_URL is empty");
        String raw = databaseUrl.trim();
        if (raw.startsWith("jdbc:")) raw = raw.substring(5);
        String rest;
        if (raw.startsWith("postgresql://")) rest = raw.substring("postgresql://".length());
        else if (raw.startsWith("postgres://")) rest = raw.substring("postgres://".length());
        else throw new IllegalArgumentException("DATABASE_URL is not a postgresql:// URL");

        String user = null;
        String password = null;
        int at = rest.lastIndexOf('@');
        if (at >= 0) {
            String userInfo = rest.substring(0, at);
            rest = rest.substring(at + 1);
            int colon = userInfo.indexOf(':');
            user = decode(colon < 0 ? userInfo : userInfo.substring(0, colon));
            password = colon < 0 ? null : decode(userInfo.substring(colon + 1));
            if (user.isEmpty()) user = null;
        }

        int hash = rest.indexOf('#');
        if (hash >= 0) rest = rest.substring(0, hash);
        int end = indexOfAny(rest, '/', '?');
        String hostPort = end < 0 ? rest : rest.substring(0, end);
        String tail = end < 0 ? "" : rest.substring(end);
        String query = null;
        int q = tail.indexOf('?');
        if (q >= 0) {
            query = tail.substring(q + 1);
            tail = tail.substring(0, q);
        }
        String path = tail.isEmpty() ? "/" : tail;

        String host;
        String port = null;
        if (hostPort.startsWith("[")) {
            int close = hostPort.indexOf(']');
            if (close < 0) throw new IllegalArgumentException("DATABASE_URL has an unclosed [IPv6] host");
            host = hostPort.substring(0, close + 1);
            String after = hostPort.substring(close + 1);
            if (after.startsWith(":")) port = after.substring(1);
            else if (!after.isEmpty()) throw new IllegalArgumentException("DATABASE_URL has text after the [IPv6] host");
        } else {
            int colon = hostPort.lastIndexOf(':');
            host = colon < 0 ? hostPort : hostPort.substring(0, colon);
            if (colon >= 0) port = hostPort.substring(colon + 1);
        }
        if (host.isEmpty() || host.equals("[]")) throw new IllegalArgumentException("DATABASE_URL has no host");
        if (port != null && !port.isEmpty() && !port.chars().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException("DATABASE_URL has a port that is not a number");
        }
        if (port != null && port.isEmpty()) port = null;

        String jdbc = "jdbc:postgresql://" + host + (port != null ? ":" + port : "")
                + path + "?" + (query == null || query.isBlank() ? "" : query + "&") + READ_ONLY_OPTION;
        return new DatabaseConfig(jdbc, user, password);
    }

    private static int indexOfAny(String s, char a, char b) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == a || c == b) return i;
        }
        return -1;
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

    /** Percent-decoding as in URLs ('+' stays '+'); a stray '%' is kept as it is. */
    static String decode(String s) {
        if (s.indexOf('%') < 0) return s;
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(s.length());
        for (int i = 0; i < s.length(); ) {
            if (s.charAt(i) == '%' && i + 2 < s.length() && hex(s.charAt(i + 1)) >= 0 && hex(s.charAt(i + 2)) >= 0) {
                out.write(hex(s.charAt(i + 1)) * 16 + hex(s.charAt(i + 2)));
                i += 3;
            } else {
                int cp = s.codePointAt(i);
                out.writeBytes(new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8));
                i += Character.charCount(cp);
            }
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static int hex(char c) {
        return Character.digit(c, 16);
    }

    /** Never print the password. */
    @Override
    public String toString() {
        return "DatabaseConfig[" + jdbcUrl + ", user=" + user + "]";
    }
}
