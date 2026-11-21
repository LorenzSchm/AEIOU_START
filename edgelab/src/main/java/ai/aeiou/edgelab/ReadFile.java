package ai.aeiou.edgelab;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Command line: read one file from disk and print the Reading. */
public final class ReadFile {

    public static void main(String[] args) throws Exception {
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        if (args.length != 1) {
            out.println("usage: read <file>");
            System.exit(2);
        }
        Path path = Path.of(args[0]);
        if (!Files.isRegularFile(path)) {
            out.println("not a file: " + path.toAbsolutePath());
            System.exit(2);
        }
        Reading r = new EdgeReader().read(path.getFileName().toString(), Files.readAllBytes(path));
        out.println(format(path.getFileName().toString(), r));
    }

    static String format(String name, Reading r) {
        StringBuilder sb = new StringBuilder();
        sb.append("file:       ").append(name).append('\n');
        sb.append("type:       ").append(r.detectedType()).append('\n');
        sb.append("status:     ").append(r.status().name().toLowerCase()).append('\n');
        sb.append("confidence: ").append(String.format(java.util.Locale.ROOT, "%.2f", r.confidence())).append('\n');
        for (Reading.Part p : r.parts()) {
            sb.append("part:       ").append(p.name()).append(" (").append(p.detectedType()).append(", ")
              .append(p.status().name().toLowerCase()).append(", ")
              .append(String.format(java.util.Locale.ROOT, "%.2f", p.confidence())).append(")\n");
        }
        for (String n : r.notes()) sb.append("note:       ").append(n).append('\n');
        sb.append("----- text -----\n").append(r.text());
        return sb.toString();
    }
}
