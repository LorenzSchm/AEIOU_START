package ai.aeiou.dispatch;

import static org.junit.jupiter.api.Assertions.*;

import ai.aeiou.freight.Shipment;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MonthlyTariffsTest {
  @TempDir Path directory;

  @Test
  void readsTheRfqsMonthAndPicksUpNewPricesWithoutRestarting() throws Exception {
    var catalog = new DispatchApplication().catalog(directory);
    var shipment = new Shipment("FRA", "LAX", BigDecimal.valueOf(100), BigDecimal.ZERO,
        "cheapest", null, null, 0);
    var november = LocalDate.of(2026, 11, 1);
    var december = november.plusMonths(1);
    assertEquals(new BigDecimal("314.00"), catalog.price(shipment, november).stream()
        .filter(offer -> offer.airline().equals("Wayzata Air Cargo"))
        .findFirst().orElseThrow().totalEur());
    assertTrue(catalog.price(shipment, november.minusDays(1)).isEmpty());
    assertTrue(catalog.price(shipment, december).isEmpty());

    String header = "airline,origin,destination,days,minimum,fuel,security,0,45,100,250,300,500,1000,2000\n";
    var nextMonth = directory.resolve("tariffs-2026-12.csv");
    Files.writeString(nextMonth, header + "Wayzata Air Cargo,FRA,LAX,4,90.95,1.19,0.14,2.42,2.11,1.50,,,,,\n");
    assertEquals(new BigDecimal("283.00"),
        catalog.price(shipment, december).getFirst().totalEur());

    Files.writeString(nextMonth, header + "Wayzata Air Cargo,FRA,LAX,4,90.95,1.19,0.14,2.42,2.11,1.35,,,,,\n");
    assertEquals(new BigDecimal("268.00"),
        catalog.price(shipment, december).getFirst().totalEur());
    assertEquals(catalog.price(shipment, november), catalog.price(shipment, december.minusDays(1)));
  }
}
