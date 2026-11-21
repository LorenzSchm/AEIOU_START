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

    /** Throws on broken or password-protected files (Tika's exceptions). */
    static List<Child> collect(String filename, byte[] bytes, String detectedType, long maxBytes)
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
        context.set(EmbeddedDocumentExtractor.class, new Collector(children, maxBytes));
        try (TikaInputStream in = TikaInputStream.get(bytes)) {
            new AutoDetectParser().parse(in, new DefaultHandler(), metadata, context);
        }
        return children;
    }

    /** Keeps each embedded file's bytes instead of parsing it. */
    private static final class Collector implements EmbeddedDocumentExtractor {
        private final List<Child> children;
        private final long maxBytes;

        Collector(List<Child> children, long maxBytes) {
            this.children = children;
            this.maxBytes = maxBytes;
        }

        @Override
        public boolean shouldParseEmbedded(Metadata metadata) {
            String name = metadata.get(TikaCoreProperties.RESOURCE_NAME_KEY);
            // The preview picture Office saves of the first page is not content.
            return name == null || !name.contains("docProps/thumbnail");
        }

        @Override
        public void parseEmbedded(InputStream stream, ContentHandler handler, Metadata metadata,
                                  boolean outputHtml) throws IOException {
            String type = metadata.get(Metadata.CONTENT_TYPE);
            String name = nameOf(metadata, type, children.size() + 1);
            boolean bodyText = metadata.get(TikaCoreProperties.RESOURCE_NAME_KEY) == null
                    && metadata.get("Content-Disposition") == null
                    && type != null && type.toLowerCase(Locale.ROOT).startsWith("text/");
            byte[] data = stream.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, maxBytes + 1));
            if (data.length > maxBytes) {
                long size = data.length;
                byte[] buf = new byte[8192];
                for (int n; (n = stream.read(buf)) > 0; ) size += n;
                children.add(new Child(name, type, null, size, bodyText));
            } else {
                children.add(new Child(name, type, data, data.length, bodyText));
            }
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
