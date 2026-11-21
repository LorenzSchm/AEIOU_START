package ai.aeiou.edgelab.alarm;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlarmPartsTest {

    @Test
    void databaseUrlBecomesAReadOnlyJdbcSessionWithoutLeakingThePassword() {
        DatabaseConfig c = DatabaseConfig.fromUrl("postgresql://bob:s%40cret@db.example:5433/mirror?sslmode=require");

        assertEquals("jdbc:postgresql://db.example:5433/mirror?sslmode=require&"
                + "options=-c%20default_transaction_read_only%3Don", c.jdbcUrl());
        assertEquals("bob", c.user());
        assertEquals("s@cret", c.password());
        assertFalse(c.toString().contains("s@cret"));
    }

    @Test
    void databaseUrlIsReadFromDotenvLines() {
        List<String> lines = List.of("# comment", "TEAM_API_KEY=x", "DATABASE_URL=\"postgresql://u:p@h:1/d\"");
        assertEquals("postgresql://u:p@h:1/d", DatabaseConfig.readKey(lines, "DATABASE_URL").orElseThrow());
        assertTrue(DatabaseConfig.readKey(lines, "MISSING").isEmpty());
    }

    @Test
    void suggestedReaderFollowsTheFileType() {
        assertEquals("voice reader", Readers.suggest("mp3", "audio/mpeg"));
        assertEquals("photo reader", Readers.suggest("heic", null));
        assertEquals("video reader", Readers.suggest("mp4", "video/mp4"));
        assertEquals("container walker", Readers.suggest("zip", "application/zip"));
        assertEquals("office reader (Tika)", Readers.suggest("pptx", null));
        assertEquals("plain text reader", Readers.suggest("txt", "text/plain"));
        assertEquals("no reader fits yet - new reader needed", Readers.suggest("xyz", "x-weird/thing"));
    }
}
