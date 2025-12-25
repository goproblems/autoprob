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
        public String[] analyzedRootRanks;
    }
}
