package autoprob.analysis;

import com.google.gson.JsonObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.stream.StreamSupport;

/** One immutable, server-owned configuration verified against this machine before acknowledgement. */
public final class AnalysisWorkerConfig {
    public final long revision;
    public final List<String> types;
    public final List<String> models;
    public final int concurrency;
    public final int reservedInteractive;
    public final String resourceGroup;
    private final Properties resolved = new Properties();

    public AnalysisWorkerConfig(Properties local, JsonObject response) {
        revision = response.get("revision").getAsLong();
        if (revision < 1 || !response.has("configuration") || response.get("configuration").isJsonNull()) {
            throw new IllegalArgumentException("Configure this Client in administration before starting analysis");
        }
        JsonObject config = response.getAsJsonObject("configuration");
        types = strings(config, "allowedTypes");
        models = strings(config, "allowedModels");
        String modelMode = config.get("modelMode").getAsString();
        String defaultModel = config.get("defaultModel").getAsString();
        String humanModel = config.get("humanModel").isJsonNull() ? null : config.get("humanModel").getAsString();
        concurrency = config.get("maxConcurrency").getAsInt();
        reservedInteractive = config.get("reservedInteractiveSlots").getAsInt();
        resourceGroup = config.get("resourceGroupId").getAsString();
        if (types.isEmpty() || !List.of("scenario", "josekinode", "josekiprecalculate", "review", "live").containsAll(types)
            || models.isEmpty() || !models.contains(defaultModel) || !List.of("fixed", "switchable").contains(modelMode)
            || ("fixed".equals(modelMode) && models.size() != 1) || concurrency < 1 || concurrency > 32
            || reservedInteractive < 0 || reservedInteractive >= concurrency) {
            throw new IllegalArgumentException("Invalid Client worker configuration");
        }
        resolved.putAll(local);
        resolved.remove("scenario.testcase_mode");
        config.getAsJsonObject("analysisParameters").entrySet().forEach(entry -> resolved.setProperty(entry.getKey(), entry.getValue().getAsString()));
        for (String model : models) KataModelResolver.resolve(resolved, model);
        resolved.setProperty("kata.model", KataModelResolver.resolve(resolved, defaultModel));
        resolved.setProperty("kata.human_model", "");
        if (humanModel != null) {
            Path directory = Path.of(local.getProperty("kata.human_models.dir", local.getProperty("kata.models.dir", ""))).toAbsolutePath().normalize();
            Path file = KataModelResolver.resolveFile(directory, humanModel);
            resolved.setProperty("kata.human_model", file.toString());
        }
        int analysisThreads = config.get("numAnalysisThreads").getAsInt();
        int searchThreads = config.get("numSearchThreadsPerAnalysisThread").getAsInt();
        if (analysisThreads < concurrency || analysisThreads > 256 || searchThreads < 1 || searchThreads > 256) {
            throw new IllegalArgumentException("Invalid Client analysis thread counts");
        }
        resolved.setProperty("analysis.kata.numAnalysisThreads", String.valueOf(analysisThreads));
        resolved.setProperty("analysis.kata.numSearchThreadsPerAnalysisThread", String.valueOf(searchThreads));
        resolved.setProperty("analysis.force_black_perspective", "true");
        if (!Files.isRegularFile(Path.of(local.getProperty("katago", ""))) || !Files.isRegularFile(Path.of(local.getProperty("kata.config", "")))) {
            throw new IllegalArgumentException("Configure installed katago and kata.config paths locally");
        }
    }

    public Properties properties() {
        Properties copy = new Properties();
        copy.putAll(resolved);
        return copy;
    }

    private static List<String> strings(JsonObject config, String name) {
        return StreamSupport.stream(config.getAsJsonArray(name).spliterator(), false).map(value -> value.getAsString()).distinct().toList();
    }
}
