package autoprob.analysis;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.util.Properties;

/** A finite, resumable scan. Raw results never pass through the queue database. */
final class JosekiPrecalculation {
    @FunctionalInterface interface Checkpoint { void save(JsonObject progress) throws Exception; }
    @FunctionalInterface interface YieldCheck { boolean shouldYield() throws Exception; }
    private final Properties props;
    private final TaskResultSubmitter application;
    private final Gson gson = new Gson();

    JosekiPrecalculation(Properties props, TaskResultSubmitter application) {
        this.props = props;
        this.application = application;
    }

    JsonObject analyze(AnalysisTask task, AnalysisKataBrain brain, Checkpoint checkpoint, YieldCheck shouldYield) throws Exception {
        JsonObject destination = task.payload.getAsJsonObject("nodeApi");
        JsonObject claim = new JsonObject();
        claim.addProperty("taskId", task.id);
        claim.addProperty("claimToken", task.claimToken);
        JsonObject progress = task.result == null ? new JsonObject() : task.result.deepCopy();
        long started = System.currentTimeMillis();
        long previousMs = progress.has("elapsedMs") ? progress.get("elapsedMs").getAsLong() : 0;
        long processed = progress.has("processedNodes") ? progress.get("processedNodes").getAsLong() : 0;
        // Keep one adapter to reuse expensive executable/config fingerprints.
        JosekiTaskAdapter adapter = new JosekiTaskAdapter(props);
        while (true) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Precalculation interrupted");
            if (shouldYield.shouldYield()) throw new AnalysisHandler.YieldRequested();
            JsonObject response = application.request(destination, "/next", claim);
            if (response.get("node").isJsonNull()) {
                progress.addProperty("elapsedMs", previousMs + System.currentTimeMillis() - started);
                return progress;
            }
            JsonObject node = response.getAsJsonObject("node");
            JsonObject delivery = claim.deepCopy();
            delivery.addProperty("nodeId", node.get("id").getAsInt());
            delivery.add("result", gson.toJsonTree(adapter.analyzeTask(node.getAsJsonObject("payload"), brain)));
            application.request(destination, "/submit", delivery);
            // Advance only after business storage acknowledges the entire node.
            // If the acknowledgement is lost, the next scan skips saved components.
            progress.addProperty("afterDepth", node.get("pathDepth").getAsInt());
            progress.addProperty("afterNodeId", node.get("id").getAsInt());
            progress.addProperty("lastCompletedPath", node.get("path").getAsString());
            progress.addProperty("processedNodes", ++processed);
            progress.addProperty("elapsedMs", previousMs + System.currentTimeMillis() - started);
            checkpoint.save(progress);
        }
    }
}
