package autoprob.scenario;

import autoprob.api.AnalysisRequest;
import autoprob.api.AnalysisResult;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JUnit test case that exercises a scenario analysis using real KataGo settings.
 */
public class ScenarioAnalysisTestCase {
    private static final Gson GSON = new GsonBuilder().create();

    @Test
    void scenarioAnalysisProducesResultsWithinOptionalBounds() throws Exception {
        Properties props = ScenarioTestContext.get();
        Objects.requireNonNull(props, "Scenario properties must be provided via ScenarioTestContext");

        AnalysisRequest request = loadRequest(props);

        try (Analysis analysis = new Analysis(props)) {
            AnalysisResult result = analysis.analyze(request);
            assertNotNull(result.score, "Score should not be null");
            assertNotNull(result.loss, "Loss should not be null");
            assertTrue(result.katagoPlayouts == null || result.katagoPlayouts > 0,
                    "Expected positive KataGo playouts when provided");

            assertWithinBoundsIfConfigured(props, "scenario.expected.score.min",
                    "scenario.expected.score.max", result.score, "score");
            assertWithinBoundsIfConfigured(props, "scenario.expected.loss.min",
                    "scenario.expected.loss.max", result.loss, "loss");
        }
    }

    private AnalysisRequest loadRequest(Properties props) throws Exception {
        String requestPath = props.getProperty("scenario.request");
        if (requestPath == null || requestPath.isBlank()) {
            throw new IllegalArgumentException("scenario.request property must point to a JSON request file");
        }
        String json = Files.readString(Path.of(requestPath));
        return GSON.fromJson(json, AnalysisRequest.class);
    }

    private void assertWithinBoundsIfConfigured(Properties props, String minKey, String maxKey,
                                                Double value, String label) {
        String min = props.getProperty(minKey);
        String max = props.getProperty(maxKey);
        if (min != null && max != null && value != null) {
            double minVal = Double.parseDouble(min);
            double maxVal = Double.parseDouble(max);
            assertTrue(value >= minVal && value <= maxVal,
                    () -> label + " expected within [" + minVal + ", " + maxVal + "] but was " + value);
        }
    }
}
