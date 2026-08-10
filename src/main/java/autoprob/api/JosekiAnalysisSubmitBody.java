package autoprob.api;

import java.util.List;

public class JosekiAnalysisSubmitBody {
    public String source;
    public int clientVersion;
    public double lowPolicyThreshold;
    public List<JosekiAnalysisResultData> results;
    public List<JosekiHumanPolicyDistributionData> humanPolicyDistributions;
}
