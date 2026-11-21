package ai.aeiou.edgelab.alarm;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * No database needed: scans every Java source of the alarm package (main and test) and fails if
 * any string literal contains a write or DDL SQL keyword. The alarm may only send SELECT, SHOW and
 * SET SESSION CHARACTERISTICS ... READ ONLY. Comments are ignored; this file is skipped because it
 * has to name the keywords.
 */
class NoWriteSqlTest {

    static final Pattern FORBIDDEN = Pattern.compile(
            "\\b(INSERT|UPDATE|DELETE|CREATE|ALTER|DROP|TRUNCATE|MERGE|COPY|GRANT|REVOKE|UPSERT)\\b",
            Pattern.CASE_INSENSITIVE);

    @Test
    void noAlarmSourceContainsAWriteStatement() throws IOException {
        List<Path> files = new ArrayList<>();
        for (String root : List.of("src/main/java/ai/aeiou/edgelab/alarm", "src/test/java/ai/aeiou/edgelab/alarm")) {
            try (Stream<Path> s = Files.walk(Path.of(root))) {
                s.filter(p -> p.toString().endsWith(".java"))
                        .filter(p -> !p.getFileName().toString().equals("NoWriteSqlTest.java"))
                        .forEach(files::add);
            }
        }
        assertTrue(files.stream().anyMatch(p -> p.getFileName().toString().equals("SnapshotSource.java")),
                "scanner did not find the alarm sources; wrong working directory?");

        List<String> hits = new ArrayList<>();
        for (Path f : files) {
            for (String lit : stringLiterals(Files.readString(f))) {
                if (FORBIDDEN.matcher(lit).find()) hits.add(f.getFileName() + ": \"" + lit + "\"");
            }
        }
        assertEquals(List.of(), hits, "write/DDL SQL found in alarm sources");
    }

    @Test
    void theScannerSeesLiteralsButNotComments() {
        String src = "// UPDATE in a comment\n/* DROP here too */\n"
                + "String a = \"SELECT 1\"; String b = \"delete from x\"; char c = '\"'; String u = \"pg://h\";\n"
                + "String t = \"\"\"\n  insert into y\n  \"\"\";";
        List<String> lits = stringLiterals(src);
        assertEquals(List.of("SELECT 1", "delete from x", "pg://h", "\n  insert into y\n  "), lits);
        assertEquals(2, lits.stream().filter(l -> FORBIDDEN.matcher(l).find()).count());
    }

    /** Tiny Java lexer: returns the raw contents of string and text-block literals, skipping comments and chars. */
    static List<String> stringLiterals(String src) {
        List<String> out = new ArrayList<>();
        int i = 0, n = src.length();
        while (i < n) {
            char ch = src.charAt(i);
            if (src.startsWith("//", i)) {
                int e = src.indexOf('\n', i);
                i = e < 0 ? n : e + 1;
            } else if (src.startsWith("/*", i)) {
                int e = src.indexOf("*/", i + 2);
                i = e < 0 ? n : e + 2;
            } else if (src.startsWith("\"\"\"", i)) {
                int start = i + 3, j = start;
                while (j < n && !src.startsWith("\"\"\"", j)) j += src.charAt(j) == '\\' ? 2 : 1;
                out.add(src.substring(start, Math.min(j, n)));
                i = j + 3;
            } else if (ch == '"' || ch == '\'') {
                int start = i + 1, j = start;
                while (j < n && src.charAt(j) != ch) j += src.charAt(j) == '\\' ? 2 : 1;
                if (ch == '"') out.add(src.substring(start, Math.min(j, n)));
                i = j + 1;
            } else {
                i++;
            }
        }
        return out;
    }
}
