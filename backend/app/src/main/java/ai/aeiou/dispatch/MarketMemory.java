package ai.aeiou.dispatch;

import ai.aeiou.freight.Offer;
import ai.aeiou.freight.Shipment;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.RowCallbackHandler;

/** Historical rates are search hints. Only FiveU can return a current, bookable offer. */
final class MarketMemory {
  private final Ledger ledger;

  MarketMemory(Ledger ledger) {
    this.ledger = ledger;
    ledger.jdbc().execute("""
        CREATE TABLE IF NOT EXISTS market_rate(
          origin TEXT, destination TEXT, airline TEXT, bracket TEXT,
          total TEXT NOT NULL, kg TEXT NOT NULL, days REAL NOT NULL, version TEXT NOT NULL,
          seen INTEGER NOT NULL, PRIMARY KEY(origin,destination,airline,bracket))
        """);
  }

  record Key(String airline, String bracket) {}
  record Sample(BigDecimal total, BigDecimal kg, double days, String version) {
    BigDecimal estimate(BigDecimal weight) {
      return total.multiply(weight).divide(kg, MathContext.DECIMAL64);
    }

  }

  Map<Key, Sample> recent(Shipment s, Instant now) {
    var result = new HashMap<Key, Sample>();
    RowCallbackHandler collect = row -> {
      var key = new Key(row.getString("airline"), row.getString("bracket"));
      var sample = new Sample(new BigDecimal(row.getString("total")), new BigDecimal(row.getString("kg")),
          row.getDouble("days"), row.getString("version"));
      result.put(key, sample);
    };
    ledger.jdbc().query("""
        SELECT * FROM market_rate WHERE origin=? AND destination=? AND seen BETWEEN ? AND ?
        """, collect,
        s.origin(), s.destination(), now.minusSeconds(3600).getEpochSecond(), now.getEpochSecond());
    return result;
  }

  void forget(Shipment s) {
    ledger.jdbc().update("DELETE FROM market_rate WHERE origin=? AND destination=?", s.origin(), s.destination());
  }

  void record(Shipment s, Key key, Offer offer, String version, Instant now) {
    ledger.jdbc().update("""
        INSERT OR REPLACE INTO market_rate(origin,destination,airline,bracket,total,kg,days,version,seen)
        VALUES(?,?,?,?,?,?,?,?,?)
        """, s.origin(), s.destination(), key.airline(), key.bracket(), offer.totalEur().toPlainString(),
        offer.billedKg().toPlainString(), offer.days(), version, now.getEpochSecond());
  }
}
