package autoprob.api;

/**
 * Represents the payload returned by the analysis-requests/next endpoint.
 */
public class AnalysisRequest {
    public int id;
    public Scenario scenario;
    public String path;
    public String difficulty;
    public String requestedAt;
    public Boolean isPrecalculate;
    public int failureCount;
    public String errorMessage;
    public boolean isFailed;

    public long getTimeoutMs(long defaultMs) {
        if (scenario != null && scenario.metadata != null
                && scenario.metadata.configOverrides != null
                && scenario.metadata.configOverrides.containsKey("timeoutSeconds")) {
            Object val = scenario.metadata.configOverrides.get("timeoutSeconds");
            if (val instanceof Number num) {
                return num.longValue() * 1000;
            }
        }
        return defaultMs;
    }

    /**
     * Scenario details embedded within an analysis request.
     */
    public static class Scenario {
        public int id;
        public String type;
        public Double elo;
        public String sgf;
        public String intro;
        public String createdAt;
        public String imageUrl;
        public String toMove;
        public String[] analyzedRootDifficulties;
        public ScenarioMetadata metadata;
    }
}
