package autoprob.analysis;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Resolve server-selected filenames inside a local directory without accepting arbitrary paths. */
public final class KataModelResolver {
    private KataModelResolver() { }

    public static String resolve(Properties props, String requestedModel) {
        String directory = props.getProperty("kata.models.dir", "").trim();
        if (directory.isEmpty()) throw new IllegalArgumentException("Configure kata.models.dir for the local weights directory");
        return resolveFile(Path.of(directory).toAbsolutePath().normalize(), requestedModel).toString();
    }

    public static Path resolveFile(Path directory, String filename) {
        if (filename == null || !filename.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,254}")) {
            throw new IllegalArgumentException("Invalid weights filename from Client configuration");
        }
        Path selected = directory.resolve(filename).normalize();
        if (!selected.getParent().equals(directory) || !Files.isRegularFile(selected)) {
            throw new IllegalArgumentException("Model is unavailable on this worker: " + filename);
        }
        return selected;
    }
}
