package autoprob.api;

import java.util.List;

public class JosekiAnalysisSubmitBody {
    public int clientVersion;
    public List<JosekiAnalysisResultData> results;
    public List<JosekiHumanPolicyDistributionData> humanPolicyDistributions;
}
