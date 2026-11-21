package ai.aeiou.forwarder;

import static org.junit.jupiter.api.Assertions.*;

import ai.aeiou.forwarder.adapters.TariffReader;
import ai.aeiou.forwarder.domain.Pricing;
import java.math.*;
import java.nio.file.*;
import java.time.*;
import org.junit.jupiter.api.Test;

class TariffTest {
  @Test
  void ingestEveryStarterTariff() throws Exception {
    var reader = new TariffReader();
    try (var files = Files.list(Path.of("start-kit"))) {
      var inputs =
          files
              .filter(p -> p.toString().contains("-tariff-") && !p.toString().endsWith("-p2.jpg"))
              .sorted()
              .toList();
      assertEquals(10, inputs.size());
      for (var p : inputs) {
        var data = reader.read(p, Instant.EPOCH);
        assertEquals("ACTIVE", data.tariff().path("state").asText(), () -> p + " " + data.tariff());
        var expected =
            java.util.Map.of(
                "cobalt",
                21,
                "danube",
                9,
                "elbflug",
                19,
                "helix",
                12,
                "kestrel",
                18,
                "orion",
                14,
                "quetzal",
                11,
                "vega",
                13,
                "wayzata",
                26,
                "yellowtail",
                15);
        assertEquals(
            expected.get(p.getFileName().toString().split("-")[0]),
            data.lanes().size(),
            p.toString());
        if (!p.toString().contains("yellowtail")) continue;
        var lane =
            data.lanes()
                .valueStream()
                .filter(
                    x ->
                        x.path("origin").asText().equals("MUC")
                            && x.path("destination").asText().equals("LAX"))
                .findFirst()
                .orElseThrow();
        for (var f :
            new String[][] {
              {"100", "0.2", "false", "304.00"},
              {"20", "0.05", "false", "108.20"},
              {"99", "0", "false", "355.41"},
              {"99", "0", "true", "304.00"}
            })
          assertEquals(
              new BigDecimal(f[3]),
              Pricing.price(
                      lane, new BigDecimal(f[0]), new BigDecimal(f[1]), Boolean.parseBoolean(f[2]))
                  .total());
        assertEquals(
            0,
            Pricing.price(lane, new BigDecimal("150"), new BigDecimal("1.92"), false)
                .billed()
                .compareTo(new BigDecimal("320.64")));
      }
    }
  }
}
