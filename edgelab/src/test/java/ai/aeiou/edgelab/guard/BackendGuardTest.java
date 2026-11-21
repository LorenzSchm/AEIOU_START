package ai.aeiou.edgelab.guard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.abort;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Lorenz's backend/ must stay byte-for-byte what is on his live branch. */
class BackendGuardTest {

    static final String LIVE_BRANCH = "origin/feat/bullshitBranch";

    @Test
    void backendIsUntouchedComparedToLorenzBranch() {
        // Gradle runs tests from edgelab/, so the repo root is one level up.
        Path repoRoot = Path.of("").toAbsolutePath().getParent();
        List<String> diffs;
        try {
            diffs = BackendGuard.differences(repoRoot, LIVE_BRANCH);
        } catch (BackendGuard.Unavailable e) {
            abort("Skipping backend guard: " + e.getMessage());
            return;
        }
        assertTrue(diffs.isEmpty(),
                "backend/ differs from " + LIVE_BRANCH + " - edgelab must not touch Lorenz's bot:\n"
                        + String.join("\n", diffs));
    }

    @Test
    void guardReportsChangedDeletedAndNewBackendFiles(@TempDir Path repo) throws Exception {
        try {
            git(repo, "init", "-q");
        } catch (BackendGuard.Unavailable e) {
            abort("Skipping: " + e.getMessage());
        }
        git(repo, "config", "user.email", "test@example.invalid");
        git(repo, "config", "user.name", "test");
        git(repo, "config", "commit.gpgsign", "false");
        Files.createDirectories(repo.resolve("backend"));
        Files.writeString(repo.resolve("backend/a.txt"), "a");
        Files.writeString(repo.resolve("backend/b.txt"), "b");
        Files.writeString(repo.resolve("other.txt"), "x");
        git(repo, "add", "-A");
        git(repo, "commit", "-q", "-m", "base");
        git(repo, "branch", "live");

        assertTrue(BackendGuard.differences(repo, "live").isEmpty());

        // Changes outside backend/ are fine.
        Files.writeString(repo.resolve("other.txt"), "changed");
        assertTrue(BackendGuard.differences(repo, "live").isEmpty());

        Files.writeString(repo.resolve("backend/a.txt"), "changed");
        Files.delete(repo.resolve("backend/b.txt"));
        Files.writeString(repo.resolve("backend/new.txt"), "new");
        List<String> diffs = BackendGuard.differences(repo, "live");
        assertEquals(List.of("M\tbackend/a.txt", "D\tbackend/b.txt", "??\tbackend/new.txt"), diffs);
    }

    @Test
    void missingRefIsUnavailableNotClean(@TempDir Path repo) throws Exception {
        try {
            git(repo, "init", "-q");
        } catch (BackendGuard.Unavailable e) {
            abort("Skipping: " + e.getMessage());
        }
        try {
            BackendGuard.differences(repo, "no-such-ref");
        } catch (BackendGuard.Unavailable expected) {
            return;
        }
        throw new AssertionError("expected Unavailable for a missing ref");
    }

    private static void git(Path dir, String... args) throws BackendGuard.Unavailable {
        BackendGuard.Result r = BackendGuard.git(dir, args);
        if (r.exit() != 0) throw new BackendGuard.Unavailable("git " + String.join(" ", args) + ": " + r.out());
    }
}
