package ai.aeiou.dispatch;

import static org.junit.jupiter.api.Assertions.*;

import ai.aeiou.freight.Shipment;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpServerErrorException;

class PortalPageTest {
  private static final Instant NOW = Instant.parse("2026-11-22T01:10:00Z");
  @TempDir java.nio.file.Path temp;

  // The plain form layout as the live page returned it on 2026-11-22.
  private static final String PLAIN = """
      <section id="result"><table data-table class="w-full text-sm">
        <thead><tr><th>Field</th><th>Value</th></tr></thead>
        <tbody>
          <tr><th class="py-2">rate_id</th><td id="rate_id" class="py-2 font-mono">R-gc7COLI-sP5SSDJk</td></tr>
          <tr><th class="py-2">Airline</th><td id="airline" class="py-2">Solace Air Cargo (SL)</td></tr>
          <tr><th class="py-2">Lane</th><td id="lane" class="py-2">FRA-JFK</td></tr>
          <tr><th class="py-2">Chargeable weight (kg)</th><td id="chargeable_weight_kg">500</td></tr>
          <tr><th class="py-2">Total</th><td id="total" class="py-2 font-semibold">1595.00</td></tr>
          <tr><th class="py-2">Currency</th><td id="currency" class="py-2">EUR</td></tr>
          <tr><th class="py-2">Transit days</th><td id="transit_days" class="py-2">2</td></tr>
          <tr><th class="py-2">Valid until</th><td id="valid_until">2026-11-22T01:26:07.160771+00:00</td></tr>
        </tbody></table></section>""";

  // The challenge layout: one-character spans, decoys hidden by a per-response class.
  private static final String SCRAMBLED = """
      <style>.k9q{display:none} .vis{color:#111}</style>
      <table><tbody>
        <tr><th>Rate ID</th><td>%s</td></tr>
        <tr><th>Airline</th><td>Solace Air Cargo (SL)</td></tr>
        <tr><th>Lane</th><td>FRA–JFK</td></tr>
        <tr><th>Chargeable weight</th><td>500 kg</td></tr>
        <tr><th>Total</th><td>%s</td></tr>
        <tr><th>Transit</th><td>2 days</td></tr>
        <tr><th>Valid until</th><td>2026-11-22T01:26:07+00:00</td></tr>
      </tbody></table>""".formatted(
          scramble("R-XKPLuZTMItuN4UYC", "7Q"), scramble("€2,315.00", "98"));

  private static String scramble(String value, String decoys) {
    var html = new StringBuilder();
    for (int i = 0; i < value.length(); i++) {
      html.append("<span class=\"vis\">").append(value.charAt(i)).append("</span>");
      if (i % 5 == 1) html.append("<span class=\"k9q\">").append(decoys.charAt(i % decoys.length())).append("</span>");
      if (i % 7 == 3) html.append("<span style=\"display: none\">5</span>");
    }
    return html.toString();
  }

  @Test
  void readsThePlainFormResult() {
    var rate = PortalPage.rate(PortalPage.Visible.read(PLAIN).fields(), "FRA", "JFK", "SL",
        new BigDecimal("500"), NOW);
    assertEquals("R-gc7COLI-sP5SSDJk", rate.get("rate_id"));
    assertEquals("Solace Air Cargo", rate.get("airline"));
    assertEquals("SL", rate.get("airline_code"));
    assertEquals("1595.00", rate.get("total"));
    assertEquals("EUR", rate.get("currency"));
    assertEquals("2", rate.get("transit_days"));
    assertEquals("500", rate.get("weight_kg"));
    assertEquals("500", rate.get("chargeable_weight_kg"));
    assertEquals("page", rate.get("dataset_version"));
  }

  @Test
  void keepsOnlyVisibleCharactersOfAScrambledResult() {
    var rate = PortalPage.rate(PortalPage.Visible.read(SCRAMBLED).fields(), "FRA", "JFK",
        "Solace Air Cargo", new BigDecimal("500"), NOW);
    assertEquals("R-XKPLuZTMItuN4UYC", rate.get("rate_id"));
    assertEquals("2315.00", rate.get("total"));
    assertEquals("EUR", rate.get("currency"));
    assertEquals("2", rate.get("transit_days"));
  }

  @Test
  void refusesRatesThatDoNotBelongToTheRequestOrCarryDecoys() {
    var fields = PortalPage.Visible.read(PLAIN).fields();
    assertThrows(IllegalArgumentException.class,
        () -> PortalPage.rate(fields, "FRA", "JFK", "PL", new BigDecimal("500"), NOW));
    assertThrows(IllegalArgumentException.class,
        () -> PortalPage.rate(fields, "MUC", "JFK", "SL", new BigDecimal("500"), NOW));
    var decoyed = new HashMap<>(fields);
    decoyed.put("rate_id", "R-gc7COLI-sP5SSDJk7");
    assertThrows(IllegalArgumentException.class,
        () -> PortalPage.rate(decoyed, "FRA", "JFK", "SL", new BigDecimal("500"), NOW));
  }

  @Test
  void readsNumbersInEitherNotation() {
    assertEquals(new BigDecimal("2315.00"), PortalPage.number("€2,315.00"));
    assertEquals(new BigDecimal("2315.00"), PortalPage.number("2.315,00 EUR"));
    assertEquals(new BigDecimal("12.50"), PortalPage.number("12,50"));
    assertEquals(new BigDecimal("1200"), PortalPage.number("1,200"));
  }

  @Test
  void decodesTheSevenSegmentSum() {
    assertEquals("7+5=", PortalPage.Sum.decode(svg("7+5=")));
    assertEquals("12", PortalPage.Sum.solve(svg("7+5=")));
    assertEquals("6", PortalPage.Sum.solve(svg("9-3=")));
    assertEquals("80", PortalPage.Sum.solve(svg("0+8*10=")));
    assertEquals("11", PortalPage.Sum.answer("3+4*2="));
    var uri = "data:image/svg+xml;base64,"
        + Base64.getEncoder().encodeToString(svg("4+4=").getBytes(StandardCharsets.UTF_8));
    assertEquals("8", PortalPage.Sum.solve(PortalPage.Sum.fromDataUri(uri)));
  }

  @Test
  void rejectsUnknownGlyphs() {
    // One extra stroke turns a glyph into nothing the decoder knows.
    String broken = svg("1+1=").replaceFirst("(<g [^>]*>)", "$1<line x1='2' y1='21' x2='22' y2='21'/>");
    assertThrows(IllegalArgumentException.class, () -> PortalPage.Sum.solve(broken));
  }

  @Test
  void pricesThroughEitherPageLayoutAndRemembersWhichWorked() throws Exception {
    var requests = new ArrayList<String>();
    var challengeMode = new boolean[] {false};
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/portal/ui", exchange -> {
      String step = exchange.getRequestHeaders().getFirst("X-Portal-Step");
      String challenge = exchange.getRequestHeaders().getFirst("X-Portal-Challenge");
      String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      requests.add(step != null ? "load" : challenge != null ? "submit" : "form");
      String reply;
      int status = 200;
      if (!challengeMode[0] && step == null && challenge == null) {
        assertTrue(body.contains("team_key=secret") && body.contains("airline=SL"), body);
        reply = "<html><body>" + PLAIN + "</body></html>";
      } else if (!challengeMode[0] || (step == null && challenge == null)) {
        status = 422;
        reply = "<div id=\"error\">Solve the challenge first.</div>";
      } else if (step != null) {
        reply = new ObjectMapper().writeValueAsString(Map.of(
            "id", "c-1",
            "fields", List.of(
                Map.of("name", "f_a", "label", "Team key", "kind", "password"),
                Map.of("name", "f_b", "label", "Origin (IATA)", "kind", "text"),
                Map.of("name", "f_c", "label", "Destination (IATA)", "kind", "text"),
                Map.of("name", "f_d", "label", "Airline", "kind", "airline"),
                Map.of("name", "f_e", "label", "Weight (kg)", "kind", "text"),
                Map.of("name", "f_f", "label", "Volume (cbm)", "kind", "text")),
            "answer", Map.of("name", "f_sum", "image", "data:image/svg+xml;base64,"
                + Base64.getEncoder().encodeToString(svg("8-3=").getBytes(StandardCharsets.UTF_8))),
            "consent", Map.of("name", "f_ok", "value", "tok-9")));
      } else {
        assertEquals("c-1", challenge);
        for (var part : List.of("name=\"f_a\"\r\n\r\nsecret", "name=\"f_b\"\r\n\r\nFRA",
            "name=\"f_d\"\r\n\r\nSL", "name=\"f_e\"\r\n\r\n500", "name=\"f_sum\"\r\n\r\n5",
            "name=\"f_ok\"\r\n\r\ntok-9"))
          assertTrue(body.contains(part), part);
        reply = new ObjectMapper().writeValueAsString(Map.of("html", SCRAMBLED));
      }
      byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(status, bytes.length);
      exchange.getResponseBody().write(bytes);
      exchange.close();
    });
    server.start();
    try {
      var page = new PortalPage("http://127.0.0.1:" + server.getAddress().getPort() + "/portal/ui",
          "secret", false);
      var plain = page.rate("FRA", "JFK", "SL", new BigDecimal("500"), BigDecimal.ONE, NOW);
      assertEquals("R-gc7COLI-sP5SSDJk", plain.get("rate_id"));

      challengeMode[0] = true;
      var solved = page.rate("FRA", "JFK", "SL", new BigDecimal("500"), BigDecimal.ONE, NOW);
      assertEquals("R-XKPLuZTMItuN4UYC", solved.get("rate_id"));
      assertEquals("2315.00", solved.get("total"));
      requests.clear();
      page.rate("FRA", "JFK", "SL", new BigDecimal("500"), BigDecimal.ONE, NOW);
      assertEquals(List.of("load", "submit"), requests);
    } finally {
      server.stop(0);
    }
    assertThrows(PortalPage.Down.class, () -> new PortalPage("http://127.0.0.1:1/portal/ui", "secret", true)
        .rate("FRA", "JFK", "SL", BigDecimal.TEN, BigDecimal.ONE, NOW));
  }

  @Test
  void theWorkerPricesThroughThePageWhileTheRateApiIsDown() throws Exception {
    var ledger = new Ledger(temp.resolve("page.sqlite3").toString(), new ObjectMapper());
    var pageUp = new boolean[] {true};
    var pageCalls = new ArrayList<String>();
    var portal = new FiveU("http://localhost", "fixture", new MarketMemory(ledger)) {
      @Override
      Map<String, Object> get(String uri, Object... args) {
        if (uri.endsWith("/ports"))
          return Map.of("lanes", List.of(Map.of("origin", "FRA", "destination", "JFK", "airlines",
              List.of(Map.of("code", "PL", "name", "Polaris Air Logistics"),
                  Map.of("code", "SL", "name", "Solace Air Cargo")))));
        throw HttpServerErrorException.create(HttpStatus.SERVICE_UNAVAILABLE, "unavailable",
            null, null, null);
      }

      @Override
      Map<String, Object> pageRate(Shipment shipment, String airline, BigDecimal weight, Instant now) {
        pageCalls.add(airline);
        if (!pageUp[0]) throw new PageDown("page down");
        var fields = new HashMap<>(PortalPage.Visible.read(PLAIN).fields());
        fields.put("airline", airline.equals("SL") || airline.startsWith("Solace") ? "Solace Air Cargo (SL)" : "Polaris Air Logistics (PL)");
        fields.put("valid_until", now.plusSeconds(900).toString());
        return PortalPage.rate(fields, "FRA", "JFK", airline, weight, now);
      }
    };
    var shipment = new Shipment("FRA", "JFK", new BigDecimal("500"), BigDecimal.ONE, "cheapest", null, null, 0);

    var rates = portal.rates(shipment, NOW);
    assertTrue(rates.unavailable());
    assertEquals(List.of("PL", "SL"), pageCalls);
    assertEquals(2, rates.offers().size());
    assertEquals("page", rates.version());
    assertEquals("portal", rates.offers().getFirst().source());

    var candidate = rates.offers().get(1);
    assertEquals("R-gc7COLI-sP5SSDJk", portal.currentRate(shipment, candidate, NOW).rateId());

    pageUp[0] = false;
    pageCalls.clear();
    var down = portal.rates(shipment, NOW);
    assertTrue(down.unavailable());
    assertTrue(down.offers().isEmpty());
    assertEquals(1, pageCalls.size());
    // A booking recheck still sees the outage, not a changed rate.
    assertThrows(HttpServerErrorException.ServiceUnavailable.class,
        () -> portal.currentRate(shipment, candidate, NOW));
  }

  /** A sum drawn like the portal's challenge: one rotated group per character, noise outside. */
  private static String svg(String expression) {
    var segments = Map.of(
        'a', "2,2,22,2", 'b', "22,2,22,19", 'c', "22,23,22,40", 'd', "2,40,22,40",
        'e', "2,23,2,40", 'f', "2,2,2,19", 'g', "2,21,22,21");
    var glyphs = Map.ofEntries(
        Map.entry('0', "abcdef"), Map.entry('1', "bc"), Map.entry('2', "abged"), Map.entry('3', "abgcd"),
        Map.entry('4', "fgbc"), Map.entry('5', "afgcd"), Map.entry('6', "afgecd"), Map.entry('7', "abc"),
        Map.entry('8', "abcdefg"), Map.entry('9', "abcdfg"));
    var svg = new StringBuilder("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"260\" height=\"60\">"
        + "<line x1=\"0\" y1=\"5\" x2=\"250\" y2=\"50\" stroke=\"#999\"/><circle cx=\"40\" cy=\"30\" r=\"2\"/>");
    double x = 8;
    // Shuffle the document order; position alone decides the reading order.
    var order = new ArrayList<Integer>();
    for (int i = 0; i < expression.length(); i++) order.add(i);
    Collections.reverse(order);
    for (int i : order) {
      char c = expression.charAt(i);
      var lines = new ArrayList<String>();
      if (glyphs.containsKey(c)) for (char s : glyphs.get(c).toCharArray()) lines.add(segments.get(s));
      else if (c == '+') lines.addAll(List.of(segments.get('g'), "12,12,12,30"));
      else if (c == '-') lines.add(segments.get('g'));
      else if (c == '*') lines.addAll(List.of("4,10,20,32", "20,10,4,32"));
      else if (c == '=') lines.addAll(List.of("2,14,22,14", "2,27,22,27"));
      svg.append("<g transform=\"translate(").append(x + i * 30).append(" 9) rotate(")
          .append(i % 2 == 0 ? 3 : -4).append(")\">");
      for (var line : lines) {
        var p = line.split(",");
        svg.append("<line x1=\"").append(p[0]).append("\" y1=\"").append(p[1]).append("\" x2=\"")
            .append(p[2]).append("\" y2=\"").append(p[3]).append("\" stroke=\"#222\"/>");
      }
      svg.append("</g>");
    }
    return svg.append("</svg>").toString();
  }
}
