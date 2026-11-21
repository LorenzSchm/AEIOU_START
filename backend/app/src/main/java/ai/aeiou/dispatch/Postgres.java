package ai.aeiou.dispatch;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;

/** Opens Postgres connections from a postgresql://user:password@host:port/db URL or a JDBC URL. */
final class Postgres {
  private final String url;
  private final Properties props = new Properties();

  Postgres(String url) {
    var uri = URI.create(url.replaceFirst("^jdbc:", "").replaceFirst("^postgres:", "postgresql:"));
    String[] info = Optional.ofNullable(uri.getRawUserInfo()).orElse("").split(":", 2);
    if (!info[0].isEmpty()) props.setProperty("user", URLDecoder.decode(info[0], StandardCharsets.UTF_8));
    if (info.length > 1) props.setProperty("password", URLDecoder.decode(info[1], StandardCharsets.UTF_8));
    props.setProperty("connectTimeout", "5");
    props.setProperty("socketTimeout", "30");
    this.url =
        "jdbc:postgresql://" + uri.getHost() + (uri.getPort() < 0 ? "" : ":" + uri.getPort())
            + uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
  }

  static Postgres of(String url) {
    return url == null || url.isBlank() ? null : new Postgres(url);
  }

  Connection open() throws SQLException {
    return DriverManager.getConnection(url, props);
  }

  static void close(Connection c) {
    try {
      if (c != null) c.close();
    } catch (SQLException ignored) {
    }
  }
}
