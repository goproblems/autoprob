package autoprob.api;

/**
 * Represents a single analysis result entry from the API.
 */
public class AnalysisResult {
    public String path;
    public Double loss;
    public Double score;
    public Double urgency;
    public Double endness;
    public String rank;
    public Double weight;
    public Integer katagoPlayouts;
    public String katagoWeightsFile;
    public String extraInfo;
}
