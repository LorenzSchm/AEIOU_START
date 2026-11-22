package ai.aeiou.dispatch;

import static org.junit.jupiter.api.Assertions.*;

import ai.aeiou.freight.Shipment;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MisappliedBreakTest {
  @TempDir Path directory;

  @Test
  void flagsRound93BidThatBillsBelowTheThreeHundredKiloBreak() {
    var catalog = new DispatchApplication().catalog(directory);
    var shipment = new Shipment("DUS", "JFK", BigDecimal.valueOf(102), new BigDecimal("0.76"),
        "fastest", "44", null, 0);
    var november = LocalDate.of(2026, 11, 22);
    assertEquals(new BigDecimal("406.14"), catalog.price(shipment, november).stream()
        .filter(offer -> offer.airline().equals("Cobalt Skylift"))
        .findFirst().orElseThrow().totalEur().setScale(2));
    assertTrue(catalog.misappliedBreak(shipment, november, List.of(), "Cobalt Skylift", new BigDecimal("364.26")));
    assertFalse(catalog.misappliedBreak(shipment, november, List.of(), "Cobalt Skylift", new BigDecimal("406.14")));
    assertFalse(catalog.misappliedBreak(shipment, november, List.of(), "Cobalt Skylift", new BigDecimal("380.00")));
    assertFalse(catalog.misappliedBreak(shipment, november, List.of(), "Fjordline Air Cargo", new BigDecimal("364.26")));
  }

  @Test
  void parsesRound91FrameAgreementInsurance() {
    var insurance = CargoInsurance.from("""
        Please include cargo insurance in the all-in price, on the terms of our frame agreement: \
        goods value 22000 EUR, insured at 110 % of that value, premium rate 0.40 %.""");
    assertEquals(new BigDecimal("96.80"), insurance.premiumEur());
  }
}
