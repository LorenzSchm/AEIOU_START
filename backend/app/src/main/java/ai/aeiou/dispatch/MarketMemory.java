package ai.aeiou.dispatch;

import ai.aeiou.freight.Offer;
import ai.aeiou.freight.Shipment;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Component;

/** Uses recent confirmed portal rates to order future rate requests. */
@Component
final class MarketMemory {
  private static final Logger log = LoggerFactory.getLogger(MarketMemory.class);
  private final Ledger ledger;

  MarketMemory(Ledger ledger) {
    this.ledger = ledger;
  }

  record Key(String airline, BigDecimal bracket) {
    Key {
      bracket = bracket.stripTrailingZeros();
    }
  }

  record Sample(BigDecimal total, BigDecimal kg, double days) {
    BigDecimal estimate(BigDecimal weight) {
      return total.multiply(weight).divide(kg, MathContext.DECIMAL64);
    }
  }

  Map<Key, Sample> recent(Shipment shipment, Instant now) {
    var samples = new HashMap<Key, Sample>();
    try {
      RowCallbackHandler collect = row -> {
        var key = new Key(row.getString("airline"), new BigDecimal(row.getString("bracket")));
        var kg = new BigDecimal(row.getString("kg"));
        if (kg.signum() > 0) {
          samples.put(key, new Sample(new BigDecimal(row.getString("total")), kg,
              row.getDouble("days")));
        }
      };
      ledger.jdbc().query("""
          SELECT airline,bracket,total,kg,days FROM portal_rate_history
          WHERE origin=? AND destination=? AND seen BETWEEN ? AND ?
          """, collect, shipment.origin(), shipment.destination(),
          now.minusSeconds(3600).toEpochMilli(), now.toEpochMilli());
      return samples;
    } catch (DataAccessException | IllegalArgumentException ex) {
      log.warn("Portal rate history unavailable ({})", ex.getClass().getSimpleName());
      return Map.of();
    }
  }

  void record(Shipment shipment, Map<Key, Offer> offers, Instant now) {
    if (offers.isEmpty()) return;
    var samples = offers.entrySet().stream().map(entry -> {
      var key = entry.getKey();
      var offer = entry.getValue();
      return new Object[] {shipment.origin(), shipment.destination(), key.airline(),
          key.bracket().toPlainString(), offer.totalEur().toPlainString(),
          offer.billedKg().toPlainString(), offer.days(), now.toEpochMilli()};
    }).toList();
    try {
      ledger.jdbc().batchUpdate("""
          INSERT INTO portal_rate_history(origin,destination,airline,bracket,total,kg,days,seen)
          VALUES(?,?,?,?,?,?,?,?)
          ON CONFLICT(origin,destination,airline,bracket) DO UPDATE SET
            total=excluded.total,kg=excluded.kg,days=excluded.days,seen=excluded.seen
          WHERE excluded.seen >= portal_rate_history.seen
          """, samples);
    } catch (DataAccessException ex) {
      log.warn("Could not save portal rate history ({})", ex.getClass().getSimpleName());
    }
  }
}
