package autoprob.api;

import java.util.List;

public class JosekiNodeCompletionCandidatePage {
    public List<JosekiNodeCompletionCandidateData> entries;
    public int scannedNodes;
    public int skippedNodes;
    public int afterNodeId;
    public int snapshotMaxNodeId;
    public boolean hasMore;
}
