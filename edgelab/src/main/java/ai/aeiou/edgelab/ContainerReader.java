package ai.aeiou.edgelab;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.sax.BodyContentHandler;
import org.xml.sax.ContentHandler;

/** Reads mail bodies and dispatches archive members and attachments in memory. */
final class ContainerReader implements FormatReader {
    private static final int MAX_PARTS = 32;
    private static final int MAX_DEPTH = 3;
    private static final int MAX_TEXT = 100_000;
    private final EdgeReader leaves;
    private final AutoDetectParser parser = new AutoDetectParser();

    ContainerReader(EdgeReader leaves) {
        this.leaves = leaves;
    }

    @Override
    public Reading read(String filename, byte[] bytes, String type) {
        return read(filename, bytes, type, new Budget(), 0);
    }

    private static final class Budget {
        int bytesLeft = 32 * 1024 * 1024;
        int partsLeft = MAX_PARTS;
    }

    private Reading read(String filename, byte[] bytes, String type, Budget budget, int depth) {
        if (depth >= MAX_DEPTH) return Reading.unreadable(type, "container nesting limit reached");
        List<Reading.Part> parts = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        boolean mail = type.equals("message/rfc822") || type.equals("application/vnd.ms-outlook");
        BodyContentHandler body = new BodyContentHandler(MAX_TEXT);
        ParseContext context = new ParseContext();
        context.set(Parser.class, parser);
        context.set(EmbeddedDocumentExtractor.class, new EmbeddedDocumentExtractor() {
            public boolean shouldParseEmbedded(Metadata metadata) {
                if (budget.partsLeft == 0 || budget.bytesLeft == 0 || text.length() >= MAX_TEXT) {
                    throw new IllegalStateException("container limit reached");
                }
                return true;
            }

            public void parseEmbedded(InputStream stream, ContentHandler handler, Metadata metadata,
                                      boolean outputHtml) throws java.io.IOException {
                if (budget.partsLeft <= 0 || budget.bytesLeft <= 0) throw new java.io.IOException("container limit reached");
                budget.partsLeft--;
                String name = metadata.get(TikaCoreProperties.RESOURCE_NAME_KEY);
                if (name == null || name.isBlank()) name = "part-" + (MAX_PARTS - budget.partsLeft);
                int limit = Math.min(8 * 1024 * 1024, budget.bytesLeft);
                byte[] member = stream.readNBytes(limit + 1);
                budget.bytesLeft = Math.max(0, budget.bytesLeft - member.length);
                String memberType = member.length > limit ? "application/octet-stream" : leaves.detect(name, member);
                Reading result;
                if (member.length > limit) {
                    parts.add(new Reading.Part(name, memberType, Reading.Status.UNREADABLE, 0));
                    notes.add(name + ": member exceeds size limit");
                    throw new java.io.IOException("member exceeds size limit");
                } else if (Router.slotFor(memberType) == Router.Slot.CONTAINER) {
                    result = ContainerReader.this.read(name, member, memberType, budget, depth + 1);
                } else {
                    result = leaves.read(name, member);
                }
                parts.add(new Reading.Part(name, result.detectedType(), result.status(), result.confidence()));
                if (result.status() != Reading.Status.OK) notes.add(name + ": " + String.join(", ", result.notes()));
                if (!result.text().isBlank()) append(text, "[" + name + "]\n" + result.text(), notes);
            }
        });
        Metadata metadata = new Metadata();
        metadata.set(Metadata.CONTENT_TYPE, type);
        if (filename != null) metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, filename);
        try (TikaInputStream stream = TikaInputStream.get(bytes)) {
            parser.parse(stream, body, metadata, context);
        } catch (Exception e) {
            notes.add("container parsing incomplete: " + e.getClass().getSimpleName());
        }
        if (mail && !body.toString().isBlank()) append(text, body.toString().strip(), notes);
        if (text.isEmpty()) {
            notes.add("no readable content");
            return new Reading("", 0, type, Reading.Status.UNREADABLE, parts, notes);
        }
        double confidence = parts.stream().filter(p -> p.status() != Reading.Status.UNREADABLE)
                .mapToDouble(Reading.Part::confidence).min().orElse(0.95);
        return new Reading(text.toString(), confidence, type,
                notes.isEmpty() ? Reading.Status.OK : Reading.Status.PARTIAL, parts, notes);
    }

    private static void append(StringBuilder out, String block, List<String> notes) {
        if (!out.isEmpty() && out.length() < MAX_TEXT) out.append('\n');
        int count = Math.min(MAX_TEXT - out.length(), block.length());
        out.append(block, 0, count);
        if (count < block.length()) notes.add("text limit reached");
    }
}
