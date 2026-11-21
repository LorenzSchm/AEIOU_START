package ai.aeiou.dispatch;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

class ReadOnlyModeTest {
  @TempDir Path temp;

  @Test
  void readOnlyDeskNeverWrites() {
    assertFalse(new WriterLease(true, "postgresql://u:p@127.0.0.1:1/db").mayWrite());
    assertTrue(new WriterLease(false, "").mayWrite());
  }

  @Test
  void readOnlyDeskNeverCallsFiveU() throws Exception {
    var ledger = new Ledger(temp.resolve("dispatch.sqlite3").toString(), new ObjectMapper());
    var upstream = new FiveU("http://127.0.0.1:1", "key", new MarketMemory(ledger), true);
    var refused = assertThrows(ResponseStatusException.class, () -> upstream.get("/v1/board/me"));
    assertEquals(403, refused.getStatusCode().value());
  }

  @Test
  void readOnlyDeskKeepsItsReplicaApartFromTheWorkerDatabase() throws Exception {
    new Ledger(temp.resolve("dispatch.sqlite3").toString(), new ObjectMapper(), true);
    assertTrue(Files.exists(temp.resolve("dispatch.replica.sqlite3")));
    assertFalse(Files.exists(temp.resolve("dispatch.sqlite3")));
  }
}
