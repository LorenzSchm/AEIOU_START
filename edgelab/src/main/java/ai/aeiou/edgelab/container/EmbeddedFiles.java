package ai.aeiou.edgelab.container;

import java.util.List;

import ai.aeiou.edgelab.FormatReader;
import ai.aeiou.edgelab.ReadSession;
import ai.aeiou.edgelab.Reading;
import ai.aeiou.edgelab.Router;

/**
 * Wraps the office slot: files that hold other files (pictures in a pptx or docx, a sheet
 * in a Word file, an attachment in a PDF) get those files read through the entry point.
 * Files without embedded content pass through unchanged.
 */
final class EmbeddedFiles implements FormatReader {

    private final FormatReader office;

    EmbeddedFiles(FormatReader office) {
        this.office = office;
    }

    @Override
    public Reading read(String filename, byte[] bytes, String detectedType) throws Exception {
        ReadSession session = ReadSession.current();
        // Called directly, outside the entry point: go through it once (before reading anything, so
        // the office slot, and any gateway call in it, runs only once), with this reader in the office slot.
        if (session == null) {
            return ContainerWalker.outsideSession(Router.standard().withOffice(this), filename, bytes);
        }
        Reading base = office.read(filename, bytes, detectedType);
        boolean hasEmbedded = base.notes().stream().anyMatch(n -> n.contains("embedded file(s) not opened"));
        if (!hasEmbedded) return base;

        List<Embedded.Child> children;
        try {
            Embedded.Collected collected = Embedded.collect(filename, bytes, detectedType, session.maxBytes());
            children = collected.children();
            ContainerWalker.noteSkipped(session, collected);
        } catch (Exception | LinkageError e) {
            return base; // the office text is still good; the embedded files stay unopened
        }
        Reading body = ContainerWalker.withoutEmbeddedNote(base);
        if (children.isEmpty()) return body;
        return ContainerWalker.walk(detectedType, body, children);
    }
}
