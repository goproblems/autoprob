package autoprob.api;

import java.util.List;

public class JosekiHumanPolicyDistributionData {
    public String path;
    public String profile;
    public String humanModel;
    public List<Double> distribution;
    public double localPolicyMass;
    public int maxMoveDistance;
    public int normalizationVersion;
}
