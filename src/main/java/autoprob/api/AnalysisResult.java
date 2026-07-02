package autoprob.api;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;

/**
 * Represents a single analysis result entry from the API.
 */
public class AnalysisResult {
    public String path;
    public Double loss;
    public Double score;
    public Double urgency;
    public Double endness;
    public String difficulty;
    public Double weight;
    public Integer katagoPlayouts;
    public String katagoWeightsFile;
    public String extraInfo;
    public String analysis;
    public Boolean isAnalyzed;
    public List<EndnessReason> endnessReasons = new ArrayList<>();

    public static class EndnessReason {
        public EndnessReasonCode code;
        public String effect;
        public Double value;
        public Double threshold;
        public Double endness;
        public Boolean primary;

        public EndnessReason() {
        }

        public EndnessReason(EndnessReasonCode code, String effect, Double value, Double threshold, Double endness, Boolean primary) {
            this.code = code;
            this.effect = effect;
            this.value = value;
            this.threshold = threshold;
            this.endness = endness;
            this.primary = primary;
        }
    }

    private static final DecimalFormat df = new DecimalFormat("0.00");
    public String toStringBrief() {
        return "{" +
                "path='" + path + '\'' +
                ", loss=" + df.format(loss) +
                ", score=" + df.format(score) +
                ", urgency=" + df.format(urgency) +
                ", endness=" + df.format(endness) +
                ", difficulty='" + difficulty + '\'' +
                ", weight=" + df.format(weight) +
                ", katagoPlayouts=" + katagoPlayouts +
                ", isAnalyzed=" + isAnalyzed +
                '}';
    }
}
