package ai.aeiou.edgelab.office;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.apache.tika.exception.EncryptedDocumentException;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.parser.ocr.TesseractOCRConfig;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.apache.tika.sax.BodyContentHandler;

import ai.aeiou.edgelab.FormatReader;
import ai.aeiou.edgelab.Reading;

/**
 * The Tika catch-all: Word, Excel, PowerPoint, OpenOffice, RTF, HTML, CSV, markdown,
 * text in any common encoding, PDF with a text layer, and the body text of eml/msg mails.
 *
 * <p>Table rows come out one per line, cells joined with " | ". Files inside the file
 * (attachments, embedded pictures) are not opened here; that is the container walker's job.
 *
 * <p>PDF pages with almost no text are flagged with the note {@code "page N looks scanned"}.
 * Ticket 05 can call {@link #extract} to get the page numbers and render those pages.
 */
public final class OfficeReader implements FormatReader {

    /** A PDF page with fewer visible characters than this is treated as a likely scan. */
    public static final int SCAN_THRESHOLD_CHARS = 20;

    /** Visible characters at which confidence reaches its top value. */
    static final int FULL_CONFIDENCE_CHARS = 30;

    private static final String OCTET_STREAM = "application/octet-stream";

    static final double HIGH_CONFIDENCE = 0.95;
    static final double LOW_CONFIDENCE = 0.3;

    /** One PDF page: its 1-based number, its text, and whether it looks like a scan. */
    public record Page(int number, String text, boolean likelyScanned) {}

    /** What Tika got out of the file. {@code pages} is empty for anything but PDF. */
    public record Extraction(String text, String tikaType, List<Page> pages) {
        public Extraction {
            text = text == null ? "" : text;
            pages = pages == null ? List.of() : List.copyOf(pages);
        }

        public List<Integer> likelyScannedPages() {
            return pages.stream().filter(Page::likelyScanned).map(Page::number).toList();
        }
    }

    private final AutoDetectParser parser = new AutoDetectParser();

    @Override
    public Reading read(String filename, byte[] bytes, String detectedType) {
        String type = detectedType;
        if (type != null && type.startsWith(OCTET_STREAM)) {
            // Detection found no known format. Single-byte text full of accents and the
            // euro sign (windows-1252) lands here; anything else is not guessed from its name.
            if (!TextBytes.looksLikeText(bytes)) return Reading.unreadable(type, "unknown file type");
            Reading r = toReading(new Extraction(TableTextHandler.tidy(TextBytes.decode(bytes)), "text/plain",
                    List.of()), "text/plain");
            List<String> notes = new ArrayList<>(r.notes());
            notes.add("no known format; read as text");
            return new Reading(r.text(), r.confidence(), r.detectedType(), r.status(), r.parts(), notes);
        }
        Extraction ex;
        List<String> skipped = new ArrayList<>();
        try {
            ex = extract(filename, bytes, detectedType, skipped);
        } catch (EncryptedDocumentException e) {
            return Reading.unreadable(orTika(type, null), "file is password protected");
        } catch (Exception | LinkageError e) {
            return Reading.unreadable(orTika(type, null), "could not read " + describe(type, filename)
                    + ": " + e.getClass().getSimpleName()
                    + (e.getMessage() != null ? ": " + firstLine(e.getMessage()) : ""));
        }
        type = orTika(type, ex.tikaType());
        Reading r = toReading(ex, type);
        if (skipped.isEmpty()) return r;
        List<String> notes = new ArrayList<>(r.notes());
        notes.add(skippedNote(skipped));
        return new Reading(r.text(), r.confidence(), r.detectedType(), r.status(), r.parts(), notes);
    }

    static String skippedNote(List<String> names) {
        String shown = String.join(", ", names.subList(0, Math.min(5, names.size())));
        if (names.size() > 5) shown += ", ...";
        return names.size() + " embedded file(s) not opened: " + shown;
    }

    /** Runs Tika and returns the text plus PDF page info. Throws on broken files. */
    public Extraction extract(String filename, byte[] bytes, String detectedType) throws Exception {
        return extract(filename, bytes, detectedType, new ArrayList<>());
    }

    private Extraction extract(String filename, byte[] bytes, String detectedType, List<String> skipped)
            throws Exception {
        Metadata metadata = new Metadata();
        if (filename != null && !filename.isBlank()) {
            metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, filename);
        }
        if (detectedType != null && !detectedType.isBlank()
                && !detectedType.startsWith(OCTET_STREAM)) {
            // The router already detected the real type from the bytes; make Tika use it.
            metadata.set(TikaCoreProperties.CONTENT_TYPE_USER_OVERRIDE, detectedType);
        }
        TableTextHandler handler = new TableTextHandler();
        try (TikaInputStream in = TikaInputStream.get(bytes)) {
            parser.parse(in, new BodyContentHandler(handler), metadata, context(skipped));
        }
        List<String> rawPages = handler.pages();
        List<Page> pages = new ArrayList<>();
        for (int i = 0; i < rawPages.size(); i++) {
            String pageText = TableTextHandler.tidy(rawPages.get(i));
            pages.add(new Page(i + 1, pageText, visibleChars(pageText) < SCAN_THRESHOLD_CHARS));
        }
        return new Extraction(handler.text(), metadata.get(Metadata.CONTENT_TYPE), pages);
    }

    private ParseContext context(List<String> skipped) {
        ParseContext context = new ParseContext();
        // Mail bodies are parsed through this; attachments go through the extractor below.
        context.set(Parser.class, parser);
        // Numbers as written in English (2.85, not 2,85), whatever the laptop's locale.
        context.set(Locale.class, Locale.US);
        // No OCR here: scanned pages go to the photo reader (ticket 05).
        TesseractOCRConfig ocr = new TesseractOCRConfig();
        ocr.setSkipOcr(true);
        context.set(TesseractOCRConfig.class, ocr);
        PDFParserConfig pdf = new PDFParserConfig();
        pdf.setOcrStrategy(PDFParserConfig.OCR_STRATEGY.NO_OCR);
        pdf.setExtractInlineImages(false);
        context.set(PDFParserConfig.class, pdf);
        // Attachments and embedded files are the container walker's job (ticket 07).
        context.set(EmbeddedDocumentExtractor.class, new SkipEmbedded(skipped));
        return context;
    }

    static Reading toReading(Extraction ex, String type) {
        String text = ex.text();
        int visible = visibleChars(text);
        List<String> notes = new ArrayList<>();
        List<Integer> scanned = ex.likelyScannedPages();
        for (int n : scanned) notes.add("page " + n + " looks scanned");

        if (visible == 0) {
            notes.add(scanned.isEmpty() ? "no text found" : "no text layer");
            return new Reading("", 0.0, type, Reading.Status.UNREADABLE, List.of(), notes);
        }
        double confidence = confidenceFor(visible);
        Reading.Status status = Reading.Status.OK;
        if (!scanned.isEmpty()) {
            // Some pages had no text: what we have is real, but part of the file is missing.
            status = Reading.Status.PARTIAL;
            confidence *= (double) (ex.pages().size() - scanned.size()) / ex.pages().size();
        }
        if (visible < SCAN_THRESHOLD_CHARS) notes.add("very little text (" + visible + " characters)");
        return new Reading(text, confidence, type, status, List.of(), notes);
    }

    /** High when real text came out, low when almost nothing did. */
    static double confidenceFor(int visibleChars) {
        if (visibleChars <= 0) return 0.0;
        double share = Math.min(1.0, (double) visibleChars / FULL_CONFIDENCE_CHARS);
        return LOW_CONFIDENCE + (HIGH_CONFIDENCE - LOW_CONFIDENCE) * share;
    }

    static int visibleChars(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (Character.isLetterOrDigit(s.charAt(i))) n++;
        }
        return n;
    }

    private static String orTika(String detected, String tikaType) {
        if (detected != null && !detected.isBlank()) return detected;
        return tikaType != null ? tikaType : OCTET_STREAM;
    }

    private static String describe(String type, String filename) {
        if (type != null && !type.isBlank()) return type.toLowerCase(Locale.ROOT);
        return filename != null ? filename : "file";
    }

    private static String firstLine(String s) {
        int nl = s.indexOf('\n');
        String line = nl < 0 ? s : s.substring(0, nl);
        return line.length() > 200 ? line.substring(0, 200) + "..." : line;
    }

    /** Leaves attachments and embedded files unopened. */
    private static final class SkipEmbedded implements EmbeddedDocumentExtractor {
        private final List<String> skipped;

        SkipEmbedded(List<String> skipped) {
            this.skipped = skipped;
        }

        @Override
        public boolean shouldParseEmbedded(Metadata metadata) {
            String name = metadata.get(TikaCoreProperties.RESOURCE_NAME_KEY);
            if (name != null && name.contains("docProps/thumbnail")) return false; // preview image, not content
            skipped.add(name != null ? name : "unnamed " + metadata.get(Metadata.CONTENT_TYPE));
            return false;
        }

        @Override
        public void parseEmbedded(java.io.InputStream stream, org.xml.sax.ContentHandler handler,
                                  Metadata metadata, boolean outputHtml) {
            // never called: shouldParseEmbedded is always false
        }
    }
}
