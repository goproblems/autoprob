package autoprob.analysis;

import autoprob.api.AnalysisRequest;
import autoprob.api.AnalysisResult;
import autoprob.scenario.Analysis;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import java.nio.charset.StandardCharsets;

import java.util.LinkedHashMap;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.io.File;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
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
import java.util.concurrent.atomic.AtomicReference;

/** One dispatcher and lease lifecycle for all analysis work. Priority belongs to goproblems. */
public class AnalysisHandler {
    public static final int CLIENT_VERSION = 19;
    private static final int INTERACTIVE_PRIORITY = 100;
    private final Properties props = new Properties();
    private final Properties localProps = new Properties();
    private final Gson gson = new Gson();
    private final TaskQueueClient queue;
    private final TaskResultSubmitter resultSubmitter = new TaskResultSubmitter();
    private final ResourceTelemetryCollector telemetry;
    private volatile long lastRegistrationNanos;
    private volatile List<String> types = List.of();
    private volatile AnalysisWorkerConfig settings;
    private final AtomicBoolean refreshConfiguration = new AtomicBoolean(true);
    private volatile boolean configurationPending = true;
    private JsonObject pendingConfiguration;
    private final Object registrationLock = new Object();
    private volatile long appliedRevision;
    private Long configurationErrorRevision;
    private String configurationError;
    private ExecutorService workers;
    private volatile String activeModelName;
    private final AtomicLong queueVersion = new AtomicLong();
    private final AtomicBoolean reconcileControls = new AtomicBoolean();
    private final Semaphore queueChanged = new Semaphore(0);
    private final AnalysisMercureClient mercure;
    private AnalysisKataBrain brain;
    private final Map<Long, RunningTask> active = new ConcurrentHashMap<>();

    private static final class RunningTask {
        final AnalysisTask task;
        final AtomicBoolean finished = new AtomicBoolean();
        final AtomicLong stopRequestedAtNanos = new AtomicLong();
        volatile Future<?> analysisFuture;
        RunningTask(AnalysisTask task) { this.task = task; }
        boolean stopRequested() { return stopRequestedAtNanos.get() != 0; }
        void stop() {
            stopRequestedAtNanos.compareAndSet(0, System.nanoTime());
            Future<?> running = analysisFuture;
            if (running != null) running.cancel(true);
        }
    }

    private void wakeup() {
        queueVersion.incrementAndGet();
        if (queueChanged.availablePermits() == 0) queueChanged.release();
    }

    private List<String> availableTypes() {
        return types.stream().filter(type -> !"live".equals(type) || mercure.isConnected()).toList();
    }

    private void control(JsonObject event) {
        if ("analysis_control_sync".equals(event.get("type").getAsString())) {
            reconcileControls.set(true);
            refreshConfiguration.set(true);
            wakeup();
            return;
        }
        if ("analysis_configuration_changed".equals(event.get("type").getAsString())) {
            refreshConfiguration.set(true);
            configurationPending = true;
            wakeup();
            return;
        }
        RunningTask running = active.get(event.get("id").getAsLong());
        if (running == null || !"live".equals(running.task.type)) return;
        int generation = event.get("generation").getAsInt();
        String status = event.get("status").getAsString();
        if (generation > running.task.generation || (generation == running.task.generation
            && ("paused".equals(status) || "cancelled".equals(status)))) running.stop();
        wakeup();
    }

    public AnalysisHandler(Properties configured) {
        localProps.putAll(configured);
        props.putAll(configured);
        queue = new TaskQueueClient(localProps);
        telemetry = new ResourceTelemetryCollector(localProps);
        mercure = new AnalysisMercureClient(localProps);
    }

    /** Serialize configuration acknowledgements and runtime heartbeats for this process instance. */
    private void registerState() throws Exception {
        synchronized (registrationLock) {
            long revision = settings == null ? 0 : settings.revision;
            JsonObject state = queue.registerWorker(revision, activeModelName, configurationErrorRevision, configurationError, telemetry.latest());
            lastRegistrationNanos = System.nanoTime();
            appliedRevision = revision;
            if (state.get("configurationRevision").getAsLong() != revision) {
                configurationPending = true;
                refreshConfiguration.set(true);
            }
        }
    }

    private void synchronizeConfiguration() throws Exception {
        if (refreshConfiguration.getAndSet(false)) {
            JsonObject response = queue.fetchConfiguration();
            long revision = response.get("revision").getAsLong();
            if (settings != null && settings.revision == revision && appliedRevision == revision) {
                pendingConfiguration = null;
                configurationPending = false;
            } else {
                pendingConfiguration = response;
                configurationPending = true;
            }
        }
        if (pendingConfiguration == null || !active.isEmpty()) return;
        JsonObject response = pendingConfiguration;
        AnalysisWorkerConfig candidate;
        try {
            candidate = new AnalysisWorkerConfig(localProps, response);
        } catch (RuntimeException invalid) {
            synchronized (registrationLock) {
                configurationErrorRevision = response.get("revision").getAsLong();
                configurationError = invalid.getMessage() == null ? invalid.getClass().getSimpleName() : invalid.getMessage();
                registerState();
            }
            pendingConfiguration = null;
            System.err.println("Client configuration could not be applied: " + invalid.getMessage());
            return;
        }
        synchronized (registrationLock) {
            if (settings == null || settings.revision != candidate.revision) {
                stopEngine();
                if (workers != null) {
                    workers.shutdown();
                    if (!workers.awaitTermination(15, TimeUnit.SECONDS)) throw new IllegalStateException("Previous analysis tasks have not stopped");
                }
                props.clear();
                props.putAll(candidate.properties());
                workers = Executors.newFixedThreadPool(candidate.concurrency);
                settings = candidate;
                types = candidate.types;
            }
            configurationError = null;
            configurationErrorRevision = null;
            registerState();
        }
        pendingConfiguration = null;
        configurationPending = appliedRevision != candidate.revision || refreshConfiguration.get();
        System.out.println("Applied Client configuration revision " + candidate.revision + "; types=" + types + "; models=" + candidate.models + "; GPU group=" + candidate.resourceGroup);
    }

    public void run() throws Exception {
        long pollMs = Math.max(250, Long.parseLong(localProps.getProperty("analysis.poll.interval.ms", "2000")));
        ScheduledExecutorService registration = Executors.newSingleThreadScheduledExecutor();
        AtomicReference<Exception> registrationFailure = new AtomicReference<>();
        Thread workerThread = Thread.currentThread();
        CountDownLatch stopped = new CountDownLatch(1);
        Thread shutdownHook = new Thread(() -> {
            workerThread.interrupt();
            try { stopped.await(40, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }, "analysis-worker-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdownHook);
        System.out.println("Unified analysis worker started; configuration source=Client API");
        try (mercure) {
            registerState();
            registration.scheduleWithFixedDelay(() -> {
                try {
                    int running = (int) active.values().stream().filter(task -> !task.finished.get()).count();
                    boolean sampled = telemetry.sampleIfDue(running, activeModelName, appliedRevision);
                    if (!sampled && System.nanoTime() - lastRegistrationNanos < TimeUnit.SECONDS.toNanos(20)) return;
                    registerState();
                }
                catch (TaskQueueClient.ApiException error) {
                    if (error.authorizationFailure() || error.status == 400) registrationFailure.compareAndSet(null, error);
                    if (error.status == 409) refreshConfiguration.set(true);
                    System.err.println("Worker registration: " + error.getMessage());
                } catch (Exception error) { System.err.println("Worker heartbeat: " + error.getMessage()); }
                wakeup();
            }, 1, 1, TimeUnit.SECONDS);
            mercure.startListening(this::wakeup, this::control);
            while (!Thread.currentThread().isInterrupted()) {
                if (registrationFailure.get() != null) throw registrationFailure.get();
                queueChanged.drainPermits();
                active.values().removeIf(running -> running.finished.get());
                try {
                    if (reconcileControls.getAndSet(false)) {
                        for (RunningTask running : active.values()) {
                            if (!"live".equals(running.task.type)) continue;
                            try { queue.update(running.task, "heartbeat", new JsonObject()); }
                            catch (TaskQueueClient.ApiException error) {
                                if (error.status == 409 || error.status == 404) running.stop();
                                else throw error;
                            }
                        }
                    }
                    synchronizeConfiguration();
                    while (!configurationPending && !refreshConfiguration.get() && settings != null && active.size() < settings.concurrency) {
                        if (brain != null && !brain.isAlive() && !active.isEmpty()) break;
                        List<String> available = availableTypes();
                        long backgroundRunning = active.values().stream().filter(running -> running.task.priority < INTERACTIVE_PRIORITY).count();
                        int backgroundLimit = settings.concurrency - settings.reservedInteractive;
                        Integer minimumPriority = backgroundRunning >= backgroundLimit ? INTERACTIVE_PRIORITY : null;
                        String activeModel = active.isEmpty() ? null : new File(brain.modelPath).getName();
                        AnalysisTask task = queue.next(available, activeModel, minimumPriority);
                        if (task == null) break;
                        long receivedAtNanos = System.nanoTime();
                        System.out.println("Received " + task.type + " task " + task.id + "; model=" + task.payload.get("model").getAsString() + "; priority=" + task.priority);
                        try { ensureEngine(task); }
                        catch (Exception error) {
                            JsonObject fields = new JsonObject();
                            fields.addProperty("errorMessage", error.getMessage());
                            queue.update(task, "error", fields);
                            continue;
                        }
                        AnalysisKataBrain taskBrain = brain;
                        RunningTask running = new RunningTask(task);
                        active.put(task.id, running);
                        workers.submit(() -> {
                            long startedAtNanos = 0;
                            try {
                                if (!running.stopRequested()) {
                                    startedAtNanos = System.nanoTime();
                                    if ("live".equals(task.type)) {
                                        System.out.printf(Locale.ROOT, "Started live task %d; generation=%d; move=%d; setup=%.2fs%n",
                                            task.id, task.generation, task.payload.getAsJsonArray("moves").size(),
                                            (startedAtNanos - receivedAtNanos) / 1_000_000_000.0);
                                    }
                                    process(task, taskBrain, running, receivedAtNanos);
                                }
                            }
                            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                            catch (Exception error) { System.err.println("Task " + task.id + ": " + error.getMessage()); }
                            finally {
                                if ("live".equals(task.type)) {
                                    long stoppedAtNanos = System.nanoTime();
                                    long requestedAtNanos = running.stopRequestedAtNanos.get();
                                    String stopTime = requestedAtNanos == 0 ? "" : String.format(Locale.ROOT,
                                        "; stop=%.2fs", (stoppedAtNanos - requestedAtNanos) / 1_000_000_000.0);
                                    System.out.printf(Locale.ROOT, "Stopped live task %d; generation=%d; analysis=%.2fs; total=%.2fs%s%n",
                                        task.id, task.generation, startedAtNanos == 0 ? 0 : (stoppedAtNanos - startedAtNanos) / 1_000_000_000.0,
                                        (stoppedAtNanos - receivedAtNanos) / 1_000_000_000.0, stopTime);
                                }
                                running.finished.set(true);
                                wakeup();
                            }
                        });
                        if (running.stopRequested()) running.stop();
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
                queueChanged.tryAcquire(mercure.isConnected() ? 30000 : pollMs, TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            boolean interrupted = Thread.interrupted();
            try {
                registration.shutdownNow();
                for (RunningTask running : active.values()) running.stop();
                if (workers != null) {
                    workers.shutdownNow();
                    try { workers.awaitTermination(15, TimeUnit.SECONDS); }
                    catch (InterruptedException stopping) { interrupted = true; }
                }
            } finally {
                try { stopEngine(); }
                finally {
                    try {
                        synchronized (registrationLock) { queue.unregisterWorker(); }
                    } catch (Exception error) {
                        System.err.println("Worker registration could not be released; it will expire automatically.");
                    } finally {
                        stopped.countDown();
                        try { Runtime.getRuntime().removeShutdownHook(shutdownHook); }
                        catch (IllegalStateException shuttingDown) { /* The JVM is already running this hook. */ }
                        if (interrupted) Thread.currentThread().interrupt();
                    }
                }
            }
        }
    }

    private void process(AnalysisTask task, AnalysisKataBrain taskBrain, RunningTask running, long receivedAtNanos) throws Exception {
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
            boolean submitsResult = task.payload.has("resultSubmission");
            boolean reusesSavedResult = submitsResult && task.result != null && task.result.has("calculatedResult");
            String scope = "task:" + task.id + ":" + UUID.randomUUID() + ":";
            future = executor.submit(() -> {
                // Only this envelope represents a full calculation. Streaming progress
                // from other adapters must never be mistaken for a reusable final result.
                if (reusesSavedResult) {
                    return task.result;
                }
                taskBrain.beginScope(scope, task.priority);
                try {
                    JsonObject calculated = analyze(task, taskBrain);
                    if (!submitsResult) return calculated;
                    JsonObject snapshot = new JsonObject();
                    snapshot.add("calculatedResult", calculated);
                    snapshot.addProperty("calculationDurationMs", Math.max(1, System.currentTimeMillis() - started));
                    return snapshot;
                }
                finally { taskBrain.endScope(scope); }
            });
            running.analysisFuture = future;
            if (running.stopRequested()) future.cancel(true);
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
            if (running.stopRequested()) throw new java.util.concurrent.CancellationException("Analysis task was stopped");
            if (submitsResult) {
                // Persist a fresh calculation before delivery. Retries receive the
                // saved snapshot with their current claim and can submit it directly.
                if (!reusesSavedResult) updateWithRetry(task, "progress", fields);
                resultSubmitter.submit(task);
                // Completion reuses the persisted snapshot instead of uploading it again.
                fields.add("result", new JsonObject());
            }
            fields.addProperty("durationMs", submitsResult ? result.get("calculationDurationMs").getAsLong()
                : "josekiprecalculate".equals(task.type) ? result.get("elapsedMs").getAsLong()
                : Math.max(1, System.currentTimeMillis() - started));
            updateWithRetry(task, "result", fields);
            if (!"live".equals(task.type)) System.out.printf(Locale.ROOT, "Completed %s task %d; total=%.2fs%n", task.type, task.id, (System.nanoTime() - receivedAtNanos) / 1_000_000_000.0);
        } catch (Exception error) {
            Throwable cause = error instanceof ExecutionException ? error.getCause() : error;
            if (cause instanceof YieldRequested && !leaseLost.get()) {
                updateWithRetry(task, "yield", null);
                System.out.println("Yielded " + task.type + " " + task.id + " to higher priority work");
            } else {
                if (future != null) future.cancel(true);
                if (cause instanceof TaskQueueClient.ApiException apiError && (apiError.status == 409 || apiError.status == 404)) leaseLost.set(true);
                if (!leaseLost.get() && !(cause instanceof InterruptedException) && !(cause instanceof java.util.concurrent.CancellationException)) {
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
                if (cause instanceof java.util.concurrent.CancellationException || leaseLost.get()) {
                    if (!"live".equals(task.type)) System.out.println("Stopped " + task.type + " task " + task.id);
                    return;
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

    public static class YieldRequested extends Exception { }

    private JsonObject analyze(AnalysisTask task, AnalysisKataBrain brain) throws Exception {
        Properties taskProps = taskProperties(task);
        taskProps.setProperty("kata.model", brain.modelPath);
        AtomicLong checkedVersion = new AtomicLong(-1);
        AtomicLong lastPriorityCheck = new AtomicLong();
        return switch (task.type) {
            case "live" -> new LiveAnalysis(taskProps).analyze(task, brain, partial -> {
                JsonObject fields = new JsonObject();
                fields.add("result", partial);
                updateWithRetry(task, "progress", fields);
            });
            case "review" -> new ReviewAnalysis(taskProps).analyze(task, brain, partial -> {
                JsonObject fields = new JsonObject();
                fields.add("result", partial);
                updateWithRetry(task, "progress", fields);
            }, () -> shouldYield(task, checkedVersion, lastPriorityCheck));
            case "josekiprecalculate" -> new JosekiPrecalculation(taskProps, resultSubmitter).analyze(task, brain,
                partial -> {
                    JsonObject fields = new JsonObject();
                    fields.add("result", partial);
                    queue.update(task, "progress", fields);
                }, () -> shouldYield(task, checkedVersion, lastPriorityCheck));
            case "scenario" -> analyzeScenario(task, brain, taskProps);
            case "josekinode" -> gson.toJsonTree(new JosekiTaskAdapter(taskProps)
                .analyzeTask(task.payload, brain)).getAsJsonObject();
            default -> throw new IllegalArgumentException("Unsupported analysis type: " + task.type);
        };
    }

    private boolean shouldYield(AnalysisTask task, AtomicLong checkedVersion, AtomicLong lastPriorityCheck) throws Exception {
        long version = queueVersion.get();
        long now = System.currentTimeMillis();
        long recoveryMs = mercure.isConnected() ? 30000 : Math.max(250,
            Long.parseLong(props.getProperty("analysis.poll.interval.ms", "2000")));
        if (version == checkedVersion.get() && now - lastPriorityCheck.get() < recoveryMs) return false;
        JsonObject fields = new JsonObject();
        fields.add("types", gson.toJsonTree(availableTypes()));
        JsonObject response = queue.update(task, "check-priority", fields);
        checkedVersion.set(version);
        lastPriorityCheck.set(now);
        return response.get("shouldYield").getAsBoolean();
    }

    private JsonObject analyzeScenario(AnalysisTask task, AnalysisKataBrain brain, Properties taskProps) throws Exception {
        AnalysisRequest request = gson.fromJson(task.payload, AnalysisRequest.class);
        Analysis analysis = new Analysis(taskProps, brain);
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
        analysis.analyzeResolved(request);
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
        if ("live".equals(task.type)) return 920000;
        if ("josekiprecalculate".equals(task.type)) return 0;
        Properties taskProps = taskProperties(task);
        if ("scenario".equals(task.type)) {
            return Long.parseLong(taskProps.getProperty("scenario.request.timeout.ms"));
        }
        return Long.parseLong(taskProps.getProperty("analysis.task.timeout.ms"));
    }

    private Properties taskProperties(AnalysisTask task) {
        Properties taskProps = new Properties();
        taskProps.putAll(props);
        task.payload.getAsJsonObject("analysisParameters").entrySet().forEach(entry -> taskProps.setProperty(entry.getKey(), entry.getValue().getAsString()));
        return taskProps;
    }

    private void ensureEngine(AnalysisTask task) throws Exception {
        String model = task.payload.get("model").getAsString();
        if (!settings.models.contains(model)) throw new IllegalArgumentException("Task model is outside the worker whitelist");
        String requestedModel = KataModelResolver.resolve(props, model);
        if (brain != null && (!brain.isAlive() || !java.nio.file.Path.of(brain.modelPath).toAbsolutePath().normalize()
            .equals(java.nio.file.Path.of(requestedModel).toAbsolutePath().normalize()))) stopEngine();
        if (brain == null) brain = new AnalysisKataBrain(props, requestedModel);
        activeModelName = new File(brain.modelPath).getName();
    }

    private void stopEngine() {
        activeModelName = null;
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
