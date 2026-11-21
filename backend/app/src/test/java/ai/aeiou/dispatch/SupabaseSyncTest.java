package ai.aeiou.dispatch;

import static org.junit.jupiter.api.Assertions.*;

import ai.aeiou.freight.Catalog;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.StringReader;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class SupabaseSyncTest {
  @TempDir Path temp;
  final ObjectMapper json = new ObjectMapper();
  final List<String[]> requests = Collections.synchronizedList(new ArrayList<>());
  HttpServer server;
  int status = 201;

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        ex -> {
          requests.add(
              new String[] {
                ex.getRequestURI().toString(),
                ex.getRequestHeaders().getFirst("apikey"),
                ex.getRequestHeaders().getFirst("Prefer"),
                new String(ex.getRequestBody().readAllBytes())
              });
          ex.sendResponseHeaders(status, -1);
          ex.close();
        });
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  @Test
  void pushesDirectoryOnceAndOnlyChangedBids() throws Exception {
    var ledger = new Ledger(temp.resolve("d.sqlite3").toString(), json);
    var catalog =
        new Catalog(
            new StringReader("h\n"),
            new StringReader(
                "name,kind,email,serves\n"
                    + "Lumen Air Cargo,airline,lumen@hackloop.mail,DFW ORD\n"
                    + "Rhein Trucking,trucker,rhein@hackloop.mail,51 50\n"),
            new StringReader("h\n"));
    var db = ledger.jdbc();
    db.update(
        "INSERT INTO mail(id,root,sender,subject,received,kind) VALUES(10,10,'buyer@x','RFQ to"
            + " DFW','2026-11-21T15:00:00Z','request')");
    db.update(
        "INSERT INTO tender(request_id,email,leg,terms) VALUES(10,'lumen@hackloop.mail','airline',"
            + "'{\"origin\":\"CGN\",\"destination\":\"DFW\",\"weight_kg\":1795}')");
    db.update(
        "INSERT INTO deliveries(action,state,response,attempted_at) VALUES("
            + "'bid:10:lumen@hackloop.mail','sent','{\"id\":11}','2026-11-21T15:00:02Z')");
    var sync =
        new SupabaseSync(ledger, catalog, "http://127.0.0.1:" + server.getAddress().getPort() + "/", "sb_secret_x");

    sync.sync();
    assertEquals(2, requests.size());
    assertEquals("/rest/v1/carriers?on_conflict=email", requests.get(0)[0]);
    assertEquals("sb_secret_x", requests.get(0)[1]);
    assertTrue(requests.get(0)[2].contains("resolution=merge-duplicates"));
    var carriers = json.readTree(requests.get(0)[3]);
    assertEquals(2, carriers.size());
    assertEquals("DFW", carriers.get(0).path("serves").get(0).asText());
    assertEquals("trucker", carriers.get(1).path("kind").asText());
    assertEquals("/rest/v1/carrier_bids?on_conflict=request_id,carrier_email", requests.get(1)[0]);
    var bid = json.readTree(requests.get(1)[3]).get(0);
    assertEquals("invited", bid.path("status").asText());
    assertEquals(11, bid.path("invite_message_id").asLong());
    assertEquals("DFW", bid.path("destination").asText());
    assertTrue(bid.path("price_eur").isNull());

    sync.sync();
    assertEquals(2, requests.size(), "unchanged rows are not resent");

    db.update(
        "UPDATE tender SET reply_id=12,euros='5819.38',days=1 WHERE request_id=10");
    sync.sync();
    assertEquals(3, requests.size());
    bid = json.readTree(requests.get(2)[3]).get(0);
    assertEquals("priced", bid.path("status").asText());
    assertEquals("5819.38", bid.path("price_eur").asText());
  }

  @Test
  void failedPushIsRetriedAndNeverThrows() throws Exception {
    var ledger = new Ledger(temp.resolve("d.sqlite3").toString(), json);
    var catalog =
        new Catalog(
            new StringReader("h\n"),
            new StringReader("name,kind,email,serves\nA,airline,a@x,DFW\n"),
            new StringReader("h\n"));
    var sync =
        new SupabaseSync(ledger, catalog, "http://127.0.0.1:" + server.getAddress().getPort(), "k");
    status = 500;
    assertDoesNotThrow(sync::sync);
    status = 201;
    sync.sync();
    assertEquals(2, requests.size(), "carriers are pushed again after a failure");
  }

  @Test
  void disabledWithoutCredentials() throws Exception {
    var ledger = new Ledger(temp.resolve("d.sqlite3").toString(), json);
    var catalog = new Catalog(new StringReader("h\n"), new StringReader("h\n"), new StringReader("h\n"));
    new SupabaseSync(ledger, catalog, "", "").sync();
    assertTrue(requests.isEmpty());
  }
}
