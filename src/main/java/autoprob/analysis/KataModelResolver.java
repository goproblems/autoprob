package autoprob.analysis;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.Set;

/** Resolve public model identifiers to locally installed weights without accepting arbitrary paths. */
public final class KataModelResolver {
    private static final Set<String> MODELS = Set.of(
        "kata1-b15c192-s1672170752-d466197061.txt.gz",
        "kata1-b18c384nbt-s9996604416-d4316597426.bin.gz",
        "kata1-b28c512nbt-s7332806912-d4357057652.bin.gz",
        "kata1-tf3-b11c768-s11003M-d5973M-7gres.bin.gz"
    );

    private KataModelResolver() { }

    public static String resolve(Properties props, String requestedModel) {
        if (requestedModel == null) return null;
        if (!MODELS.contains(requestedModel)) {
            throw new IllegalArgumentException("Unsupported KataGo model: " + requestedModel);
        }
        Path configuredModel = Path.of(props.getProperty("kata.model")).toAbsolutePath().normalize();
        Path selectedModel = configuredModel.resolveSibling(requestedModel).normalize();
        if (!Files.isRegularFile(selectedModel)) {
            throw new IllegalArgumentException("KataGo model is unavailable on this worker: " + requestedModel);
        }
        return selectedModel.toString();
    }
}
