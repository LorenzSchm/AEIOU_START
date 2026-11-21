package ai.aeiou.forwarder.api;

import java.time.OffsetDateTime;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Terse SQL seeding for API tests. Returns generated ids. */
public record TestData(JdbcClient jdbc) {

    public long session(String ref, String type, String status, OffsetDateTime lastEventAt, boolean needsHuman) {
        return jdbc.sql("""
                        INSERT INTO session (public_ref, type, status, customer_addr, subject, needs_human,
                                             needs_human_reason, last_event_at, last_event_summary, created_at)
                        VALUES (:ref, :type, :status, 'anna@customer.test', 'Quote ' || :ref, :nh,
                                CASE WHEN :nh THEN 'low confidence' END, :at, 'last event', :at)
                        RETURNING id""")
                .param("ref", ref).param("type", type).param("status", status).param("nh", needsHuman)
                .param("at", lastEventAt)
                .query(Long.class).single();
    }

    public void rfq(long sessionId, String origin, String destination) {
        jdbc.sql("""
                        INSERT INTO rfq (session_id, origin_airport, destination, weight_kg, volume_cbm, mode)
                        VALUES (:id, :o, :d, 120.5, 0.8, 'cheapest')""")
                .param("id", sessionId).param("o", origin).param("d", destination).update();
    }

    public long event(Long sessionId, String kind, String payloadJson) {
        return jdbc.sql("""
                        INSERT INTO event (session_id, kind, actor, summary, payload)
                        VALUES (:sid, :kind, 'system', :kind || ' happened', CAST(:payload AS jsonb)) RETURNING id""")
                .param("sid", sessionId).param("kind", kind).param("payload", payloadJson)
                .query(Long.class).single();
    }

    public long mail(long externalId, String direction, Long sessionId, String subject, String body, OffsetDateTime sentAt) {
        return jdbc.sql("""
                        INSERT INTO mail (external_id, direction, from_addr, to_addr, subject, body, session_id, sent_at,
                                          references_ext, thread_root_ext)
                        VALUES (:ext, :dir,
                                CASE WHEN :dir = 'in' THEN 'anna@customer.test' ELSE 'desk@aeiou.test' END,
                                CASE WHEN :dir = 'in' THEN 'desk@aeiou.test' ELSE 'anna@customer.test' END,
                                :subject, :body, :sid, :at, '{}', :ext)
                        RETURNING id""")
                .param("ext", externalId).param("dir", direction).param("sid", sessionId)
                .param("subject", subject).param("body", body).param("at", sentAt)
                .query(Long.class).single();
    }

    public long attachment(long mailId, String filename, String contentType, byte[] content) {
        return jdbc.sql("""
                        INSERT INTO attachment (mail_id, filename, content_type, size, content)
                        VALUES (:mail, :name, :type, :size, :content) RETURNING id""")
                .param("mail", mailId).param("name", filename).param("type", contentType)
                .param("size", (long) content.length).param("content", content)
                .query(Long.class).single();
    }

    public void airport(String iata, String city, double lat, double lon) {
        jdbc.sql("INSERT INTO airport (iata, city, lat, lon) VALUES (:i, :c, :lat, :lon)")
                .param("i", iata).param("c", city).param("lat", lat).param("lon", lon).update();
    }

    public long booking(long sessionId, String origin, String destination, String status) {
        return jdbc.sql("""
                        INSERT INTO booking (session_id, team_reference, booking_reference, airline_name, origin,
                                             destination, price, currency, transit_days, status)
                        VALUES (:sid, 'TEAM-' || :sid, 'BK-' || :sid || :status, 'Helix Air', :o, :d, 1387.354, 'EUR',
                                4, :status) RETURNING id""")
                .param("sid", sessionId).param("o", origin).param("d", destination).param("status", status)
                .query(Long.class).single();
    }

    public void draft(long sessionId) {
        jdbc.sql("""
                        INSERT INTO draft (session_id, purpose, to_addr, subject, body, in_reply_to_ext)
                        VALUES (:sid, 'quote', 'anna@customer.test', 'Re: Quote', 'Dear Anna, ...', 1001)""")
                .param("sid", sessionId).update();
    }
}
