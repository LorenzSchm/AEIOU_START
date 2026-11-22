package ai.aeiou.edgelab.container;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.apache.tika.exception.EncryptedDocumentException;

import ai.aeiou.edgelab.EdgeReader;
import ai.aeiou.edgelab.FormatReader;
import ai.aeiou.edgelab.ReadSession;
import ai.aeiou.edgelab.Reading;
import ai.aeiou.edgelab.Router;
import ai.aeiou.edgelab.office.OfficeReader;

/**
 * The container slot: zip, tar, gz, 7z, rar, eml and msg. Lists the files inside and sends
 * each one back through {@link EdgeReader#read} (so a photo in a zip reaches the photo
 * reader, a zip in a mail is opened again). Mail body text comes from the office reader.
 *
 * <p>Each part's text sits under a header naming it ({@link ReadSession#header}), e.g.
 * {@code === part: forwarded.eml > packing-list.xlsx ===}. Every part, at any depth, gets an
 * entry in {@code parts} with its own status and confidence. Limits: {@link ReadSession#MAX_DEPTH}
 * levels, {@link ReadSession#MAX_FILES} files per top-level read, and the shared time budget;
 * what is left out is listed in notes and makes the result {@code partial}.
 *
 * <p>{@link #openEmbedded} wraps the office slot the same way, for pictures in docx/pptx.
 */
public final class ContainerWalker implements FormatReader {

    /** Pictures smaller than this are icons, bullets or logos: not worth a gateway call. */
    static final int MIN_PICTURE_BYTES = 2048;
    /** At most this many names per "not read" note. */
    private static final int NAMES_PER_NOTE = 5;

    private final OfficeReader mailBody;

    public ContainerWalker() {
        this(new OfficeReader());
    }

    public ContainerWalker(OfficeReader mailBody) {
        this.mailBody = mailBody;
    }

    /**
     * The office slot with embedded files opened: when the office reader reports
     * "embedded file(s) not opened" (pictures in a pptx, a sheet in a docx), each one is
     * read through the entry point and added under its own header.
     */
    public static FormatReader openEmbedded(FormatReader office) {
        return new EmbeddedFiles(office);
    }

    @Override
    public Reading read(String filename, byte[] bytes, String detectedType) {
        ReadSession session = ReadSession.current();
        // Called directly, outside the entry point: go through it once so a session (budget, limits) exists,
        // with this walker in the container slot.
        if (session == null) return outsideSession(Router.standard().withContainer(this), filename, bytes);
        Embedded.Collected collected;
        try {
            collected = Embedded.collect(filename, bytes, detectedType, session.maxBytes());
        } catch (EncryptedDocumentException e) {
            return Reading.unreadable(detectedType, (isMail(detectedType) ? "mail" : "archive")
                    + " is password protected");
        } catch (Exception | LinkageError e) {
            return Reading.unreadable(detectedType, "could not open " + kind(detectedType) + ": "
                    + e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + firstLine(e.getMessage()) : ""));
        }
        List<Embedded.Child> children = collected.children();
        noteSkipped(session, collected);
        Reading body = null;
        if (isMail(detectedType)) {
            body = withoutEmbeddedNote(mailBody.read(filename, bytes, detectedType));
            // The office reader already read the body; Tika also lists it as a part.
            children = children.stream().filter(c -> !c.mailBodyText()).toList();
            if (children.isEmpty()) return body;
        } else if (children.isEmpty()) {
            return Reading.unreadable(detectedType, collected.incomplete()
                    ? "archive not read: " + String.join("; ", collected.notes())
                    : "archive is empty or broken");
        }
        return walk(detectedType, body, children);
    }

    /**
     * Reads {@code children} through the entry point under the current session.
     * {@code body} is the file's own text (mail body, slide text), or null for archives.
     */
    static Reading walk(String detectedType, Reading body, List<Embedded.Child> children) {
        ReadSession s = ReadSession.current();
        boolean top = s.depth() == 0;
        String here = s.currentPath();
        int sectionsBefore = s.sectionCount();
        int partsBefore = s.partCount();
        boolean partial = false;
        List<String> notes = new ArrayList<>();

        if (body != null) {
            s.addSection(top ? null : here, body.text(), body.confidence());
            // "no text found" in a slide that is only a picture says nothing useful here.
            if (body.status() != Reading.Status.UNREADABLE) notes.addAll(body.notes());
            if (body.status() == Reading.Status.PARTIAL) partial = true;
        }

        List<String> tooDeep = new ArrayList<>();
        List<String> overLimit = new ArrayList<>();
        List<String> late = new ArrayList<>();
        List<String> ignoredPictures = new ArrayList<>();
        for (Embedded.Child c : children) {
            String p = s.pathTo(c.name());
            if (isIgnorablePicture(c)) {
                ignoredPictures.add(p);
                continue;
            }
            if (c.bytes() == null) {
                notes.add(p + ": not read, too large (over " + s.maxBytes() + " bytes)");
                partial = true;
                continue;
            }
            if (s.expired()) { late.add(p); continue; }
            if (s.depth() + 1 > ReadSession.MAX_DEPTH) { tooDeep.add(p); continue; }
            if (!s.takeFileSlot()) { overLimit.add(p); continue; }

            int index = s.addPart(new Reading.Part(p, "application/octet-stream", Reading.Status.UNREADABLE, 0));
            int sectionsBeforeChild = s.sectionCount();
            Reading r = s.readPart(c.name(), c.bytes());
            s.setPart(index, new Reading.Part(p, r.detectedType(), r.status(), r.confidence()));
            if (s.sectionCount() == sectionsBeforeChild) {
                // A plain file (or a container that gave nothing): add its text and notes here.
                s.addSection(p, r.text(), r.confidence());
                for (String n : r.notes()) s.addNote(p + ": " + n);
            }
        }
        addNamesNote(notes, tooDeep, "not read, deeper than " + ReadSession.MAX_DEPTH + " levels");
        addNamesNote(notes, overLimit, "not read, limit of " + ReadSession.MAX_FILES + " files reached");
        addNamesNote(notes, late, "not read, time budget used up");
        addNamesNote(notes, ignoredPictures, "small or vector picture(s) not read");
        if (!tooDeep.isEmpty() || !overLimit.isEmpty() || !late.isEmpty()) partial = true;

        for (String n : notes) s.addNote(top ? n : here + ": " + n);
        if (partial) s.markIncomplete();
        return top ? s.result(detectedType) : s.summarySince(sectionsBefore, partsBefore, detectedType, partial);
    }

    /** Entries the collector skipped (limits, budget) are listed and make the read partial. */
    static void noteSkipped(ReadSession s, Embedded.Collected collected) {
        if (!collected.incomplete()) return;
        String here = s.currentPath();
        for (String n : collected.notes()) s.addNote(here.isEmpty() ? n : here + ": " + n);
        s.markIncomplete();
    }

    /** One read through a fresh entry point with {@code router}; it opens the session. */
    static Reading outsideSession(Router router, String filename, byte[] bytes) {
        return new EdgeReader(router, EdgeReader.DEFAULT_MAX_BYTES).read(filename, bytes);
    }

    private static void addNamesNote(List<String> notes, List<String> names, String what) {
        if (names.isEmpty()) return;
        String shown = String.join(", ", names.subList(0, Math.min(NAMES_PER_NOTE, names.size())));
        if (names.size() > NAMES_PER_NOTE) shown += ", ... (" + (names.size() - NAMES_PER_NOTE) + " more)";
        notes.add(names.size() + " part(s) " + what + ": " + shown);
    }

    static boolean isIgnorablePicture(Embedded.Child c) {
        String type = c.tikaType() == null ? "" : c.tikaType().toLowerCase(Locale.ROOT);
        String name = c.name().toLowerCase(Locale.ROOT);
        boolean vector = type.contains("emf") || type.contains("wmf")
                || name.endsWith(".emf") || name.endsWith(".wmf");
        if (vector) return true;
        boolean picture = type.startsWith("image/")
                || name.matches(".*\\.(png|jpe?g|gif|bmp|tiff?|webp|heic|heif)$");
        return picture && c.bytes() != null && c.bytes().length < MIN_PICTURE_BYTES;
    }

    static Reading withoutEmbeddedNote(Reading r) {
        List<String> notes = r.notes().stream().filter(n -> !n.contains("embedded file(s) not opened")).toList();
        return new Reading(r.text(), r.confidence(), r.detectedType(), r.status(), r.parts(), notes);
    }

    static boolean isMail(String type) {
        String t = type == null ? "" : type.toLowerCase(Locale.ROOT);
        return t.startsWith("message/rfc822") || t.startsWith("application/vnd.ms-outlook");
    }

    private static String kind(String type) {
        return isMail(type) ? "mail" : "archive";
    }

    private static String firstLine(String s) {
        int nl = s.indexOf('\n');
        String line = nl < 0 ? s : s.substring(0, nl);
        return line.length() > 200 ? line.substring(0, 200) + "..." : line;
    }
}
