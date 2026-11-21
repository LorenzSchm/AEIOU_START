package ai.aeiou.edgelab.alarm;

import ai.aeiou.edgelab.alarm.ChangeDetector.Decision;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The change alarm. Polls the shared DB (read-only) every 60 s and tells Thure about new things.
 *
 * <pre>
 *   ./gradlew -q alarm                      run until Ctrl+C
 *   ./gradlew -q alarm --args="--once"      one poll, then exit
 *   ./gradlew -q alarm --args="--replay"    print what today's history would raise (no pop-ups, memory untouched)
 *   ./gradlew -q alarm --args="--dump f.json"  write the current snapshot (no mail bodies) to a file
 * </pre>
 */
public final class AlarmMain {

    private static final DateTimeFormatter HHMMSS =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    static final Duration POLL = Duration.ofSeconds(60);
    /** More pop-ups than this in one poll are summed up into one "and N more" line. */
    static final int MAX_POPUPS_PER_POLL = 3;

    public static void main(String[] args) throws Exception {
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        Path home = Path.of("").toAbsolutePath();
        String mode = args.length == 0 ? "--run" : args[0];

        DatabaseConfig config = DatabaseConfig.load(home);
        try (SnapshotSource source = new SnapshotSource(config)) {
            switch (mode) {
                case "--replay" -> replay(source, out);
                case "--dump" -> {
                    Path target = Path.of(args.length > 1 ? args[1] : "snapshot.json");
                    Json.MAPPER.writeValue(target.toFile(), source.read());
                    out.println("snapshot written to " + target.toAbsolutePath());
                }
                case "--once", "--run" -> run(source, out, home, mode.equals("--once"));
                default -> {
                    out.println("usage: alarm [--once | --replay | --dump <file>]");
                    System.exit(2);
                }
            }
        }
    }

    private static void replay(SnapshotSource source, PrintStream out) throws Exception {
        Snapshot snap = source.read();
        List<Alert> alerts = ChangeDetector.decide(snap, Memory.empty()).alerts();
        out.println(alerts.size() + " alerts on today's history (nothing saved, no pop-ups):");
        for (Alert a : alerts) {
            out.println("  " + HHMMSS.format(a.at()) + "  " + a.line() + "   [" + a.reader() + "]");
        }
    }

    private static void run(SnapshotSource source, PrintStream out, Path home, boolean once) throws Exception {
        MemoryStore store = new MemoryStore(home.resolve("alarm-memory.json"));
        AlertLog log = new AlertLog(home.resolve("alerts.log"));
        CountDownLatch stop = new CountDownLatch(1);
        CountDownLatch stopped = new CountDownLatch(1);
        Thread hook = new Thread(() -> {
            stop.countDown();
            try {
                stopped.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }, "alarm-stop");
        Runtime.getRuntime().addShutdownHook(hook);

        try (Notifier notifier = Notifier.best(out)) {
            source.connection();
            out.println("Change alarm running (read-only DB session confirmed). Polling every "
                    + POLL.toSeconds() + " s. Log: " + log.file() + ". Ctrl+C stops it.");
            boolean popped = false;
            do {
                try {
                    popped = pollOnce(source, store, log, notifier, out);
                } catch (Exception e) {
                    out.println(HHMMSS.format(Instant.now())
                            + " poll failed, will retry: " + e.getMessage());
                    source.close();
                }
            } while (!once && !stop.await(POLL.toMillis(), TimeUnit.MILLISECONDS));
            if (once && popped) stop.await(6, TimeUnit.SECONDS); // let the pop-up show before the tray icon goes
        } finally {
            out.println("Change alarm stopped.");
            stopped.countDown();
        }
        if (once) Runtime.getRuntime().removeShutdownHook(hook);
    }

    /** One poll: decide, log, save memory, pop up. Returns true if a pop-up was shown. */
    static boolean pollOnce(SnapshotSource source, MemoryStore store, AlertLog log, Notifier notifier, PrintStream out)
            throws Exception {
        Snapshot snap = source.read();
        Memory before = store.load();
        Decision d = ChangeDetector.decide(snap, before);
        Instant now = Instant.now();
        for (Alert a : d.alerts()) log.append(a, now);
        store.save(d.memory(), now);

        String time = HHMMSS.format(Instant.now());
        if (!before.initialised()) {
            out.println(time + " first run: learned the baseline (" + d.memory().seen().size()
                    + " known things), no alerts. Memory: " + store.file());
            notifier.show("Change alarm is watching. Baseline learned, no alerts for old history.");
            return true;
        }
        List<Alert> alerts = d.alerts();
        if (alerts.isEmpty()) {
            out.println(time + " nothing new");
            return false;
        }
        for (Alert a : alerts) out.println(time + " ALERT " + a.line());
        int shown = alerts.size() <= MAX_POPUPS_PER_POLL ? alerts.size() : MAX_POPUPS_PER_POLL - 1;
        for (int i = 0; i < shown; i++) notifier.show(alerts.get(i).line());
        if (shown < alerts.size()) notifier.show("... and " + (alerts.size() - shown) + " more, see alerts.log");
        return true;
    }

    private AlarmMain() {}
}
