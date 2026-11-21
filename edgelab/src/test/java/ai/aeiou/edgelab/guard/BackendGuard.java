package ai.aeiou.edgelab.guard;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Lists every file under {@code backend/} that differs from a git ref (changed, deleted or new). */
public final class BackendGuard {

    /** Thrown when git or the ref is not available, so the check can be skipped instead of failed. */
    public static final class Unavailable extends Exception {
        public Unavailable(String message) { super(message); }
    }

    private BackendGuard() {}

    public static List<String> differences(Path repoRoot, String ref) throws Unavailable {
        Result version = git(repoRoot, "--version");
        if (version.exit != 0) throw new Unavailable("git not available: " + version.out);
        Result verify = git(repoRoot, "rev-parse", "--verify", "--quiet", ref + "^{commit}");
        if (verify.exit != 0) throw new Unavailable("git ref not found: " + ref);

        List<String> diffs = new ArrayList<>();
        // Tracked files: changed, deleted or added (staged or not) compared to the ref.
        Result diff = git(repoRoot, "diff", "--name-status", ref, "--", "backend/");
        if (diff.exit != 0) throw new Unavailable("git diff failed: " + diff.out);
        diff.lines().forEach(diffs::add);
        // New files git doesn't know about yet (ignored build output excluded).
        Result untracked = git(repoRoot, "ls-files", "--others", "--exclude-standard", "--", "backend/");
        if (untracked.exit != 0) throw new Unavailable("git ls-files failed: " + untracked.out);
        untracked.lines().forEach(l -> diffs.add("??\t" + l));
        return diffs;
    }

    record Result(int exit, String out) {
        List<String> lines() {
            return out.lines().map(String::strip).filter(l -> !l.isEmpty()).toList();
        }
    }

    static Result git(Path dir, String... args) throws Unavailable {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.add("-c");
        cmd.add("core.quotepath=off");
        cmd.addAll(List.of(args));
        try {
            Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
            byte[] out = p.getInputStream().readAllBytes();
            if (!p.waitFor(30, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new Unavailable("git timed out: " + String.join(" ", args));
            }
            return new Result(p.exitValue(), new String(out, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new Unavailable("git not available: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Unavailable("interrupted");
        }
    }
}
