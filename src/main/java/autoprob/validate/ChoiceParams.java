package autoprob.validate;

import java.util.Properties;

// the settings ChoiceRule uses to pick a move from a decision's candidates. built from
// the redo.* properties for live generation; the tuner overrides fields by name
// (the property name without the redo. prefix) to try alternatives offline.
public class ChoiceParams {
    public String extendMode;        // scan or primary
    public String responseCriterion; // testing, human, order or local
    public int threatStones;
    public double threatOwnershipThreshold;
    public double severeThreatRatio;
    public double testingBand;       // width of the human answer bands the testing criterion compares
    public int threatRegionDist;     // only count threats near the stakes; -1 counts everywhere

    // candidate gathering. applied live by the extractor; offline they can only
    // narrow the recorded candidates, never add new ones
    public int visits;
    public int responseCandidates;
    public double minResponsePrior;
    public double minHumanPolicy;
    public boolean humanThreatExtends;

    public static ChoiceParams fromProps(Properties props) {
        ChoiceParams p = new ChoiceParams();
        p.extendMode = props.getProperty("redo.extend_mode", "scan");
        p.responseCriterion = props.getProperty("redo.response_criterion", "testing");
        p.threatStones = Integer.parseInt(props.getProperty("redo.threat_stones", "4"));
        p.threatOwnershipThreshold = Double.parseDouble(props.getProperty("redo.threat_ownership_threshold", "1.1"));
        p.severeThreatRatio = Double.parseDouble(props.getProperty("redo.severe_threat_ratio", "0.6"));
        p.testingBand = Double.parseDouble(props.getProperty("redo.testing_band", "0.2"));
        // redo.threat_region_dist is not applied to live generation yet (see docs/redo.md
        // known gaps); the tuner can try it offline first
        p.threatRegionDist = -1;
        p.visits = Integer.parseInt(props.getProperty("redo.visits", "1000"));
        p.responseCandidates = Integer.parseInt(props.getProperty("redo.response_candidates", "4"));
        p.minResponsePrior = Double.parseDouble(props.getProperty("redo.min_response_prior", "0.02"));
        p.minHumanPolicy = Double.parseDouble(props.getProperty("redo.min_human_policy", "0.05"));
        p.humanThreatExtends = Boolean.parseBoolean(props.getProperty("redo.human_threat_extends", "true"));
        return p;
    }

    public ChoiceParams copy() {
        ChoiceParams p = new ChoiceParams();
        p.extendMode = extendMode;
        p.responseCriterion = responseCriterion;
        p.threatStones = threatStones;
        p.threatOwnershipThreshold = threatOwnershipThreshold;
        p.severeThreatRatio = severeThreatRatio;
        p.testingBand = testingBand;
        p.threatRegionDist = threatRegionDist;
        p.visits = visits;
        p.responseCandidates = responseCandidates;
        p.minResponsePrior = minResponsePrior;
        p.minHumanPolicy = minHumanPolicy;
        p.humanThreatExtends = humanThreatExtends;
        return p;
    }

    // set a field by its property name without the redo. prefix
    public void set(String name, String value) {
        switch (name) {
            case "extend_mode" -> extendMode = value;
            case "response_criterion" -> responseCriterion = value;
            case "threat_stones" -> threatStones = Integer.parseInt(value);
            case "threat_ownership_threshold" -> threatOwnershipThreshold = Double.parseDouble(value);
            case "severe_threat_ratio" -> severeThreatRatio = Double.parseDouble(value);
            case "testing_band" -> testingBand = Double.parseDouble(value);
            case "threat_region_dist" -> threatRegionDist = Integer.parseInt(value);
            case "response_candidates" -> responseCandidates = Integer.parseInt(value);
            case "min_response_prior" -> minResponsePrior = Double.parseDouble(value);
            case "min_human_policy" -> minHumanPolicy = Double.parseDouble(value);
            case "human_threat_extends" -> humanThreatExtends = Boolean.parseBoolean(value);
            default -> throw new IllegalArgumentException("not a tunable choice setting: " + name);
        }
    }
}
