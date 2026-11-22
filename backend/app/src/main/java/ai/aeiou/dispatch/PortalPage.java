package ai.aeiou.dispatch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jakarta.annotation.PreDestroy;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.w3c.dom.Element;

/**
 * Prices one rate through the hosted rate page when the rate API is down. The page has two known
 * layouts that rotate with the hourly setup changes: a plain form, and a two-step bot challenge
 * (load a challenge with randomised field names and a "solve the sum" SVG, then submit). Either
 * way the result comes back as the API's rate fields, so the pricing code treats it like any
 * portal rate. Anything unexpected throws, and the worker falls back to the Rate Desk as before.
 */
@Component
class PortalPage {
  /** The page could not be reached or no longer looks like either known layout. */
  static class Down extends IOException {
    Down(String message) {
      super(message);
    }
  }

  // Rate IDs from the API and both page layouts have always been "R-" plus 16 characters. A
  // stricter check keeps a decoy character from ever reaching a booking.
  private static final Pattern RATE_ID = Pattern.compile("R-[A-Za-z0-9_-]{16}");
  private static final Duration RATE_LIFETIME = Duration.ofMinutes(14);
  private static final ObjectMapper JSON = new ObjectMapper();

  private final String url;
  private final String key;
  private final boolean readOnly;
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
  private final PortalBrowser browser;
  private volatile boolean challengeFirst;
  private volatile Instant retryAt = Instant.EPOCH;

  PortalPage(String url, String key, boolean readOnly) {
    this(url, key, readOnly, "", "", "", true);
  }

  /** With a {@code cf_clearance}, requests go through a browser to pass Cloudflare. */
  @Autowired
  PortalPage(@Value("${dispatch.portal-page}") String url, @Value("${dispatch.key}") String key,
      @Value("${dispatch.read-only}") boolean readOnly,
      @Value("${dispatch.portal-clearance}") String clearance,
      @Value("${dispatch.portal-user-agent}") String userAgent,
      @Value("${dispatch.portal-browser}") String channel,
      @Value("${dispatch.portal-headless}") boolean headless) {
    this.url = url;
    this.key = key;
    this.readOnly = readOnly;
    this.browser = clearance.isBlank() ? null
        : new PortalBrowser(url, clearance.strip(), userAgent.strip(), channel.strip(), headless);
  }

  @PreDestroy
  void close() {
    if (browser != null) browser.close();
  }

  /** One rate in the API's shape. Throws {@link Down} or IllegalArgumentException. */
  Map<String, Object> rate(String origin, String destination, String airline, BigDecimal weight,
      BigDecimal volume, Instant now) throws IOException {
    if (readOnly) throw new Down("This desk is read-only.");
    if (key == null || key.isBlank()) throw new Down("No team key for the portal page");
    if (now.isBefore(retryAt)) throw new Down("Portal page rate limit");
    var form = new LinkedHashMap<String, String>();
    form.put("origin", origin);
    form.put("destination", destination);
    form.put("airline", airline);
    form.put("weight_kg", weight.toPlainString());
    form.put("volume_cbm", volume == null ? "0" : volume.toPlainString());
    Map<String, String> fields;
    // Start with the layout that worked last; a layout change costs one extra request.
    boolean challenge = challengeFirst;
    try {
      fields = challenge ? challenge(form, now) : form(form, now);
    } catch (Limited limited) {
      throw limited;
    } catch (IOException | IllegalArgumentException first) {
      challenge = !challenge;
      fields = challenge ? challenge(form, now) : form(form, now);
    }
    challengeFirst = challenge;
    return rate(fields, origin, destination, airline, weight, now);
  }

  private static final class Limited extends Down {
    Limited() {
      super("Portal page rate limit");
    }
  }

  private Map<String, String> form(Map<String, String> form, Instant now) throws IOException {
    var body = new StringJoiner("&");
    var values = new LinkedHashMap<String, String>();
    values.put("team_key", key);
    values.putAll(form);
    values.forEach((name, value) -> body.add(URLEncoder.encode(name, StandardCharsets.UTF_8) + "="
        + URLEncoder.encode(value, StandardCharsets.UTF_8)));
    var response = send(Map.of("Content-Type", "application/x-www-form-urlencoded"), body.toString(), now);
    return result(response);
  }

  private Map<String, String> challenge(Map<String, String> form, Instant now) throws IOException {
    var load = send(Map.of("X-Portal-Step", "load"), "", now);
    JsonNode challenge;
    try {
      challenge = JSON.readTree(load.body());
    } catch (IOException ex) {
      throw new Down("Portal page did not return a challenge");
    }
    String id = challenge.path("id").asText("");
    var answer = challenge.path("answer");
    var consent = challenge.path("consent");
    if (id.isBlank() || !challenge.path("fields").isArray() || answer.path("name").asText("").isBlank())
      throw new Down("Portal challenge layout changed");
    var values = new LinkedHashMap<String, String>();
    for (var field : challenge.path("fields")) {
      String name = field.path("name").asText("");
      String label = field.path("label").asText("").toLowerCase(Locale.ROOT);
      String kind = field.path("kind").asText("");
      if (name.isBlank()) throw new Down("Portal challenge field without a name");
      values.put(name, "password".equals(kind) ? key
          : label.startsWith("origin") ? form.get("origin")
          : label.startsWith("destination") ? form.get("destination")
          : label.startsWith("weight") ? form.get("weight_kg")
          : label.startsWith("volume") ? form.get("volume_cbm")
          : "airline".equals(kind) || label.startsWith("airline") ? form.get("airline")
          : "");
    }
    try {
      values.put(answer.path("name").asText(), Sum.solve(Sum.fromDataUri(answer.path("image").asText(""))));
    } catch (IllegalArgumentException ex) {
      throw new Down("Portal challenge: " + ex.getMessage());
    }
    if (!consent.path("name").asText("").isBlank())
      values.put(consent.path("name").asText(), consent.path("value").asText(""));
    String boundary = UUID.randomUUID().toString().replace("-", "");
    var body = new StringBuilder();
    values.forEach((name, value) -> body.append("--").append(boundary)
        .append("\r\nContent-Disposition: form-data; name=\"").append(name).append("\"\r\n\r\n")
        .append(value).append("\r\n"));
    body.append("--").append(boundary).append("--\r\n");
    var response = send(Map.of("X-Portal-Challenge", id,
        "Content-Type", "multipart/form-data; boundary=" + boundary), body.toString(), now);
    return result(response);
  }

  private PortalBrowser.Reply send(Map<String, String> headers, String body, Instant now) throws IOException {
    PortalBrowser.Reply response = browser != null ? browser.post(headers, body) : direct(headers, body);
    if (response.challenged())
      throw new Down("Cloudflare challenged the portal page; refresh FIVEU_CF_CLEARANCE");
    if (response.status() == 429) {
      String seconds = response.retryAfter();
      retryAt = now.plusSeconds(seconds.matches("[0-9]{1,9}") ? Math.max(1, Long.parseLong(seconds)) : 60);
      throw new Limited();
    }
    if (response.status() >= 500) throw new Down("Portal page returned " + response.status());
    return response;
  }

  private PortalBrowser.Reply direct(Map<String, String> headers, String body) throws IOException {
    var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8))
        .header("User-Agent", "Mozilla/5.0 (compatible; aeiou-dispatch)")
        .POST(body.isEmpty() ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
    headers.forEach(request::header);
    HttpResponse<String> response;
    try {
      response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new Down("Interrupted");
    }
    return new PortalBrowser.Reply(response.statusCode(), response.body(),
        response.headers().firstValue("Retry-After").orElse(""),
        "challenge".equals(response.headers().firstValue("cf-mitigated").orElse("")));
  }

  /** Visible fields of a result, from HTML or from a JSON reply that carries the fields or HTML. */
  private static Map<String, String> result(PortalBrowser.Reply response) throws IOException {
    String body = response.body() == null ? "" : response.body();
    if (body.stripLeading().startsWith("{")) {
      JsonNode json;
      try {
        json = JSON.readTree(body);
      } catch (IOException ex) {
        throw new Down("Unreadable portal page reply");
      }
      if (json.has("rate_id")) {
        var fields = new LinkedHashMap<String, String>();
        json.properties().forEach(e -> fields.put(e.getKey(), e.getValue().asText()));
        return fields;
      }
      String html = html(json);
      if (html == null) {
        String detail = json.path("detail").asText(json.path("error").asText(""));
        throw new Down("Portal page reply without a rate" + (detail.isBlank() ? "" : ": " + detail));
      }
      body = html;
    }
    var page = Visible.read(body);
    if (page.fields().containsKey("rate_id")) return page.fields();
    if (!page.error().isBlank()) throw new IllegalArgumentException("Portal page: " + page.error());
    throw new Down("Portal page reply without a rate (HTTP " + response.status() + ")");
  }

  private static String html(JsonNode node) {
    if (node.isTextual() && node.asText().contains("<")) return node.asText();
    for (var child : node) {
      String html = html(child);
      if (html != null) return html;
    }
    return null;
  }

  /** Normalises page fields into the API's rate map and checks they belong to the request. */
  static Map<String, Object> rate(Map<String, String> fields, String origin, String destination,
      String airline, BigDecimal weight, Instant now) {
    var rate = new LinkedHashMap<String, Object>();
    String rateId = fields.getOrDefault("rate_id", "").replaceAll("\\s", "");
    if (!RATE_ID.matcher(rateId).matches()) throw new IllegalArgumentException("Unreadable page rate ID");
    rate.put("rate_id", rateId);
    String carrier = fields.getOrDefault("airline", "").strip();
    String code = fields.getOrDefault("airline_code", "").strip();
    var named = Pattern.compile("(.+?)\\s*\\(([A-Z0-9]{2,3})\\)").matcher(carrier);
    if (named.matches()) {
      carrier = named.group(1);
      code = named.group(2);
    }
    if (!airline.equalsIgnoreCase(carrier) && !airline.equalsIgnoreCase(code))
      throw new IllegalArgumentException("Page rate is for another airline");
    rate.put("airline", carrier);
    rate.put("airline_code", code);
    String from = fields.getOrDefault("origin", "");
    String to = fields.getOrDefault("destination", "");
    var lane = Pattern.compile("([A-Z]{3})\\s*[-–—>→]+\\s*([A-Z]{3})").matcher(fields.getOrDefault("lane", ""));
    if (lane.find()) {
      from = lane.group(1);
      to = lane.group(2);
    }
    if (!origin.equals(from.strip()) || !destination.equals(to.strip()))
      throw new IllegalArgumentException("Page rate is for another lane");
    rate.put("origin", origin);
    rate.put("destination", destination);
    String priced = fields.get("weight_kg");
    rate.put("weight_kg", priced == null ? weight.toPlainString() : number(priced).toPlainString());
    rate.put("chargeable_weight_kg", number(fields.getOrDefault("chargeable_weight_kg", "")).toPlainString());
    String total = fields.getOrDefault("total", "");
    rate.put("total", number(total).toPlainString());
    String currency = fields.getOrDefault("currency", "").strip().toUpperCase(Locale.ROOT);
    if (currency.isBlank())
      currency = total.contains("€") || total.toUpperCase(Locale.ROOT).contains("EUR") ? "EUR"
          : total.contains("$") || total.toUpperCase(Locale.ROOT).contains("USD") ? "USD" : "";
    rate.put("currency", currency);
    if (fields.containsKey("fx_eur_per_usd"))
      rate.put("fx_eur_per_usd", number(fields.get("fx_eur_per_usd")).toPlainString());
    var days = Pattern.compile("\\d+").matcher(fields.getOrDefault("transit_days", ""));
    if (!days.find()) throw new IllegalArgumentException("Missing page transit days");
    rate.put("transit_days", days.group());
    String validUntil = fields.getOrDefault("valid_until", "").strip();
    // The brief gives every rate ID 15 minutes; assume a little less when the page omits it.
    rate.put("valid_until", validUntil.isBlank() ? now.plus(RATE_LIFETIME).toString() : validUntil);
    // Page rates form their own dataset, so a pass never mixes them with API rates.
    rate.put("dataset_version", "page");
    return rate;
  }

  static BigDecimal number(String text) {
    String digits = text.replaceAll("[^0-9.,]", "");
    int dot = digits.lastIndexOf('.');
    int comma = digits.lastIndexOf(',');
    if (dot >= 0 && comma >= 0) {
      digits = dot > comma ? digits.replace(",", "") : digits.replace(".", "").replace(',', '.');
    } else if (comma >= 0) {
      digits = digits.length() - comma - 1 == 2 && digits.indexOf(',') == comma
          ? digits.replace(',', '.') : digits.replace(",", "");
    }
    if (!digits.matches("\\d+(\\.\\d+)?")) throw new IllegalArgumentException("Unreadable page number");
    return new BigDecimal(digits);
  }

  /**
   * Reads the text a browser would show. The challenge layout splits values into one-character
   * spans and hides decoys with a per-response CSS class, so <style> rules, inline styles and the
   * hidden attribute all decide which characters count.
   */
  record Visible(Map<String, String> fields, String error) {
    private static final Pattern TOKEN = Pattern.compile(
        "<!--.*?-->|<(/?)([a-zA-Z][a-zA-Z0-9]*)([^>]*)>|([^<]+)|<", Pattern.DOTALL);
    private static final Pattern RULE = Pattern.compile("([^{}]+)\\{([^{}]*)\\}");
    private static final Pattern HIDING = Pattern.compile(
        "display\\s*:\\s*none|visibility\\s*:\\s*hidden|font-size\\s*:\\s*0(?![.\\d]*[1-9])"
            + "|opacity\\s*:\\s*0(?![.\\d]*[1-9])|color\\s*:\\s*transparent");
    private static final Set<String> VOID = Set.of("area", "base", "br", "col", "embed", "hr", "img",
        "input", "link", "meta", "source", "track", "wbr");

    static Visible read(String html) {
      var hidden = new HashSet<String>();
      var styles = Pattern.compile("<style[^>]*>(.*?)</style>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE)
          .matcher(html);
      while (styles.find()) {
        var rule = RULE.matcher(styles.group(1));
        while (rule.find()) {
          if (!HIDING.matcher(rule.group(2)).find()) continue;
          for (String selector : rule.group(1).split(",")) {
            var cls = Pattern.compile("\\.([A-Za-z_][\\w-]*)\\s*$").matcher(selector.strip());
            if (cls.find()) hidden.add(cls.group(1));
          }
        }
      }
      record Open(String tag, boolean hidden, String id, StringBuilder text) {}
      var open = new ArrayDeque<Open>();
      var fields = new LinkedHashMap<String, String>();
      String label = "";
      String error = "";
      var tokens = TOKEN.matcher(html);
      while (tokens.find()) {
        if (tokens.group(4) != null || tokens.group(2) == null) {
          String text = tokens.group(4) == null ? "" : entities(tokens.group(4));
          if (open.isEmpty() || open.peek().hidden()) continue;
          for (var element : open) if (element.text() != null) element.text().append(text);
          continue;
        }
        String tag = tokens.group(2).toLowerCase(Locale.ROOT);
        String attributes = tokens.group(3);
        if (tokens.group(1).isEmpty()) {
          if (tag.equals("style") || tag.equals("script")) {
            int end = html.toLowerCase(Locale.ROOT).indexOf("</" + tag, tokens.end());
            if (end < 0) break;
            tokens.region(end, html.length());
            continue;
          }
          if (VOID.contains(tag) || attributes.strip().endsWith("/")) continue;
          String classes = attribute(attributes, "class");
          String style = attribute(attributes, "style");
          boolean hide = (!open.isEmpty() && open.peek().hidden())
              || Arrays.stream(classes.split("\\s+")).anyMatch(hidden::contains)
              || (style != null && HIDING.matcher(style).find())
              || Pattern.compile("(^|\\s)hidden(\\s|=|$)").matcher(attributes).find();
          String id = attribute(attributes, "id");
          boolean collect = tag.equals("th") || tag.equals("td") || !id.isBlank();
          open.push(new Open(tag, hide, id, collect ? new StringBuilder() : null));
          continue;
        }
        if (open.stream().noneMatch(element -> element.tag().equals(tag))) continue;
        Open closed;
        do {
          closed = open.pop();
          if (closed.text() == null) continue;
          String text = closed.text().toString().replaceAll("\\s+", " ").strip();
          if (closed.tag().equals("th")) label = text;
          else if (closed.tag().equals("td")) {
            String field = field(closed.id());
            if (field == null) field = field(label);
            if (field != null && !closed.hidden()) fields.putIfAbsent(field, text);
          } else if (closed.id().equals("error") && !closed.hidden()) error = text;
        } while (!closed.tag().equals(tag));
      }
      return new Visible(fields, error);
    }

    static String field(String label) {
      String l = label.toLowerCase(Locale.ROOT).replaceAll("[^a-z]+", " ").strip();
      if (l.isEmpty()) return null;
      if (l.contains("rate") && l.contains("id")) return "rate_id";
      if (l.startsWith("airline code")) return "airline_code";
      if (l.startsWith("airline") || l.equals("carrier")) return "airline";
      if (l.startsWith("lane") || l.startsWith("route")) return "lane";
      if (l.startsWith("origin")) return "origin";
      if (l.startsWith("destination")) return "destination";
      if (l.contains("chargeable")) return "chargeable_weight_kg";
      if (l.startsWith("weight")) return "weight_kg";
      if (l.startsWith("total") || l.startsWith("price")) return "total";
      if (l.startsWith("currency")) return "currency";
      if (l.contains("transit")) return "transit_days";
      if (l.startsWith("valid")) return "valid_until";
      if (l.startsWith("fx") || l.contains("exchange")) return "fx_eur_per_usd";
      return null;
    }

    private static String attribute(String attributes, String name) {
      var m = Pattern.compile("(?:^|\\s)" + name + "\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))",
          Pattern.CASE_INSENSITIVE).matcher(attributes);
      if (!m.find()) return "style".equals(name) ? null : "";
      return m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2) : m.group(3);
    }

    private static String entities(String text) {
      var m = Pattern.compile("&(#x[0-9a-fA-F]+|#\\d+|[a-zA-Z]+);").matcher(text);
      var out = new StringBuilder();
      while (m.find()) {
        String e = m.group(1);
        String c = switch (e) {
          case "amp" -> "&";
          case "lt" -> "<";
          case "gt" -> ">";
          case "quot" -> "\"";
          case "apos" -> "'";
          case "nbsp" -> " ";
          case "euro" -> "€";
          default -> e.startsWith("#x") ? Character.toString(Integer.parseInt(e.substring(2), 16))
              : e.startsWith("#") ? Character.toString(Integer.parseInt(e.substring(1))) : m.group();
        };
        m.appendReplacement(out, Matcher.quoteReplacement(c));
      }
      return m.appendTail(out).toString();
    }
  }

  /**
   * Decodes the challenge's "solve the sum" SVG without OCR. Each character is a
   * {@code <g transform="translate(x y) rotate(..)">} of {@code <line>} strokes on a 24 x 42
   * seven-segment grid in local coordinates; noise is drawn outside the groups.
   */
  static final class Sum {
    private static final Map<String, String> DIGITS = Map.of(
        "abcdef", "0", "bc", "1", "abdeg", "2", "abcdg", "3", "bcfg", "4",
        "acdfg", "5", "acdefg", "6", "abc", "7", "abcdefg", "8", "abcdfg", "9");
    private static final Pattern TRANSLATE = Pattern.compile("translate\\(([-\\d.]+)[ ,]+([-\\d.]+)\\)");

    static String fromDataUri(String uri) {
      int comma = uri.indexOf(',');
      if (!uri.startsWith("data:") || comma < 0) throw new IllegalArgumentException("challenge image is not a data URI");
      String data = uri.substring(comma + 1);
      return uri.substring(0, comma).endsWith(";base64")
          ? new String(Base64.getDecoder().decode(data), StandardCharsets.UTF_8)
          : java.net.URLDecoder.decode(data, StandardCharsets.UTF_8);
    }

    static String solve(String svg) {
      return answer(decode(svg));
    }

    static String decode(String svg) {
      org.w3c.dom.Document document;
      try {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setExpandEntityReferences(false);
        document = factory.newDocumentBuilder()
            .parse(new ByteArrayInputStream(svg.getBytes(StandardCharsets.UTF_8)));
      } catch (Exception ex) {
        throw new IllegalArgumentException("unreadable challenge image");
      }
      var chars = new TreeMap<Double, String>();
      var groups = document.getElementsByTagNameNS("*", "g");
      for (int i = 0; i < groups.getLength(); i++) {
        var group = (Element) groups.item(i);
        // A wrapper around the character groups is not a character itself.
        if (group.getElementsByTagNameNS("*", "g").getLength() > 0) continue;
        var lines = group.getElementsByTagNameNS("*", "line");
        if (lines.getLength() == 0) continue;
        var translate = TRANSLATE.matcher(group.getAttribute("transform"));
        double x = translate.find() ? Double.parseDouble(translate.group(1)) : 0;
        var strokes = new ArrayList<double[]>();
        for (int j = 0; j < lines.getLength(); j++) {
          var line = (Element) lines.item(j);
          strokes.add(new double[] {coordinate(line, "x1"), coordinate(line, "y1"),
              coordinate(line, "x2"), coordinate(line, "y2")});
        }
        if (chars.put(x, glyph(strokes)) != null) throw new IllegalArgumentException("overlapping glyphs");
      }
      if (chars.isEmpty()) throw new IllegalArgumentException("no glyphs in challenge image");
      return String.join("", chars.values());
    }

    private static double coordinate(Element line, String name) {
      try {
        return Double.parseDouble(line.getAttribute(name));
      } catch (NumberFormatException ex) {
        throw new IllegalArgumentException("unreadable stroke");
      }
    }

    static char segment(double x1, double y1, double x2, double y2) {
      double dx = Math.abs(x2 - x1), dy = Math.abs(y2 - y1);
      double mx = (x1 + x2) / 2, my = (y1 + y2) / 2;
      if (dx > dy * 2) {
        if (my < 7) return 'a';
        if (my > 33) return 'd';
        if (my >= 16 && my <= 23) return 'g';
        return 'h'; // off-grid horizontal: part of '=' (or '-')
      }
      if (dy > dx * 2) {
        if (mx > 6 && mx < 17 && my > 6 && my < 34 && Math.abs(my - 20) < 6 && dy > 14)
          return 'v'; // centred vertical: part of '+'
        boolean left = mx < 11, top = my < 20;
        return left ? (top ? 'f' : 'e') : (top ? 'b' : 'c');
      }
      return 'x'; // diagonal: multiplication or division sign
    }

    static String glyph(List<double[]> strokes) {
      var segments = new ArrayList<Character>();
      for (var s : strokes) segments.add(segment(s[0], s[1], s[2], s[3]));
      boolean twoStrokes = segments.size() == 2;
      if (twoStrokes && segments.contains('v') && (segments.contains('g') || segments.contains('h')))
        return "+";
      if (twoStrokes && segments.stream().allMatch(c -> c == 'g' || c == 'h'))
        return "="; // two horizontals; the upper one can land in the middle-segment band
      if (twoStrokes && segments.stream().allMatch(c -> c == 'x')) return "*";
      if (segments.size() == 1 && (segments.getFirst() == 'g' || segments.getFirst() == 'h')) return "-";
      var set = new TreeSet<>(segments);
      var name = new StringBuilder();
      set.forEach(name::append);
      String digit = DIGITS.get(name.toString());
      if (digit == null || set.size() != segments.size())
        throw new IllegalArgumentException("unknown glyph " + segments);
      return digit;
    }

    /** Evaluates digits joined by + - * with the usual precedence. */
    static String answer(String expression) {
      String expr = expression.endsWith("=") ? expression.substring(0, expression.length() - 1) : expression;
      if (!expr.matches("\\d+(?:[-+*]\\d+)+")) throw new IllegalArgumentException("unexpected sum " + expression);
      long total = 0;
      var terms = Pattern.compile("([-+]?)([\\d*]+)").matcher(expr);
      while (terms.find()) {
        long product = 1;
        for (String factor : terms.group(2).split("\\*")) product = Math.multiplyExact(product, Long.parseLong(factor));
        total += "-".equals(terms.group(1)) ? -product : product;
      }
      return Long.toString(total);
    }
  }
}
