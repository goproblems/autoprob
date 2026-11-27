package autoprob.analysis;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.Callable;

/** A finite, resumable scan. Raw results never pass through the queue database. */
final class JosekiPrecalculation {
    private static final long PROGRESS_LOG_INTERVAL_MS = 30000;
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
        long initialProcessed = processed;
        long lastLoggedAt = started;
        logProgress(task, progress, started, previousMs, initialProcessed, processed > 0 ? "resumed" : "started");
        // Keep one adapter to reuse expensive executable/config fingerprints.
        JosekiTaskAdapter adapter = new JosekiTaskAdapter(props);
        while (true) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Precalculation interrupted");
            if (retry(shouldYield::shouldYield, null)) throw new AnalysisHandler.YieldRequested();
            JsonObject response = retry(() -> application.request(destination, "/next", claim), shouldYield);
            if (response.get("node").isJsonNull()) {
                progress.addProperty("elapsedMs", previousMs + System.currentTimeMillis() - started);
                logProgress(task, progress, started, previousMs, initialProcessed, "scan complete");
                return progress;
            }
            JsonObject node = response.getAsJsonObject("node");
            JsonObject delivery = claim.deepCopy();
            delivery.addProperty("nodeId", node.get("id").getAsInt());
            delivery.add("result", gson.toJsonTree(adapter.analyzeTask(node.getAsJsonObject("payload"), brain)));
            retry(() -> application.request(destination, "/submit", delivery), shouldYield);
            // Advance only after business storage acknowledges the entire node.
            // If the acknowledgement is lost, the next scan skips saved components.
            progress.addProperty("afterDepth", node.get("pathDepth").getAsInt());
            progress.addProperty("afterNodeId", node.get("id").getAsInt());
            progress.addProperty("lastCompletedPath", node.get("path").getAsString());
            progress.addProperty("processedNodes", ++processed);
            progress.addProperty("elapsedMs", previousMs + System.currentTimeMillis() - started);
            retry(() -> { checkpoint.save(progress); return null; }, shouldYield);
            long now = System.currentTimeMillis();
            if (now - lastLoggedAt >= PROGRESS_LOG_INTERVAL_MS) {
                logProgress(task, progress, started, previousMs, initialProcessed, "progress");
                lastLoggedAt = now;
            }
        }
    }

    private void logProgress(AnalysisTask task, JsonObject progress, long started, long previousMs, long initialProcessed, String phase) {
        long runMs = System.currentTimeMillis() - started;
        long processed = progress.has("processedNodes") ? progress.get("processedNodes").getAsLong() : 0;
        long elapsedSeconds = (previousMs + runMs) / 1000;
        String elapsed = String.format(Locale.ROOT, "%dh%02dm%02ds", elapsedSeconds / 3600, elapsedSeconds / 60 % 60, elapsedSeconds % 60);
        // Snapshot size includes already-qualified nodes; processed/snapshot is not a completion percentage.
        System.out.printf(Locale.ROOT,
            "Joseki precalculation task %d %s: processed=%,d nodes; snapshot=%,d nodes (already-analyzed nodes skipped); this run=%,d; rate=%.1f nodes/min; elapsed=%s; lastNode=%s; depth=%s%n",
            task.id, phase, processed, task.payload.get("totalNodes").getAsLong(), processed - initialProcessed,
            (processed - initialProcessed) * 60000.0 / Math.max(1, runMs), elapsed,
            progress.has("afterNodeId") ? progress.get("afterNodeId").getAsString() : "-",
            progress.has("afterDepth") ? progress.get("afterDepth").getAsString() : "-");
    }

    private <T> T retry(Callable<T> request, YieldCheck shouldYield) throws Exception {
        long delayMs = 1000;
        boolean recovering = false;
        while (true) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Precalculation interrupted");
            try {
                // A callback outage must not block higher-priority work indefinitely.
                if (recovering && shouldYield != null && shouldYield.shouldYield()) throw new AnalysisHandler.YieldRequested();
                return request.call();
            } catch (IOException error) {
                if (!retryable(error)) throw error;
                System.err.printf("Joseki precalculation request failed; retrying in %ds: %s%n", delayMs / 1000, error.getMessage());
                // The handler's independent heartbeat cancels this thread if its lease is lost.
                Thread.sleep(delayMs);
                delayMs = Math.min(30000, delayMs * 2);
                recovering = true;
            }
        }
    }

    private boolean retryable(IOException error) {
        int status;
        if (error instanceof TaskQueueClient.ApiException queueError) status = queueError.status;
        else if (error instanceof TaskResultSubmitter.ApiException applicationError) status = applicationError.status;
        else return true;
        return status == 408 || status == 429 || status >= 500;
    }
}
