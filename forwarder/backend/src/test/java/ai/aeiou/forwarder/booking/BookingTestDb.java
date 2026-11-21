package ai.aeiou.forwarder.booking;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** One migrated Postgres (Testcontainers) shared by the booking and tracking tests, plus fixture inserts. */
public final class BookingTestDb {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");
    private static JdbcClient jdbc;

    private BookingTestDb() {}

    public static synchronized JdbcClient jdbc() {
        if (jdbc == null) {
            POSTGRES.start();
            DriverManagerDataSource ds = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
            Flyway.configure().dataSource(ds).load().migrate();
            jdbc = JdbcClient.create(ds);
        }
        return jdbc;
    }

    public static void reset() {
        jdbc().sql("TRUNCATE draft, booking, price_candidate, bid_request, rfq, event, mail, session, carrier, airport RESTART IDENTITY CASCADE").update();
        jdbc().sql("INSERT INTO airport (iata, city, lat, lon) VALUES ('FRA', 'Frankfurt', 50.0, 8.5), ('SIN', 'Singapore', 1.3, 103.9)").update();
        jdbc().sql("INSERT INTO carrier (name, code, kind, email) VALUES ('Helix Air Freight', 'HX', 'airline', 'helix-air-freight@hackloop.mail'), "
                + "('Kestrel Airways', NULL, 'airline', 'kestrel-airways@hackloop.mail')").update();
    }

    public static long session(String publicRef, String customer, String subject) {
        return jdbc().sql("INSERT INTO session (public_ref, type, status, customer_addr, subject) VALUES (?, 'RFQ', 'QUOTED', ?, ?) RETURNING id")
                .param(publicRef).param(customer).param(subject).query(Long.class).single();
    }

    public static void rfq(long sessionId, LocalDate readyDate, String language, String customerName) {
        jdbc().sql("""
                        INSERT INTO rfq (session_id, origin_airport, destination, weight_kg, volume_cbm, pieces, ready_date, commodity, language, customer_name)
                        VALUES (?, 'FRA', 'SIN', 250, 1.2, 3, ?, 'machine parts', ?, ?)""")
                .param(sessionId).param(readyDate).param(language).param(customerName).update();
    }

    public static void candidate(long sessionId, String source, String airlineCode, String airlineName, String total,
                                 String rateId, OffsetDateTime validUntil) {
        jdbc().sql("""
                        INSERT INTO price_candidate (session_id, source, airline_code, airline_name, total, currency, total_eur,
                                                     transit_days, rate_id, valid_until, chosen)
                        VALUES (?, ?, ?, ?, ?, 'EUR', ?, 4, ?, ?, true)""")
                .param(sessionId).param(source).param(airlineCode).param(airlineName)
                .param(new BigDecimal(total)).param(new BigDecimal(total)).param(rateId).param(validUntil).update();
    }

    public static long mail(long externalId, long sessionId, String direction, String from, String subject, String body) {
        return jdbc().sql("""
                        INSERT INTO mail (external_id, direction, from_addr, to_addr, subject, body, thread_root_ext, session_id, sent_at)
                        VALUES (?, ?, ?, 'aeiou@hackloop.mail', ?, ?, ?, ?, now()) RETURNING id""")
                .param(externalId).param(direction).param(from).param(subject).param(body).param(externalId).param(sessionId)
                .query(Long.class).single();
    }

    public static long booking(long sessionId, String teamRef, String bookingRef, String status,
                               OffsetDateTime departure, OffsetDateTime eta) {
        return jdbc().sql("""
                        INSERT INTO booking (session_id, rate_id, team_reference, booking_reference, airline_code, airline_name,
                                             origin, destination, price, currency, transit_days, status, departure_at, eta_at)
                        VALUES (?, 'R-abcdefgh12', ?, ?, 'HX', 'Helix Air Freight', 'FRA', 'SIN', 1387.35, 'EUR', 4, ?, ?, ?)
                        RETURNING id""")
                .param(sessionId).param(teamRef).param(bookingRef).param(status).param(departure).param(eta)
                .query(Long.class).single();
    }
}
