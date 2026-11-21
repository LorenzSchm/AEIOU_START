package ai.aeiou.edgelab.photo;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import ai.aeiou.edgelab.FormatReader;
import ai.aeiou.edgelab.Reading;
import ai.aeiou.edgelab.office.OfficeReader;

/**
 * The office slot with scanned PDF pages filled in. Runs the {@link OfficeReader} as
 * before; when it flags PDF pages as "looks scanned", those pages are rendered with
 * PDFBox and read by the {@link PhotoReader}. Their text is merged into the PDF's
 * Reading in page order. Everything that is not a scanned PDF passes through unchanged.
 */
public final class ScanAwareOfficeReader implements FormatReader {

    /** At most this many scanned pages go to the gateway per file (each one costs points). */
    public static final int MAX_SCANNED_PAGES = 10;
    /** Render resolution; an A4 page at 150 dpi is ~1240x1754 before the photo reader shrinks it. */
    static final float RENDER_DPI = 150f;
    /** Confidence of a page that had a real text layer. */
    static final double TEXT_PAGE_CONFIDENCE = 0.95;

    private final OfficeReader office;
    private final PhotoReader photo;

    public ScanAwareOfficeReader(OfficeReader office, PhotoReader photo) {
        this.office = office;
        this.photo = photo;
    }

    public static ScanAwareOfficeReader standard() {
        return new ScanAwareOfficeReader(new OfficeReader(), PhotoReader.standard());
    }

    @Override
    public Reading read(String filename, byte[] bytes, String detectedType) throws Exception {
        Reading base = office.read(filename, bytes, detectedType);
        boolean scanned = base.notes().stream().anyMatch(n -> n.endsWith("looks scanned"));
        if (!scanned || !base.detectedType().startsWith("application/pdf")) return base;

        OfficeReader.Extraction ex;
        try {
            ex = office.extract(filename, bytes, detectedType);
        } catch (Exception e) {
            return base;
        }

        List<String> texts = new ArrayList<>();
        List<String> pageNotes = new ArrayList<>();
        Set<String> photoNotes = new LinkedHashSet<>();
        double confidenceSum = 0;
        int missing = 0;
        int sent = 0;

        try (PDDocument doc = Loader.loadPDF(bytes)) {
            PDFRenderer renderer = new PDFRenderer(doc);
            for (OfficeReader.Page page : ex.pages()) {
                if (!page.likelyScanned()) {
                    if (!page.text().isBlank()) texts.add(page.text().strip());
                    confidenceSum += TEXT_PAGE_CONFIDENCE;
                    continue;
                }
                if (sent >= MAX_SCANNED_PAGES || page.number() > doc.getNumberOfPages()) {
                    pageNotes.add("page " + page.number() + " looks scanned; not read (limit "
                            + MAX_SCANNED_PAGES + " scanned pages per file)");
                    missing++;
                    continue;
                }
                sent++;
                Reading r = readPage(renderer, page.number());
                if (r.status() == Reading.Status.UNREADABLE) {
                    pageNotes.add("page " + page.number() + " looks scanned");
                    String reason = r.notes().isEmpty() ? "no text" : r.notes().get(r.notes().size() - 1);
                    pageNotes.add("page " + page.number() + " not read by the photo reader: " + reason);
                    if (!page.text().isBlank()) texts.add(page.text().strip());
                    missing++;
                    continue;
                }
                texts.add(r.text());
                confidenceSum += r.confidence();
                pageNotes.add("page " + page.number() + " was a scan, read by the photo reader");
                r.notes().stream().filter(n -> n.startsWith("vision model")).forEach(photoNotes::add);
            }
        }

        List<String> notes = new ArrayList<>();
        for (String n : base.notes()) {
            if (n.endsWith("looks scanned") || n.equals("no text layer") || n.startsWith("very little text")) continue;
            notes.add(n);
        }
        notes.addAll(pageNotes);
        notes.addAll(photoNotes);

        String text = String.join("\n\n", texts);
        int pages = Math.max(1, ex.pages().size());
        if (PhotoReader.visibleChars(text) == 0) {
            notes.add("no text found");
            return new Reading("", 0.0, base.detectedType(), Reading.Status.UNREADABLE, List.of(), notes);
        }
        Reading.Status status = missing == 0 ? Reading.Status.OK : Reading.Status.PARTIAL;
        return new Reading(text, confidenceSum / pages, base.detectedType(), status, List.of(), notes);
    }

    private Reading readPage(PDFRenderer renderer, int pageNumber) {
        BufferedImage image;
        try {
            image = renderer.renderImageWithDPI(pageNumber - 1, RENDER_DPI, ImageType.RGB);
        } catch (Exception | OutOfMemoryError e) {
            return Reading.unreadable("application/pdf", "page could not be rendered: " + e.getClass().getSimpleName());
        }
        // An empty page has nothing to copy; don't pay the gateway for it.
        if (isBlank(image)) return Reading.unreadable("application/pdf", "page is blank, not sent");
        return photo.readImage(image, "application/pdf");
    }

    /** Fewer than 1 in 1000 sampled pixels darker than light grey. */
    static boolean isBlank(BufferedImage img) {
        int step = Math.max(1, Math.min(img.getWidth(), img.getHeight()) / 400);
        long samples = 0;
        long dark = 0;
        for (int y = 0; y < img.getHeight(); y += step) {
            for (int x = 0; x < img.getWidth(); x += step) {
                int rgb = img.getRGB(x, y);
                int luma = (((rgb >> 16) & 0xFF) * 299 + ((rgb >> 8) & 0xFF) * 587 + (rgb & 0xFF) * 114) / 1000;
                samples++;
                if (luma < 200) dark++;
            }
        }
        return dark * 1000 < samples;
    }
}
