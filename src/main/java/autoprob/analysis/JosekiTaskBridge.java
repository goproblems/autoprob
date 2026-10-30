package autoprob.analysis;

import autoprob.api.JosekiAnalysisRequestData;
import com.google.gson.JsonObject;

import java.net.URI;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Imports one legacy request at a time; all computation still goes through the central priority queue. */
public class JosekiTaskBridge implements AutoCloseable {
    private final Properties props;
    private final TaskQueueClient queue;
    private final JosekiTaskAdapter remote;
    private final String namespace;
    private ScheduledExecutorService scheduler;
    private ScheduledExecutorService heartbeat;
    private JosekiAnalysisRequestData request;
    private Long taskId;
    private final AtomicBoolean leaseLost = new AtomicBoolean();
    private final AtomicLong lastHeartbeat = new AtomicLong();

    public JosekiTaskBridge(Properties configured, TaskQueueClient queue) {
        props = remoteProperties(configured);
        this.queue = queue;
        remote = new JosekiTaskAdapter(props);
        namespace = configured.getProperty("analysis.joseki.namespace",
            URI.create(props.getProperty("baseurl", "http://localhost/")).getHost());
    }

    public static Properties remoteProperties(Properties configured) {
        Properties result = new Properties();
        result.putAll(configured);
        String base = configured.getProperty("joseki.baseurl", configured.getProperty("baseurl", ""));
        result.setProperty("baseurl", base.replaceAll("/+$", "") + "/");
        // Existing configs may omit these paths; credentials are never taken from task payloads.
        result.putIfAbsent("api.joseki.analysis_requests_next", "api/joseki-analysis-requests/next");
        result.putIfAbsent("api.joseki.analysis_results", "api/joseki-analysis-results");
        result.putIfAbsent("api.joseki.analysis_request_error", "api/joseki-analysis-requests/{id}/error");
        result.putIfAbsent("api.joseki.nodes", "api/joseki-nodes");
        result.setProperty("api.connect.timeout.ms", "3000");
        result.setProperty("api.read.timeout.ms", "5000");
        return result;
    }

    public void start() {
        if (!Boolean.parseBoolean(props.getProperty("analysis.joseki.bridge", "false"))) return;
        if (props.getProperty("api.joseki.username", "").isBlank()
            || props.getProperty("api.joseki.password", "").isBlank()) {
            throw new IllegalArgumentException("Joseki bridge requires existing api.joseki.username/password credentials");
        }
        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleWithFixedDelay(this::tick, 0, 2, TimeUnit.SECONDS);
        System.out.println("Josekipedia bridge enabled; namespace=" + namespace);
    }

    private void tick() {
        try {
            if (request != null && (leaseLost.get()
                || (heartbeat != null && System.currentTimeMillis() - lastHeartbeat.get() >= 15000))) {
                release();
            }
            if (request == null) {
                request = remote.fetchNextAnalysisRequest();
                if (request == null) return;
                leaseLost.set(false);
                lastHeartbeat.set(System.currentTimeMillis());
                heartbeat = remote.startAnalysisHeartbeat(request, leaseLost, lastHeartbeat);
            }
            if (taskId == null) {
                JsonObject body = new JsonObject();
                body.addProperty("type", "joseki");
                body.addProperty("dedupeKey", "josekipedia:" + namespace + ":" + request.id);
                body.add("payload", remote.taskPayload(request));
                JsonObject created = queue.request("POST", "", body);
                taskId = created.get("id").getAsLong();
            }
            JsonObject task = queue.request("GET", "/" + taskId, null);
            if (leaseLost.get() || (heartbeat != null && System.currentTimeMillis() - lastHeartbeat.get() >= 15000)) {
                release();
                return;
            }
            String status = task.get("status").getAsString();
            if ("completed".equals(status)) {
                JsonObject result = task.getAsJsonObject("result");
                if (result == null) throw new IllegalStateException("Completed joseki task has no result");
                long duration = task.has("durationMs") && !task.get("durationMs").isJsonNull()
                    ? task.get("durationMs").getAsLong() : 1;
                remote.forwardTaskResult(result, duration);
                release();
            } else if ("failed".equals(status) || "cancelled".equals(status)) {
                String message = task.has("errorMessage") && !task.get("errorMessage").isJsonNull()
                    ? task.get("errorMessage").getAsString() : "Central analysis task " + status;
                remote.forwardTaskError(request.id, message);
                release();
            }
        } catch (Exception error) {
            // Keep the remote lease alive while retrying an interrupted enqueue or result relay.
            // Stable dedupeKey makes a restart/reclaim safe after an uncertain POST response.
            System.err.println("Joseki bridge: " + error.getMessage());
        }
    }

    private void release() {
        if (heartbeat != null) heartbeat.shutdownNow();
        heartbeat = null;
        request = null;
        taskId = null;
    }

    @Override public void close() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            try { scheduler.awaitTermination(10, TimeUnit.SECONDS); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        }
        release();
    }
}
