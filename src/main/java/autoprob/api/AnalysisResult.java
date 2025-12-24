package autoprob.api;

import java.text.DecimalFormat;

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
    public String analysis;
    public Boolean isAnalyzed;

    private static final DecimalFormat df = new DecimalFormat("0.00");

    public String toStringBrief() {
        return "{" +
                "path='" + path + '\'' +
                ", loss=" + df.format(loss) +
                ", score=" + df.format(score) +
                ", urgency=" + df.format(urgency) +
                ", endness=" + df.format(endness) +
                ", rank='" + rank + '\'' +
                ", weight=" + df.format(weight) +
                ", katagoPlayouts=" + katagoPlayouts +
                ", isAnalyzed=" + isAnalyzed +
                '}';
    }
}
