package autoprob.analysis;

import autoprob.api.AnalysisRequest;
import autoprob.api.AnalysisResult;
import autoprob.scenario.Analysis;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import java.nio.charset.StandardCharsets;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** One dispatcher and lease lifecycle for all analysis work. Priority belongs to goproblems. */
public class AnalysisHandler {
    public static final int CLIENT_VERSION = 10;
    private final Properties props = new Properties();
    private final Gson gson = new Gson();
    private final TaskQueueClient queue;
    private final List<String> types;
    private final AtomicLong queueVersion = new AtomicLong();
    private final Semaphore queueChanged = new Semaphore(0);
    private final AnalysisMercureClient mercure;
    private AnalysisKataBrain brain;

    public AnalysisHandler(Properties configured) {
        props.putAll(configured);
        props.setProperty("analysis.force_black_perspective", "true");
        if (Long.parseLong(props.getProperty("kata.query.timeout.ms", "0")) <= 0) {
            props.setProperty("kata.query.timeout.ms", "120000");
        }
        types = Arrays.stream(props.getProperty("analysis.types", "scenario,joseki,review").split(","))
            .map(String::trim).filter(value -> !value.isBlank()).distinct().toList();
        if (types.isEmpty() || !List.of("scenario", "joseki", "review").containsAll(types)) {
            throw new IllegalArgumentException("analysis.types supports scenario,joseki,review");
        }
        queue = new TaskQueueClient(props);
        mercure = new AnalysisMercureClient(props);
    }

    public void run() throws Exception {
        long pollMs = Math.max(250, Long.parseLong(props.getProperty("analysis.poll.interval.ms", "2000")));
        System.out.println("Unified analysis worker started; types=" + types + "; priorities assigned by goproblems");
        try (mercure; JosekiTaskBridge bridge = new JosekiTaskBridge(props, queue)) {
            mercure.startListening(() -> {
                queueVersion.incrementAndGet();
                if (queueChanged.availablePermits() == 0) queueChanged.release();
            });
            bridge.start();
            while (!Thread.currentThread().isInterrupted()) {
                queueChanged.drainPermits();
                try {
                    AnalysisTask task = queue.next(types);
                    if (task != null) {
                        process(task);
                        continue;
                    }
                } catch (TaskQueueClient.ApiException error) {
                    if (error.authorizationFailure()) throw error;
                    System.err.println(error.getMessage());
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception error) {
                    System.err.println("Analysis worker: " + error.getMessage());
                }
                // Push wakes idle workers immediately; periodic recovery covers missed events/expired leases.
                queueChanged.tryAcquire(mercure.isConnected() ? 30000 : pollMs, TimeUnit.MILLISECONDS);
            }
        } finally {
            stopEngine();
        }
    }

    private void process(AnalysisTask task) throws Exception {
        if (task.claimToken == null || task.payload == null || !types.contains(task.type)) {
            throw new IllegalStateException("Invalid analysis task envelope");
        }
        long started = System.currentTimeMillis();
        AtomicLong lastHeartbeat = new AtomicLong(started);
        AtomicBoolean leaseLost = new AtomicBoolean();
        ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<JsonObject> future = null;
        heartbeat.scheduleAtFixedRate(() -> {
            try {
                JsonObject fields = new JsonObject();
                fields.add("types", gson.toJsonTree(types));
                queue.update(task, "heartbeat", fields);
                lastHeartbeat.set(System.currentTimeMillis());
            } catch (TaskQueueClient.ApiException error) {
                if (error.status == 409 || error.status == 404 || error.authorizationFailure()) leaseLost.set(true);
                System.err.println("Task " + task.id + " heartbeat: " + error.getMessage());
            } catch (Exception error) {
                System.err.println("Task " + task.id + " heartbeat: " + error.getMessage());
            }
        }, 20, 20, TimeUnit.SECONDS);
        try {
            ensureEngine(task);
            future = executor.submit(() -> analyze(task));
            long timeoutMs = timeoutMs(task);
            JsonObject result;
            while (true) {
                if (leaseLost.get() || System.currentTimeMillis() - lastHeartbeat.get() > 90000) {
                    leaseLost.set(true);
                    throw new IllegalStateException("Analysis task lease was lost");
                }
                if (timeoutMs > 0 && System.currentTimeMillis() - started > timeoutMs) {
                    throw new TimeoutException("Analysis task exceeded " + timeoutMs + "ms");
                }
                try {
                    result = future.get(1, TimeUnit.SECONDS);
                    break;
                } catch (TimeoutException waiting) {
                    // Keep checking the independent lease while KataGo calculates.
                }
            }
            JsonObject fields = new JsonObject();
            fields.add("result", result);
            fields.addProperty("durationMs", Math.max(1, System.currentTimeMillis() - started));
            updateWithRetry(task, "result", fields);
            System.out.println("Completed " + task.type + " task " + task.id);
        } catch (Exception error) {
            Throwable cause = error instanceof ExecutionException ? error.getCause() : error;
            if (cause instanceof ReviewAnalysis.YieldRequested && !leaseLost.get()) {
                updateWithRetry(task, "yield", null);
                System.out.println("Yielded review " + task.id + " to higher priority work");
            } else {
                if (future != null) future.cancel(true);
                stopEngine();
                if (!leaseLost.get() && !(cause instanceof InterruptedException)) {
                    String message = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
                    JsonObject fields = new JsonObject();
                    fields.addProperty("errorMessage", message.substring(0, Math.min(2000, message.length())));
                    try { updateWithRetry(task, "error", fields); }
                    catch (Exception reportError) { System.err.println("Could not report task failure: " + reportError.getMessage()); }
                }
                if (cause instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    throw (InterruptedException) cause;
                }
                System.err.println("Task " + task.id + " failed: " + cause.getMessage());
            }
        } finally {
            heartbeat.shutdownNow();
            if (future != null && !future.isDone()) future.cancel(true);
            executor.shutdownNow();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    private JsonObject analyze(AnalysisTask task) throws Exception {
        AtomicLong checkedVersion = new AtomicLong(-1);
        AtomicLong lastPriorityCheck = new AtomicLong();
        return switch (task.type) {
            case "review" -> new ReviewAnalysis(props).analyze(task, brain, partial -> {
                JsonObject fields = new JsonObject();
                fields.add("result", partial);
                updateWithRetry(task, "progress", fields);
            }, () -> {
                long version = queueVersion.get();
                long now = System.currentTimeMillis();
                long recoveryMs = mercure.isConnected() ? 30000 : Math.max(250,
                    Long.parseLong(props.getProperty("analysis.poll.interval.ms", "2000")));
                if (version == checkedVersion.get() && now - lastPriorityCheck.get() < recoveryMs) return false;
                JsonObject fields = new JsonObject();
                fields.add("types", gson.toJsonTree(types));
                JsonObject response = queue.update(task, "check-priority", fields);
                checkedVersion.set(version);
                lastPriorityCheck.set(now);
                return response.get("shouldYield").getAsBoolean();
            });
            case "scenario" -> analyzeScenario(task);
            case "joseki" -> gson.toJsonTree(new JosekiTaskAdapter(
                JosekiTaskBridge.remoteProperties(props)).analyzeTask(task.payload, brain)).getAsJsonObject();
            default -> throw new IllegalArgumentException("Unsupported analysis type: " + task.type);
        };
    }

    private JsonObject analyzeScenario(AnalysisTask task) throws Exception {
        AnalysisRequest request = gson.fromJson(task.payload, AnalysisRequest.class);
        Analysis analysis = new Analysis(props, brain);
        boolean streamed = Boolean.TRUE.equals(request.isPrecalculate);
        AtomicLong batchNumber = new AtomicLong(task.result != null && task.result.has("lastBatchNumber")
            ? task.result.get("lastBatchNumber").getAsLong() + 1 : 0);
        Map<String, AnalysisResult> results = new LinkedHashMap<>();
        analysis.setResultSubmitter(batch -> {
            if (streamed) {
                uploadScenarioBatch(task, batch, batchNumber);
            } else {
                for (AnalysisResult result : batch) results.put(result.path + ":" + result.difficulty, result);
            }
        });
        analysis.analyze(request);
        JsonObject result = new JsonObject();
        result.addProperty("requestId", request.id);
        result.addProperty("streamed", streamed);
        result.add("results", gson.toJsonTree(results.values()));
        return result;
    }

    private void uploadScenarioBatch(AnalysisTask task, AnalysisResult[] batch, AtomicLong batchNumber) throws Exception {
        AnalysisResult root = Arrays.stream(batch).filter(value -> value.path == null || value.path.isEmpty())
            .findFirst().orElseThrow(() -> new IllegalStateException("Precalculation batch requires a root score"));
        JsonElement rootJson = gson.toJsonTree(root);
        int rootBytes = gson.toJson(rootJson).getBytes(StandardCharsets.UTF_8).length;
        int maxBytes = 4 * 1024 * 1024;
        if (rootBytes > maxBytes) throw new IllegalStateException("Scenario root analysis exceeds upload size limit");
        JsonArray chunk = new JsonArray();
        chunk.add(rootJson);
        int bytes = rootBytes;
        for (AnalysisResult value : batch) {
            if (value == root) continue;
            JsonElement item = gson.toJsonTree(value);
            int itemBytes = gson.toJson(item).getBytes(StandardCharsets.UTF_8).length;
            if (rootBytes + itemBytes > maxBytes) throw new IllegalStateException("Scenario node analysis exceeds upload size limit");
            if (chunk.size() >= 100 || bytes + itemBytes > maxBytes) {
                submitScenarioChunk(task, chunk, batchNumber.get());
                batchNumber.incrementAndGet();
                chunk = new JsonArray();
                chunk.add(rootJson);
                bytes = rootBytes;
            }
            chunk.add(item);
            bytes += itemBytes;
        }
        submitScenarioChunk(task, chunk, batchNumber.get());
        batchNumber.incrementAndGet();
    }

    private void submitScenarioChunk(AnalysisTask task, JsonArray chunk, long batchNumber) throws Exception {
        JsonObject partial = new JsonObject();
        partial.addProperty("batchNumber", batchNumber);
        partial.add("results", chunk);
        JsonObject fields = new JsonObject();
        fields.add("result", partial);
        updateWithRetry(task, "progress", fields);
    }

    private long timeoutMs(AnalysisTask task) {
        if ("scenario".equals(task.type)) {
            return gson.fromJson(task.payload, AnalysisRequest.class).getTimeoutMs(
                Long.parseLong(props.getProperty("scenario.request.timeout.ms", "30000")));
        }
        return Long.parseLong(props.getProperty("analysis.task.timeout.ms", "43200000"));
    }

    private void ensureEngine(AnalysisTask task) throws Exception {
        String requestedModel = props.getProperty("kata.model");
        if (("joseki".equals(task.type) || "review".equals(task.type))
            && task.payload.has("model") && !task.payload.get("model").isJsonNull()) {
            requestedModel = KataModelResolver.resolve(props, task.payload.get("model").getAsString());
        }
        if (brain != null && !java.nio.file.Path.of(brain.modelPath).toAbsolutePath().normalize()
            .equals(java.nio.file.Path.of(requestedModel).toAbsolutePath().normalize())) stopEngine();
        if (brain == null) brain = new AnalysisKataBrain(props, requestedModel);
    }

    private void stopEngine() {
        if (brain != null) { brain.stopKataBrain(); brain = null; }
    }

    private void updateWithRetry(AnalysisTask task, String action, JsonObject fields) throws Exception {
        for (int attempt = 0; ; attempt++) {
            try { queue.update(task, action, fields); return; }
            catch (TaskQueueClient.ApiException error) {
                if (error.status < 500 || attempt == 2) throw error;
            } catch (java.io.IOException error) {
                if (attempt == 2) throw error;
            }
            Thread.sleep(1000L * (attempt + 1));
        }
    }
}
