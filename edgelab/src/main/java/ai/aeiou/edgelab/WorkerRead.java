package ai.aeiou.edgelab;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.pdfbox.Loader;

/** File-based protocol for the isolated worker process. An empty output means hold. */
public final class WorkerRead {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Expected filename, input and output");
        byte[] input = Files.readAllBytes(Path.of(args[1]));
        if (input.length == 0 || input.length > 2_000_000) return;
        Router router = Router.standard();
        FormatReader office = router.readerFor("application/pdf");
        router = router.withOffice((name, bytes, type) -> {
            if ("application/pdf".equals(type)) {
                try (var pdf = Loader.loadPDF(bytes)) {
                    if (pdf.getNumberOfPages() > 20) return Reading.unreadable(type, "PDF page limit");
                }
            }
            return office.read(name, bytes, type);
        });
        String text = acceptedText(new EdgeReader(router, 2_000_000).read(args[0], input));
        if (text != null) Files.writeString(Path.of(args[2]), text);
    }

    static String acceptedText(Reading r) {
        if (r.status() != Reading.Status.OK || r.text().length() > 100_000 || r.text().isBlank()) return null;
        var slot = Router.slotFor(r.detectedType());
        if (slot != Router.Slot.VIDEO && r.parts().stream().anyMatch(p -> p.status() != Reading.Status.OK)) return null;
        if (r.notes().stream().anyMatch(n -> n.contains("not read") || n.contains("not opened"))) return null;
        String text = r.text();
        // A packing declaration or a bid must come from a single document. Strip the
        // generated path so an archive member's filename cannot supply shipment facts.
        if (slot == Router.Slot.CONTAINER && !r.parts().isEmpty()) {
            List<Reading.Part> leaves = r.parts().stream()
                    .filter(p -> Router.slotFor(p.detectedType()) != Router.Slot.CONTAINER).toList();
            if (leaves.size() != 1) return null;
            String header = ReadSession.header(leaves.getFirst().name()) + "\n";
            if (!text.startsWith(header)) return null;
            text = text.substring(header.length());
        } else if (slot != Router.Slot.VIDEO && !r.parts().isEmpty()) {
            return null;
        }
        if (slot == Router.Slot.VIDEO) text = text.replaceFirst("^What is (?:shown|said):\\n", "");
        return text;
    }
}
