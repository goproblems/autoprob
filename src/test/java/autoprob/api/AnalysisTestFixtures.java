package autoprob.api;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

final class AnalysisTestFixtures {
    private static final Gson GSON = new GsonBuilder().create();

    private AnalysisTestFixtures() {
    }

    static AnalysisRequest loadRequest(String resourceName) {
        return load(resourceName, AnalysisRequest.class);
    }

    static AnalysisResult loadResult(String resourceName) {
        return load(resourceName, AnalysisResult.class);
    }

    private static <T> T load(String resourceName, Class<T> type) {
        InputStream rawStream = AnalysisTestFixtures.class.getClassLoader().getResourceAsStream(resourceName);
        if (rawStream == null) {
            throw new IllegalArgumentException("Missing resource: " + resourceName);
        }

        try (InputStream stream = rawStream;
             Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return GSON.fromJson(reader, type);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load resource: " + resourceName, e);
        }
    }
}
