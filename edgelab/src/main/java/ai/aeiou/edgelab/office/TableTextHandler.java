package ai.aeiou.edgelab.office;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import org.xml.sax.Attributes;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Turns Tika's XHTML into plain text, keeping each table row on one line
 * (cells joined with " | ") so numbers stay in their columns. Also cuts the
 * text into PDF pages ({@code <div class="page">}) so near-empty pages can be flagged.
 */
final class TableTextHandler extends DefaultHandler {

    static final String CELL_SEPARATOR = " | ";

    private final StringBuilder out = new StringBuilder();

    // Table state. Nested tables are flattened into the outer cell.
    private int tableDepth;
    private List<String> row;
    private StringBuilder cell;
    private int cellDepth;

    // PDF page state: one entry per open <div>, true when it is a page div.
    private final Deque<Boolean> divs = new ArrayDeque<>();
    private int pageStart = -1;
    private final List<String> pages = new ArrayList<>();

    @Override
    public void startElement(String uri, String localName, String qName, Attributes atts) {
        String name = name(localName, qName);
        switch (name) {
            case "table" -> tableDepth++;
            case "tr" -> {
                if (cellDepth == 0) row = new ArrayList<>();
            }
            case "td", "th" -> {
                if (cellDepth++ == 0) {
                    if (row == null) row = new ArrayList<>();
                    cell = new StringBuilder();
                } else {
                    cell.append(' ');
                }
            }
            case "div" -> {
                boolean page = "page".equals(atts.getValue("class"));
                divs.push(page);
                if (page) pageStart = out.length();
            }
            case "br" -> text(" \n");
            default -> { }
        }
    }

    @Override
    public void endElement(String uri, String localName, String qName) {
        String name = name(localName, qName);
        switch (name) {
            case "td", "th" -> {
                if (cellDepth > 0 && --cellDepth == 0) {
                    row.add(cell.toString().replaceAll("\\s+", " ").trim());
                    cell = null;
                } else if (cell != null) {
                    cell.append(' ');
                }
            }
            case "tr" -> {
                if (cellDepth == 0 && row != null) {
                    endRow();
                }
            }
            case "div" -> {
                Boolean page = divs.isEmpty() ? Boolean.FALSE : divs.pop();
                if (page && pageStart >= 0) {
                    pages.add(out.substring(pageStart));
                    pageStart = -1;
                }
            }
            case "p", "li", "h1", "h2", "h3", "h4", "h5", "h6", "table", "title" -> text("\n");
            default -> { }
        }
    }

    private void endRow() {
        int last = row.size();
        while (last > 0 && row.get(last - 1).isEmpty()) last--; // drop trailing empty cells
        if (last > 0) {
            ensureLineStart();
            out.append(String.join(CELL_SEPARATOR, row.subList(0, last))).append('\n');
        }
        row = null;
    }

    @Override
    public void characters(char[] ch, int start, int length) {
        text(new String(ch, start, length));
    }

    @Override
    public void ignorableWhitespace(char[] ch, int start, int length) {
        text(new String(ch, start, length));
    }

    private void text(String s) {
        if (cell != null) {
            cell.append(s);
        } else if (row == null && (tableDepth == 0 || !s.isBlank())) {
            out.append(s);
        }
        // Whitespace between rows and cells of a table is dropped, so rows stay together.
    }

    private void ensureLineStart() {
        if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') out.append('\n');
    }

    private static String name(String localName, String qName) {
        String n = localName != null && !localName.isEmpty() ? localName : qName;
        int colon = n.indexOf(':');
        return (colon >= 0 ? n.substring(colon + 1) : n).toLowerCase(java.util.Locale.ROOT);
    }

    /** The whole text, with trailing spaces removed and runs of blank lines squeezed. */
    String text() {
        return tidy(out.toString());
    }

    /** Raw text of each PDF page in order (empty for non-PDF files). */
    List<String> pages() {
        return List.copyOf(pages);
    }

    static String tidy(String s) {
        String t = s.replace("\r\n", "\n").replace('\r', '\n')
                .replace(' ', ' ')
                .replaceAll("[ \\t]+\\n", "\n")
                .replaceAll("\\n{3,}", "\n\n");
        return t.strip();
    }
}
