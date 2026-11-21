import java.sql.*;
import java.util.Properties;

/** Checks a previously migrated forwarder database in one read-only PostgreSQL snapshot. */
class StandPruefen {
    public static void main(String[] args) {
        Properties p = new Properties();
        p.setProperty("user", System.getenv("DATABASE_USER"));
        p.setProperty("password", System.getenv("DATABASE_PASSWORD"));
        p.setProperty("connectTimeout", "5");
        p.setProperty("socketTimeout", "10");
        try (Connection db = DriverManager.getConnection(System.getenv("DATABASE_URL"), p)) {
            db.setReadOnly(true);
            db.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            db.setAutoCommit(false);
            try (Statement s = db.createStatement()) {
                s.execute("SET TRANSACTION READ ONLY");
                long cursor;
                try (ResultSet r = s.executeQuery("select current_schema(),mail_cursor,demo_seconds from stream_clock where id=1")) {
                    if (!r.next() || r.getLong(3) != 0 || "demo".equalsIgnoreCase(r.getString(1))) {
                        throw new IllegalStateException("Kein geeigneter Live-Stand.");
                    }
                    cursor = r.getLong(2);
                    System.out.printf("Schema=%s, Mail-Cursor=%d%n",r.getString(1),cursor);
                }
                try (ResultSet r = s.executeQuery("select version, success from flyway_schema_history where version is not null order by installed_rank desc limit 1")) {
                    if (!r.next() || !"4".equals(r.getString(1)) || !r.getBoolean(2)) {
                        throw new IllegalStateException("Schema entspricht nicht dem geprüften Übergabestand V4.");
                    }
                }
                try (ResultSet r = s.executeQuery("select count(*) from inbox where processed=false")) {
                    r.next(); System.out.println("Unverarbeitete Inbox-Einträge: " + r.getLong(1));
                }
                try (ResultSet r = s.executeQuery("select state,count(*) from job where state in ('READY','RUNNING','UNCERTAIN','FAILED') group by state order by state")) {
                    while(r.next()) System.out.printf("Jobs %s: %d%n",r.getString(1),r.getLong(2));
                }
                if (args.length == 1 && cursor != Long.parseLong(args[0])) {
                    throw new IllegalStateException("Mail-Cursor weicht vom übergebenen Stand ab.");
                }
            } finally { db.rollback(); }
        } catch (SQLException e) {
            System.err.println("PostgreSQL-Zugriff oder Schema prüfen (SQLSTATE " + e.getSQLState() + ").");
            System.exit(1);
        } catch (IllegalStateException e) {
            System.err.println(e.getMessage());
            System.exit(1);
        }
    }
}
