package ai.aeiou.edgelab.container;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.ocr.TesseractOCRConfig;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.xml.sax.ContentHandler;
import org.xml.sax.helpers.DefaultHandler;

import ai.aeiou.edgelab.ReadSession;

/**
 * Lists the files directly inside one file, with their bytes: zip/tar/gz/7z/rar entries,
 * mail attachments (eml, msg, also forwarded mails), pictures and objects embedded in
 * Word, PowerPoint, Excel or OpenOffice files. Uses Tika's parsers; nothing is opened
 * further here (the walker sends each child back through the read entry point).
 */
final class Embedded {

    /**
     * One child. {@code bytes} is null when it was larger than the size limit.
     * {@code tikaType} is what the parent said the child is (may be null). {@code mailBodyText}
     * marks an unnamed text part without Content-Disposition: a mail's own body, not an attachment.
     */
    record Child(String name, String tikaType, byte[] bytes, long size, boolean mailBodyText) {}

    private static final String OCTET_STREAM = "application/octet-stream";

    private Embedded() {}

    /**
     * Caps on what one {@link #collect} keeps in memory, so a zip bomb (thousands of entries,
     * or a few that inflate to gigabytes) cannot exhaust the heap before the walker's own
     * limits apply. The walker reads at most {@link ReadSession#MAX_FILES} parts anyway; the
     * entry cap is a little higher because small icons are dropped later without using a slot.
     */
    record Limits(int maxEntries, long maxTotalBytes) {
        static final Limits DEFAULT = new Limits(5 * ReadSession.MAX_FILES, 100L * 1024 * 1024);
    }

    /** The children kept, and notes on what was skipped while collecting (empty when nothing was). */
    record Collected(List<Child> children, List<String> notes) {
        boolean incomplete() { return !notes.isEmpty(); }
    }

    /** Throws on broken or password-protected files (Tika's exceptions). */
    static Collected collect(String filename, byte[] bytes, String detectedType, long maxBytes)
            throws Exception {
        return collect(filename, bytes, detectedType, maxBytes, Limits.DEFAULT);
    }

    static Collected collect(String filename, byte[] bytes, String detectedType, long maxBytes, Limits limits)
            throws Exception {
        Metadata metadata = new Metadata();
        if (filename != null && !filename.isBlank()) {
            metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, filename);
        }
        if (detectedType != null && !detectedType.isBlank() && !detectedType.startsWith(OCTET_STREAM)) {
            metadata.set(TikaCoreProperties.CONTENT_TYPE_USER_OVERRIDE, detectedType);
        }
        List<Child> children = new ArrayList<>();
        ParseContext context = new ParseContext();
        TesseractOCRConfig ocr = new TesseractOCRConfig();
        ocr.setSkipOcr(true);
        context.set(TesseractOCRConfig.class, ocr);
        PDFParserConfig pdf = new PDFParserConfig();
        pdf.setOcrStrategy(PDFParserConfig.OCR_STRATEGY.NO_OCR);
        pdf.setExtractInlineImages(false);
        context.set(PDFParserConfig.class, pdf);
        Collector collector = new Collector(children, maxBytes, limits);
        context.set(EmbeddedDocumentExtractor.class, collector);
        try (TikaInputStream in = TikaInputStream.get(bytes)) {
            new AutoDetectParser().parse(in, new DefaultHandler(), metadata, context);
        }
        return new Collected(children, collector.notes());
    }

    /** Keeps each embedded file's bytes instead of parsing it, within {@link Limits} and the time budget. */
    private static final class Collector implements EmbeddedDocumentExtractor {
        private final List<Child> children;
        private final long maxBytes;
        private final Limits limits;
        private long totalBytes;
        private int overCount;
        private int overTotal;
        private int late;

        Collector(List<Child> children, long maxBytes, Limits limits) {
            this.children = children;
            this.maxBytes = maxBytes;
            this.limits = limits;
        }

        @Override
        public boolean shouldParseEmbedded(Metadata metadata) {
            String name = metadata.get(TikaCoreProperties.RESOURCE_NAME_KEY);
            // The preview picture Office saves of the first page is not content.
            if (name != null && name.contains("docProps/thumbnail")) return false;
            // Past a limit nothing more is read: Tika skips the entry without handing us its bytes.
            if (ReadSession.stopRequested()) { late++; return false; }
            if (children.size() >= limits.maxEntries()) { overCount++; return false; }
            if (totalBytes >= limits.maxTotalBytes()) { overTotal++; return false; }
            return true;
        }

        @Override
        public void parseEmbedded(InputStream stream, ContentHandler handler, Metadata metadata,
                                  boolean outputHtml) throws IOException {
            String type = metadata.get(Metadata.CONTENT_TYPE);
            String name = nameOf(metadata, type, children.size() + 1);
            boolean bodyText = metadata.get(TikaCoreProperties.RESOURCE_NAME_KEY) == null
                    && metadata.get("Content-Disposition") == null
                    && type != null && type.toLowerCase(Locale.ROOT).startsWith("text/");
            long room = limits.maxTotalBytes() - totalBytes;
            long cap = Math.min(maxBytes, room);
            byte[] data = stream.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, cap + 1));
            if (data.length > maxBytes) {
                // Not drained to measure it: a bomb entry would inflate for nothing. Size is "more than this".
                children.add(new Child(name, type, null, data.length, bodyText));
            } else if (data.length > room) {
                overTotal++;
            } else {
                totalBytes += data.length;
                children.add(new Child(name, type, data, data.length, bodyText));
            }
        }

        List<String> notes() {
            List<String> notes = new ArrayList<>();
            if (overCount > 0) notes.add(overCount + " embedded file(s) not read, limit of "
                    + limits.maxEntries() + " files per container reached");
            if (overTotal > 0) notes.add(overTotal + " embedded file(s) not read, container content over "
                    + limits.maxTotalBytes() + " bytes in total");
            if (late > 0) notes.add(late + " embedded file(s) not read, time budget used up");
            return notes;
        }

        private static String nameOf(Metadata metadata, String type, int index) {
            String name = metadata.get(TikaCoreProperties.RESOURCE_NAME_KEY);
            if (name == null || name.isBlank()) name = metadata.get(TikaCoreProperties.EMBEDDED_RELATIONSHIP_ID);
            if (name != null && !name.isBlank()) return name.strip();
            String t = type == null ? "" : type.toLowerCase(Locale.ROOT);
            if (t.startsWith("message/rfc822")) return "attached-mail-" + index + ".eml";
            return "part-" + index;
        }
    }
}
