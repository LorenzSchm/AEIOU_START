package ai.aeiou.edgelab.container;

import java.util.List;

import ai.aeiou.edgelab.EdgeReader;
import ai.aeiou.edgelab.FormatReader;
import ai.aeiou.edgelab.ReadSession;
import ai.aeiou.edgelab.Reading;

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
        Reading base = office.read(filename, bytes, detectedType);
        boolean hasEmbedded = base.notes().stream().anyMatch(n -> n.contains("embedded file(s) not opened"));
        if (!hasEmbedded) return base;
        if (ReadSession.current() == null) return new EdgeReader().read(filename, bytes);

        List<Embedded.Child> children;
        try {
            children = Embedded.collect(filename, bytes, detectedType, EdgeReader.DEFAULT_MAX_BYTES);
        } catch (Exception | LinkageError e) {
            return base; // the office text is still good; the embedded files stay unopened
        }
        Reading body = ContainerWalker.withoutEmbeddedNote(base);
        if (children.isEmpty()) return body;
        return ContainerWalker.walk(detectedType, body, children);
    }
}
