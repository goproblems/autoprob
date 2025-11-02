package autoprob.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalysisResultTest {

    @Test
    void invasionScenarioScoreAndLossFallWithinExpectedRanges() {
        AnalysisResult result = evaluateScenario(
                "scenario/sample-analysis-request.json",
                "scenario/sample-analysis-result.json"
        );

        assertNotNull(result.score, "Score should be populated by the analysis");
        assertRange("score", result.score, 2.0, 2.6);

        assertNotNull(result.loss, "Loss should be populated by the analysis");
        assertRange("loss", result.loss, -0.2, -0.1);
    }

    private AnalysisResult evaluateScenario(String requestResource, String resultResource) {
        AnalysisRequest request = AnalysisTestFixtures.loadRequest(requestResource);
        AnalysisResult result = AnalysisTestFixtures.loadResult(resultResource);
        assertEquals(request.path, result.path, "Result path should align with the request path");
        return result;
    }

    private void assertRange(String label, double value, double minInclusive, double maxInclusive) {
        assertTrue(value >= minInclusive,
                () -> label + " expected to be >= " + minInclusive + " but was " + value);
        assertTrue(value <= maxInclusive,
                () -> label + " expected to be <= " + maxInclusive + " but was " + value);
    }
}
