package autoprob.api;

import java.util.List;

public class JosekiNodeEntry {
    public int id;
    public String path;
    public Integer parentId;
    public Double score;
    public Double loss;
    public Double prior;
    public Integer moveOrder;
    public Integer sortOrder;
    public Integer analysisClientVersion;
    public String deletedAt;
    public String createdAt;
    public String updatedAt;
    public List<String> missingHumanPolicyProfiles;
}
