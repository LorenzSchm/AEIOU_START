package ai.aeiou.edgelab.alarm;

import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

/**
 * What the alarm has already seen, as keys like "reason:too_high" or "sender:x@y".
 * {@code initialised} is false only before the very first run (no memory file yet).
 */
public record Memory(boolean initialised, Set<String> seen) {

    public Memory {
        seen = Collections.unmodifiableSortedSet(new TreeSet<>(seen == null ? Set.of() : seen));
    }

    /** No memory file yet: the next decide learns the baseline and alerts nothing. */
    public static Memory none() {
        return new Memory(false, Set.of());
    }

    /** An initialised memory that has seen nothing: everything in history is new. */
    public static Memory empty() {
        return new Memory(true, Set.of());
    }
}
