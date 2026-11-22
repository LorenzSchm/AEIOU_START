package ai.aeiou.edgelab;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.tika.Tika;

/**
 * The one public entry point: {@code read(filename, bytes) -> Reading}.
 * Never throws for bad input; problems come back as status UNREADABLE with a note.
 *
 * <p>Each top-level call gets a time budget (20 s by default), shared by every part read
 * inside the file (zip entries, attachments, embedded pictures). When it runs out the call
 * returns what it has, status PARTIAL, note "timed out after 20 s". Parts are read through
 * this same method while a {@link ReadSession} is active; they share the caller's budget.
 */
public final class EdgeReader {

    public static final long DEFAULT_MAX_BYTES = 50L * 1024 * 1024;
    /** Time for one top-level call, all parts included. */
    public static final Duration DEFAULT_BUDGET = Duration.ofSeconds(20);

    private static final String OCTET_STREAM = "application/octet-stream";

    private final Router router;
    private final long maxBytes;
    private final Duration budget;
    private final Tika tika = new Tika();

    public EdgeReader() {
        this(Router.standard(), DEFAULT_MAX_BYTES);
    }

    public EdgeReader(Router router, long maxBytes) {
        this(router, maxBytes, DEFAULT_BUDGET);
    }

    /** {@code budget}: time for one top-level call, shared by all its parts. */
    public EdgeReader(Router router, long maxBytes, Duration budget) {
        this.router = router;
        this.maxBytes = maxBytes;
        this.budget = budget;
    }

    /** The maximum size of one file (also of each part inside a file). */
    public long maxBytes() {
        return maxBytes;
    }

    public Reading read(String filename, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return Reading.unreadable(OCTET_STREAM, "empty input");
        }
        String type = detect(filename, bytes);
        if (bytes.length > maxBytes) {
            return Reading.unreadable(type,
                    "input too large: " + bytes.length + " bytes, limit is " + maxBytes);
        }
        if (ReadSession.current() != null) {
            // A part inside a file that is being read: same thread, shared budget.
            return route(filename, bytes, type);
        }
        return readWithBudget(filename, bytes, type);
    }

    /** Runs the read on its own thread so the caller gets an answer when the budget is used up. */
    private Reading readWithBudget(String filename, byte[] bytes, String type) {
        ReadSession session = new ReadSession(this, budget);
        FutureTask<Reading> task = new FutureTask<>(() -> {
            session.enter();
            try {
                return session.finish(route(filename, bytes, type));
            } finally {
                session.exit();
            }
        });
        Thread worker = new Thread(task, "edgelab-read");
        worker.setDaemon(true);
        worker.start();
        try {
            return task.get(budget.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            task.cancel(true);
            return session.timedOut(type);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            task.cancel(true);
            return session.timedOut(type);
        } catch (ExecutionException e) {
            Throwable c = e.getCause() != null ? e.getCause() : e;
            return failed(type, c);
        }
    }

    private Reading route(String filename, byte[] bytes, String type) {
        try {
            Reading reading = router.readerFor(type).read(filename, bytes, type);
            return reading != null ? reading : Reading.unreadable(type, "reader returned nothing");
        } catch (Exception | LinkageError e) {
            if (e instanceof LinkageError) ReadSession.retryAfterServiceFailure();
            return failed(type, e);
        }
    }

    private static Reading failed(String type, Throwable e) {
        return Reading.unreadable(type, "reader failed: " + e.getClass().getSimpleName()
                + (e.getMessage() != null ? ": " + e.getMessage() : ""));
    }

    /**
     * MIME type from the content. The file name is only a hint: Tika lets it refine
     * a content match (zip -> docx) but never overrule it (text stays text even as .pdf).
     */
    String detect(String filename, byte[] bytes) {
        try (InputStream in = new ByteArrayInputStream(bytes)) {
            String byContent = tika.detect(in);
            if (filename == null || filename.isBlank()) return byContent;
            String withHint = tika.detect(bytes, filename);
            // Only accept the name when it refines the content type, not when it replaces it.
            return isRefinement(withHint, byContent) ? withHint : byContent;
        } catch (Exception e) {
            return OCTET_STREAM;
        }
    }

    private static boolean isRefinement(String withHint, String byContent) {
        if (withHint.equals(byContent)) return true;
        if (byContent.equals(OCTET_STREAM)) return false; // unknown bytes: don't trust the ending
        var registry = org.apache.tika.mime.MimeTypes.getDefaultMimeTypes().getMediaTypeRegistry();
        return registry.isSpecializationOf(
                org.apache.tika.mime.MediaType.parse(withHint),
                org.apache.tika.mime.MediaType.parse(byContent));
    }
}
