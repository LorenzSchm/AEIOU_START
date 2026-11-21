package ai.aeiou.dispatch;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

/**
 * Java port of {@code backend/cloudline/cloudline_renderer.py}: wraps a finished plain-text quote
 * in the Cloudline HTML layout without changing its wording. Template and cloud image keep the
 * handover's {@code mail-designs/} structure on the classpath under {@code cloudline/}.
 */
final class Cloudline {
  private static final String ROOT = "cloudline/mail-designs/";
  private static final String SIGNATURE_STYLE = "color: #304c5a; font-weight: 700;";
  private static final Pattern PARAGRAPH = Pattern.compile("\n(?:[^\\S\n]*\n)+");
  private static final Pattern PLACEHOLDER = Pattern.compile("\\$([A-Za-z_][A-Za-z0-9_]*)");
  private static final String LAYOUT = text(ROOT + "cloudline.html");
  private static final String CLOUDS =
      "data:image/webp;base64,"
          + Base64.getEncoder().encodeToString(bytes(ROOT + "assets/clouds.webp"));

  private Cloudline() {}

  static String renderMailHtml(
      String body, String allInTotal, String language, String variant, String origin,
      String destination) {
    if (!"cloudline".equals(variant))
      throw new IllegalArgumentException("Unknown AEIOU mail design: " + variant);
    body = body.replace("\r\n", "\n").replace("\r", "\n").strip();
    var paragraphs = body.isEmpty() ? new String[0] : PARAGRAPH.split(body);
    String total =
        allInTotal != null && !allInTotal.isEmpty() && occurrences(body, allInTotal) == 1
            ? allInTotal
            : "";
    var rendered = new ArrayList<String>();
    for (int p = 0; p < paragraphs.length; p++) {
      boolean lastParagraph = p == paragraphs.length - 1;
      var lines = paragraphs[p].split("\n", -1);
      var renderedLines = new ArrayList<String>();
      for (int l = 0; l < lines.length; l++) {
        String line = lines[l];
        String html = escape(line);
        int at = total.isEmpty() ? -1 : line.indexOf(total);
        if (at >= 0)
          html =
              escape(line.substring(0, at))
                  + "<strong>"
                  + escape(total)
                  + "</strong>"
                  + escape(line.substring(at + total.length()));
        if (lastParagraph && l == lines.length - 1 && line.strip().equals("Team AEIOU"))
          html = "<span style=\"" + SIGNATURE_STYLE + "\">" + html + "</span>";
        renderedLines.add(html);
      }
      String margin = lastParagraph ? "0" : "0 0 16px";
      rendered.add(
          "<p style=\"margin: " + margin + ";\">"
              + String.join("<br>\n              ", renderedLines)
              + "</p>");
    }
    String lane = "Freight offer";
    if (origin != null && destination != null && !origin.strip().isEmpty()
        && !destination.strip().isEmpty()) {
      String dotted =
          "<span style=\"display:inline-block;width:9px;margin:0 2px;"
              + "border-top:1px dotted #607889;vertical-align:.25em;\"></span>";
      String separator =
          "<span role=\"img\" aria-label=\"→\" style=\"color:#405a6b;"
              + "font-family:'Apple Symbols','Segoe UI Symbol','DejaVu Sans',sans-serif;"
              + "font-variant-emoji:text;font-size:.74em;font-weight:400;"
              + "letter-spacing:0;display:inline-block;white-space:nowrap;vertical-align:.16em;\">"
              + dotted + "&#x2708;&#xFE0E;" + dotted + "</span>";
      lane = escape(origin.strip()) + " " + separator + " " + escape(destination.strip());
    }
    var values =
        Map.of(
            "language", escape(language),
            "lane", lane,
            "message_html", String.join("\n              ", rendered),
            "cloud_image_url", CLOUDS);
    // Single pass like string.Template.substitute, so text in the mail is never re-expanded.
    return PLACEHOLDER
        .matcher(LAYOUT)
        .replaceAll(
            m -> {
              String value = values.get(m.group(1));
              if (value == null) throw new IllegalStateException("Unknown placeholder " + m.group());
              return Matcher.quoteReplacement(value);
            });
  }

  // Python's html.escape(quote=True).
  private static String escape(String text) {
    return text.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#x27;");
  }

  private static int occurrences(String text, String part) {
    int count = 0;
    for (int i = text.indexOf(part); i >= 0; i = text.indexOf(part, i + part.length())) count++;
    return count;
  }

  private static byte[] bytes(String resource) {
    try (InputStream in = Cloudline.class.getClassLoader().getResourceAsStream(resource)) {
      if (in == null) throw new IllegalStateException("Missing Cloudline resource " + resource);
      return in.readAllBytes();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String text(String resource) {
    return new String(bytes(resource), StandardCharsets.UTF_8);
  }
}
