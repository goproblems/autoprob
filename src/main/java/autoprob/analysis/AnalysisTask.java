package autoprob.analysis;

import com.google.gson.JsonObject;

/** Server-owned scheduling and lease envelope. */
public class AnalysisTask {
    public long id;
    public String type;
    public int priority;
    public JsonObject payload;
    public JsonObject result;
    public String claimToken;
    public String status;
}
