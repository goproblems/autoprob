package autoprob.joseki;

import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JosekiNodeRecalculatorTaskQueryTest {
    @Test
    void passBackfillUsesItsOwnPolicyThreshold() {
        Properties props = new Properties();
        props.setProperty("joseki.low_policy_threshold", "0.01");
        props.setProperty("joseki.pass_policy_threshold", "0.001");
        JosekiNodeRecalculator recalculator = new JosekiNodeRecalculator(props);

        String analysisQuery = recalculator.buildNodesQuery(500, 0, "local", false);
        String passQuery = recalculator.buildNodesQuery(500, 0, "local", true);

        assertTrue(analysisQuery.contains("policyThreshold=0.01"));
        assertFalse(analysisQuery.contains("passAnalysisMissing=true"));
        assertTrue(passQuery.contains("policyThreshold=0.001"));
        assertTrue(passQuery.contains("passAnalysisMissing=true"));
    }

    @Test
    void passThresholdDefaultsToOneTenthOfOnePercent() {
        String passQuery = new JosekiNodeRecalculator(new Properties())
            .buildNodesQuery(500, 0, "global", true);

        assertTrue(passQuery.contains("policyThreshold=0.001"));
    }

    @Test
    void invalidPassThresholdIsRejected() {
        Properties props = new Properties();
        props.setProperty("joseki.pass_policy_threshold", "1.1");

        assertThrows(IllegalArgumentException.class, () ->
            new JosekiNodeRecalculator(props).buildNodesQuery(500, 0, "global", true)
        );
    }
}
