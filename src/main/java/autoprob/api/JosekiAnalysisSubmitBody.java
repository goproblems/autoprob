package autoprob.api;

import java.util.List;

public class JosekiAnalysisSubmitBody {
    public Integer requestId;
    public Long durationMs;
    public String source;
    public int clientVersion;
    public double lowPolicyThreshold;
    public List<JosekiAnalysisResultData> results;
    public List<JosekiHumanPolicyDistributionData> humanPolicyDistributions;
}
