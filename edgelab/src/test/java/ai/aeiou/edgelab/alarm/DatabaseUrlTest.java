package ai.aeiou.edgelab.alarm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** DATABASE_URL shapes that java.net.URI gets wrong. Fake credentials only. */
class DatabaseUrlTest {

    private static final String RO = "options=-c%20default_transaction_read_only%3Don";

    @Test
    void hostWithUnderscoreKeepsItsName() {
        DatabaseConfig c = DatabaseConfig.fromUrl("postgres://user:pw@db_mirror_1:5432/mirror");
        assertEquals("jdbc:postgresql://db_mirror_1:5432/mirror?" + RO, c.jdbcUrl());
        assertEquals("user", c.user());
        assertEquals("pw", c.password());
    }

    @Test
    void unencodedAtHashSlashAndQuestionMarkInThePassword() {
        DatabaseConfig c = DatabaseConfig.fromUrl("postgresql://bob:p@ss#w/rd?x@db.example.com/mirror?sslmode=require");
        assertEquals("jdbc:postgresql://db.example.com/mirror?sslmode=require&" + RO, c.jdbcUrl());
        assertEquals("bob", c.user());
        assertEquals("p@ss#w/rd?x", c.password());
    }

    @Test
    void percentEncodedPartsAreDecodedAndPlusStaysPlus() {
        DatabaseConfig c = DatabaseConfig.fromUrl("jdbc:postgresql://b%C3%B6b:a+b%2Fc%@h/d");
        assertEquals("böb", c.user());
        assertEquals("a+b/c%", c.password());
        assertEquals("jdbc:postgresql://h/d?" + RO, c.jdbcUrl());
    }

    @Test
    void noUserNoPortNoPath() {
        DatabaseConfig c = DatabaseConfig.fromUrl("postgresql://localhost");
        assertEquals("jdbc:postgresql://localhost/?" + RO, c.jdbcUrl());
        assertNull(c.user());
        assertNull(c.password());
    }

    @Test
    void ipv6HostWithPort() {
        DatabaseConfig c = DatabaseConfig.fromUrl("postgresql://u:p@[::1]:6543/db");
        assertEquals("jdbc:postgresql://[::1]:6543/db?" + RO, c.jdbcUrl());
    }

    @Test
    void badUrlsFailClearlyWithoutThePassword() {
        String secret = "hunter2-SECRET";
        for (String url : new String[] {
                "postgresql://u:" + secret + "@/db",
                "postgresql://u:" + secret + "@host:notaport/db",
                "mysql://u:" + secret + "@host/db",
                "postgresql://u:" + secret + "@[::1/db"}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> DatabaseConfig.fromUrl(url), url);
            assertFalse(e.getMessage().contains(secret), e.getMessage());
            assertFalse(e.getMessage().contains("null"), e.getMessage());
        }
    }
}
