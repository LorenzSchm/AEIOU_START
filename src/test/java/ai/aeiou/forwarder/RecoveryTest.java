package ai.aeiou.forwarder;

import static ai.aeiou.forwarder.domain.Json.*;
import static org.junit.jupiter.api.Assertions.*;

import ai.aeiou.forwarder.application.*;
import ai.aeiou.forwarder.infrastructure.Ledger;
import java.sql.Timestamp;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:recovery;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
      "forwarder.demo.tick-ms=600000"
    })
class RecoveryTest {
  @Autowired Ledger store;
  @Autowired Workflow flow;
  @Autowired TransactionTemplate tx;
  @Autowired Worker worker;

  @Test
  void expiredSendLeaseRemainsUncertainUntilAcceptance() {
    tx.executeWithoutResult(
        t -> {
          store.lock();
          var d = store.shipment("s_100");
          var at = flow.clock.now();
          flow.decide(d, at);
          store.db.update(
              "update job set state='RUNNING', attempts=1,due_at=? where id='s_100:quote'",
              Timestamp.from(at.minusSeconds(1)));
        });
    worker.tick();
    worker.tick();
    assertEquals("UNCERTAIN", store.shipment("s_100").at("/quote/sendState").asText());
    assertEquals(0, store.read("select data from mail where id='s_100_quote'").size());
    tx.executeWithoutResult(
        t -> {
          store.lock();
          flow.accepted(store.shipment("s_100"), "mail_accepted", flow.clock.now());
        });
    assertEquals(
        "RECONCILED",
        store.db.queryForObject("select state from job where id='s_100:quote'", String.class));
    assertEquals(
        1,
        store.db.queryForObject("select count(*) from job where id='s_100:book'", Integer.class));
  }
}
