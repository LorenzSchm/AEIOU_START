package ai.aeiou.forwarder.tariff.parse;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Access to the real start-kit files (tests run from forwarder/backend). */
public final class KitFiles {

    public static final Path DIR = Path.of("../../start-kit");

    private KitFiles() {}

    public static TariffSource source(String filename) {
        try {
            return new TariffSource(filename, null, Files.readAllBytes(DIR.resolve(filename)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
