package ai.aeiou.edgelab.voice;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;

/**
 * Finds the Whisper model file, downloading it once into the user's home folder.
 * After the first download everything works offline.
 *
 * Settings (system property, else environment variable):
 *   edgelab.whisper.model / EDGELAB_WHISPER_MODEL  model name, default {@value #DEFAULT_MODEL}
 *   edgelab.whisper.dir   / EDGELAB_WHISPER_DIR    cache folder, default ~/.cache/edgelab/whisper
 */
public final class WhisperModelCache {

    /** Multilingual base model, 5-bit quantised (~57 MB). Tiny got German numbers wrong. */
    public static final String DEFAULT_MODEL = "base-q5_1";

    private static final String BASE_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/";

    private final Path dir;
    private final String model;

    public WhisperModelCache(Path dir, String model) {
        this.dir = dir;
        this.model = model;
    }

    public static WhisperModelCache fromSettings() {
        String model = setting("edgelab.whisper.model", "EDGELAB_WHISPER_MODEL", DEFAULT_MODEL);
        String dir = setting("edgelab.whisper.dir", "EDGELAB_WHISPER_DIR",
                Path.of(System.getProperty("user.home"), ".cache", "edgelab", "whisper").toString());
        return new WhisperModelCache(Path.of(dir), model);
    }

    public String model() { return model; }

    public Path file() { return dir.resolve("ggml-" + model + ".bin"); }

    public boolean isCached() {
        try {
            return Files.isRegularFile(file()) && Files.size(file()) > 1_000_000;
        } catch (IOException e) {
            return false;
        }
    }

    /** The model file, downloading it first if it is not cached yet. */
    public synchronized Path ensure() throws IOException, InterruptedException {
        if (isCached()) return file();
        Files.createDirectories(dir);
        Path part = dir.resolve(file().getFileName() + ".part");
        HttpClient http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(BASE_URL + "ggml-" + model + ".bin"))
                .timeout(Duration.ofMinutes(10))
                .GET().build();
        HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() != 200) {
            resp.body().close();
            throw new IOException("model download failed: HTTP " + resp.statusCode() + " for " + req.uri());
        }
        try (InputStream in = resp.body()) {
            Files.copy(in, part, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.move(part, file(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return file();
    }

    private static String setting(String prop, String env, String fallback) {
        String v = System.getProperty(prop);
        if (v == null || v.isBlank()) v = System.getenv(env);
        return v == null || v.isBlank() ? fallback : v.trim();
    }
}
