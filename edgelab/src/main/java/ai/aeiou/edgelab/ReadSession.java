package ai.aeiou.edgelab;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * State shared by one top-level {@link EdgeReader#read} call and every part read inside it
 * (zip entries, mail attachments, embedded pictures): the time budget, how deep we are,
 * how many parts were opened, and the text, parts and notes collected so far.
 *
 * <p>{@link EdgeReader} opens one per top-level call and keeps it in a thread-local while
 * the readers run. The container walker reads it with {@link #current()} and reads each
 * part through {@link #readPart}, which goes back through the same entry point.
 *
 * <p>When the budget runs out, {@link EdgeReader} freezes the session and builds the
 * answer from what was collected; later writes from the still-running reader are ignored.
 */
public final class ReadSession {

    /** Parts are opened at most this many levels below the top-level file. */
    public static final int MAX_DEPTH = 3;
    /** At most this many parts are read per top-level call, at any depth. */
    public static final int MAX_FILES = 20;

    private static final ThreadLocal<ReadSession> CURRENT = new ThreadLocal<>();

    /** One block of text: {@code header} is the part's path, or null for the top-level file's own text. */
    private record Section(String header, String text, double confidence) {}

    private final EdgeReader reader;
    private final Duration budget;
    private final long deadline;

    // Only the reading thread touches these two.
    private final Deque<String> path = new ArrayDeque<>();
    private int files;

    // The reading thread writes, the caller's thread reads after a timeout: guarded by "this".
    private final List<Section> sections = new ArrayList<>();
    private final List<Reading.Part> parts = new ArrayList<>();
    private final List<String> notes = new ArrayList<>();
    private boolean incomplete;
    private boolean frozen;
    private boolean serviceFailure;

    public static void retryAfterServiceFailure() {
        ReadSession session = CURRENT.get();
        if (session != null) session.serviceFailure = true;
    }

    Reading finish(Reading reading) {
        if (reading.status() == Reading.Status.OK || !(serviceFailure || expired() || Thread.currentThread().isInterrupted()))
            return reading;
        return new Reading(reading.text(), reading.confidence(), reading.detectedType(), reading.status(),
                reading.parts(), reading.notes(), true);
    }

    ReadSession(EdgeReader reader, Duration budget) {
        this.reader = reader;
        this.budget = budget;
        this.deadline = System.nanoTime() + budget.toNanos();
    }

    /** The session of the read running on this thread, or null outside {@link EdgeReader#read}. */
    public static ReadSession current() {
        return CURRENT.get();
    }

    void enter() { CURRENT.set(this); }

    void exit() { CURRENT.remove(); }

    // ---- where we are ------------------------------------------------------------

    /** 0 while reading the top-level file, 1 inside one of its parts, and so on. */
    public int depth() {
        return path.size();
    }

    /** The path of the file being read now, e.g. "inner.zip"; "" for the top-level file. */
    public String currentPath() {
        return String.join(" > ", path);
    }

    /** The path a child of the current file would have, e.g. "inner.zip > packing-list.xlsx". */
    public String pathTo(String childName) {
        return path.isEmpty() ? childName : currentPath() + " > " + childName;
    }

    /** Text header naming a part, so a parser can tell where each line came from. */
    public static String header(String partPath) {
        return "=== part: " + partPath + " ===";
    }

    // ---- limits --------------------------------------------------------------------

    public boolean expired() {
        return System.nanoTime() - deadline >= 0;
    }

    /** Note readers add when they stopped early because the budget ran out. */
    public static final String STOPPED_NOTE = "stopped: time budget used up";

    /**
     * True when the read on this thread should stop before its next costly step (a gateway
     * call, a Whisper run, the next page or frame): the budget ran out, the caller already
     * gave up, or the thread was interrupted. Always false outside a session and not interrupted.
     * Does not clear the interrupt flag.
     */
    public static boolean stopRequested() {
        if (Thread.currentThread().isInterrupted()) return true;
        ReadSession s = CURRENT.get();
        return s != null && (s.expired() || s.isFrozen());
    }

    private synchronized boolean isFrozen() {
        return frozen;
    }

    /** The size limit of the reader that opened this session; parts inside are held to it too. */
    public long maxBytes() {
        return reader.maxBytes();
    }

    /** Takes one of the {@link #MAX_FILES} part slots; false when they are used up. */
    public boolean takeFileSlot() {
        if (files >= MAX_FILES) return false;
        files++;
        return true;
    }

    /** Reads one part through the same entry point, one level deeper. Never throws. */
    public Reading readPart(String name, byte[] bytes) {
        path.addLast(name);
        try {
            return reader.read(name, bytes);
        } finally {
            path.removeLast();
        }
    }

    // ---- what was collected ---------------------------------------------------------

    public synchronized int sectionCount() { return sections.size(); }

    public synchronized int partCount() { return parts.size(); }

    /** Adds text under a header naming {@code partPath}, or without header when it is null. */
    public synchronized void addSection(String partPath, String text, double confidence) {
        if (frozen || text == null || text.isBlank()) return;
        sections.add(new Section(partPath, text.strip(), confidence));
    }

    /** Adds a part and returns its index, so the entry can be filled in once it is read. */
    public synchronized int addPart(Reading.Part part) {
        if (frozen) return -1;
        parts.add(part);
        return parts.size() - 1;
    }

    public synchronized void setPart(int index, Reading.Part part) {
        if (frozen || index < 0 || index >= parts.size()) return;
        parts.set(index, part);
    }

    public synchronized void addNote(String note) {
        if (!frozen) notes.add(note);
    }

    /** Something was left out (limit hit, part not fully read): the result is at most partial. */
    public synchronized void markIncomplete() {
        if (!frozen) incomplete = true;
    }

    /**
     * The top-level answer from everything collected: text with one header per part,
     * every part at every depth, and all notes.
     */
    public synchronized Reading result(String detectedType) {
        return build(0, 0, detectedType, incomplete, notes);
    }

    /**
     * What a nested container contributed (sections and parts added since the given counts),
     * for its entry in the parent's parts. Its notes are already in the session.
     */
    public synchronized Reading summarySince(int sectionsBefore, int partsBefore, String detectedType,
                                             boolean partial) {
        Reading r = build(sectionsBefore, partsBefore, detectedType, partial, List.of());
        if (partial && r.status() == Reading.Status.UNREADABLE) {
            // Opened fine, but every part inside was left out (limits): partial, not broken.
            return new Reading(r.text(), r.confidence(), r.detectedType(), Reading.Status.PARTIAL,
                    r.parts(), r.notes());
        }
        return r;
    }

    /** Called by {@link EdgeReader} when the budget ran out: freezes and returns what is there. */
    synchronized Reading timedOut(String detectedType) {
        List<String> all = new ArrayList<>(notes);
        all.add("timed out after " + seconds(budget) + " s");
        Reading r = build(0, 0, detectedType, true, all);
        frozen = true;
        // At the limit the answer is "partial" even when nothing was read yet.
        return new Reading(r.text(), r.confidence(), r.detectedType(), Reading.Status.PARTIAL,
                r.parts(), r.notes(), true);
    }

    private Reading build(int fromSection, int fromPart, String type, boolean partial, List<String> noteList) {
        List<Section> secs = sections.subList(fromSection, sections.size());
        List<Reading.Part> ps = parts.subList(fromPart, parts.size());
        StringBuilder text = new StringBuilder();
        double confidenceSum = 0;
        for (Section s : secs) {
            if (!text.isEmpty()) text.append("\n\n");
            if (s.header() != null) text.append(header(s.header())).append('\n');
            text.append(s.text());
            confidenceSum += s.confidence();
        }
        double confidence = secs.isEmpty() ? 0.0 : confidenceSum / secs.size();
        Reading.Status status;
        if (text.isEmpty()) {
            status = Reading.Status.UNREADABLE;
        } else if (partial || ps.stream().anyMatch(p -> p.status() != Reading.Status.OK)) {
            status = Reading.Status.PARTIAL;
        } else {
            status = Reading.Status.OK;
        }
        return new Reading(text.toString(), confidence, type, status, ps, noteList);
    }

    static String seconds(Duration d) {
        long ms = d.toMillis();
        if (ms % 1000 == 0) return Long.toString(ms / 1000);
        return String.format(Locale.ROOT, "%.1f", ms / 1000.0);
    }
}
