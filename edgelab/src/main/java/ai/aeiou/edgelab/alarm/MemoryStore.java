package ai.aeiou.edgelab.alarm;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Set;

/** The local memory file (git-ignored). Written atomically so Ctrl+C never leaves half a file. */
public final class MemoryStore {

    /** On-disk shape: when it was saved, and the keys already reported. */
    record Stored(Instant savedAt, Set<String> seen) {}

    private final Path file;

    public MemoryStore(Path file) {
        this.file = file;
    }

    public Path file() {
        return file;
    }

    /** {@link Memory#none()} when there is no file yet (first run). */
    public Memory load() throws IOException {
        if (!Files.isRegularFile(file)) return Memory.none();
        Stored s = Json.MAPPER.readValue(file.toFile(), Stored.class);
        return new Memory(true, s.seen());
    }

    public void save(Memory memory, Instant now) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Path tmp = parent.resolve(file.getFileName() + ".tmp");
        Json.MAPPER.writeValue(tmp.toFile(), new Stored(now, memory.seen()));
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
