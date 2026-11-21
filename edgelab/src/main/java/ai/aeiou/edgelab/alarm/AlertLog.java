package ai.aeiou.edgelab.alarm;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** Appends the full story of each alert to edgelab/alerts.log (git-ignored). */
public final class AlertLog {

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final Path file;

    public AlertLog(Path file) {
        this.file = file;
    }

    public Path file() {
        return file;
    }

    public void append(Alert a, Instant loggedAt) throws IOException {
        Files.writeString(file, format(a, loggedAt), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /** One log entry: time, topic, round, example mail, suggested reader, detail. */
    public static String format(Alert a, Instant loggedAt) {
        String mail = a.mailId() == null ? "-"
                : "#" + a.mailId() + (a.subject() == null ? "" : " \"" + a.subject() + "\"");
        return "[" + TIME.format(loggedAt) + "] " + a.topic().label.toUpperCase() + "\n"
                + "  alert:   " + a.line() + "\n"
                + "  when:    " + (a.at() == null ? "-" : TIME.format(a.at())) + "\n"
                + "  round:   " + (a.round() == null ? "-" : a.round()) + "\n"
                + "  mail:    " + mail + "\n"
                + "  reader:  " + a.reader() + "\n"
                + "  detail:  " + a.detail() + "\n\n";
    }
}
