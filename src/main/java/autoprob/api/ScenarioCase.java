package autoprob.api;

import java.util.List;

public class ScenarioCase {
    public int id;
    public Scenario scenario;
    public String path;
    public String description;
    public String difficulty;
    public boolean isActive;
    public boolean isTestCase;
    public List<Expectation> expectations;
    public Tolerances tolerances;
    public String createdAt;
    public String updatedAt;

    public static class Expectation {
        public String path;
        public ExpectedValues expected;
        public Tolerances tolerances;
    }

    public static class ExpectedValues {
        public Double endness;
        public Double urgency;
        public Double totalLoss;
    }

    public static class Tolerances {
        public Double endness;
        public Double urgency;
        public Double totalLoss;
    }
}
