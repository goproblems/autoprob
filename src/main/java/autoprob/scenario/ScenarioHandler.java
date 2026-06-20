package autoprob.scenario;

import autoprob.ApiClient;
import autoprob.KataBrain;
import autoprob.api.AnalysisRequest;
import autoprob.api.AnalysisResult;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class ScenarioHandler {
    // Client version sent to the API. Bump when releasing a new version.
    public static final int CLIENT_VERSION = 7;

    private final Properties props;
    private final long sleepMs;
    private final boolean useMercure;
    private final String clientId;

    private final AtomicLong lastServerContactTime = new AtomicLong(System.currentTimeMillis());
    private static final long PING_INTERVAL_MS = 30_000;

    private final AtomicBoolean updateRequired = new AtomicBoolean(false);
    private volatile int minClientVersion = 0;
    private static final long VERSION_WARN_INTERVAL_MS = 60_000; // 1 minute

    private ScheduledExecutorService scheduler;

    // ANSI color codes
    private static final String RED = "\033[31m";
    private static final String RESET = "\033[0m";

    public ScenarioHandler(Properties props) {
        this.props = props;
        this.sleepMs = Long.parseLong(props.getProperty("scenario.poll.interval.ms", "5000"));
        this.useMercure = Boolean.parseBoolean(props.getProperty("scenario.use.mercure", "false"));
        this.clientId = props.getProperty("clientId");
        if (clientId == null || clientId.isEmpty()) {
            System.out.println("WARNING: No clientId configured in properties. Ping and client-specific features will be disabled.");
        }
    }

    private static class ScenarioResultSubmitBody {
        Integer requestId;
        AnalysisResult[] results;
        Long durationMs;
    }

    public void run() throws Exception {
        startBackgroundServices();
        try {
            if (useMercure) {
                runWithMercure();
            } else {
                runWithPolling();
            }
        } finally {
            stopBackgroundServices();
        }
    }

    private void startBackgroundServices() {
        scheduler = Executors.newScheduledThreadPool(2);

        // Ping immediately on startup
        if (clientId != null && !clientId.isEmpty()) {
            pingServer();
        }

        scheduler.scheduleAtFixedRate(() -> {
            try {
                long elapsed = System.currentTimeMillis() - lastServerContactTime.get();
                if (elapsed >= PING_INTERVAL_MS && clientId != null && !clientId.isEmpty()) {
                    pingServer();
                }
            } catch (Exception e) {
                System.out.println("Ping failed: " + e.getMessage());
            }
        }, PING_INTERVAL_MS, PING_INTERVAL_MS, TimeUnit.MILLISECONDS);

        scheduler.scheduleAtFixedRate(() -> {
            if (updateRequired.get()) {
                printVersionWarning();
            }
        }, VERSION_WARN_INTERVAL_MS, VERSION_WARN_INTERVAL_MS, TimeUnit.MILLISECONDS);

        System.out.println("Background services started (ping interval: " + PING_INTERVAL_MS + "ms)");
    }

    private void stopBackgroundServices() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    private void printVersionWarning() {
        System.out.println(RED + "WARNING: Your client version (" + CLIENT_VERSION +
                ") is below the minimum required version (" + minClientVersion +
                "). Please update your client!" + RESET);
    }

    /**
     * Send ping to the server.
     * Parses minClientVersion from response to check if an update is required.
     */
    private void pingServer() {
        try {
            ApiClient apiClient = new ApiClient();
            Map<String, String> pathParams = new HashMap<>();
            pathParams.put("clientId", clientId);
            String url = apiClient.buildUrl("api.analysis.clients.ping", pathParams, null, props);

            URL pingUrl = URI.create(url).toURL();
            HttpURLConnection conn = (HttpURLConnection) pingUrl.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("X-Api-Key", props.getProperty("apikey"));
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setDoOutput(true);
            conn.setFixedLengthStreamingMode(0);

            int code = conn.getResponseCode();
            recordServerContact();

            if (code == 200) {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                    }
                    String body = sb.toString().trim();
                    if (!body.isEmpty() && body.startsWith("{")) {
                        JsonObject json = new Gson().fromJson(body, JsonObject.class);
                        if (json.has("minClientVersion") && json.get("minClientVersion").isJsonPrimitive()) {
                            int minVersion = json.get("minClientVersion").getAsInt();
                            minClientVersion = minVersion;
                            boolean needsUpdate = minVersion > CLIENT_VERSION;
                            if (needsUpdate && !updateRequired.getAndSet(true)) {
                                // First detection — warn immediately
                                printVersionWarning();
                            } else if (!needsUpdate) {
                                updateRequired.set(false);
                            }
                        }
                    }
                }
            } else {
                System.out.println("Ping returned status: " + code);
            }
            conn.disconnect();
        } catch (Exception e) {
            System.out.println("Ping error: " + e.getMessage());
        }
    }

    /**
     * Record that we just communicated with the server, resetting the idle timer.
     */
    private void recordServerContact() {
        lastServerContactTime.set(System.currentTimeMillis());
    }

    /**
     * Build query string with clientId and clientVersion for the /next endpoint.
     */
    private String buildNextRequestQueryString() {
        if (clientId != null && !clientId.isEmpty()) {
            return "?clientId=" + clientId + "&clientVersion=" + CLIENT_VERSION;
        }
        return null;
    }

    /**
     * Run using Mercure
     */
    private void runWithMercure() throws Exception {
        ApiClient apiClient = new ApiClient();
        Gson gson = new GsonBuilder().create();

        KataBrain brain = new KataBrain(props);
        Analysis analysis = new Analysis(props, brain);

        java.util.concurrent.Semaphore notificationSignal = new java.util.concurrent.Semaphore(0);
        MercureClient mercureClient = new MercureClient(props);
        mercureClient.startListening(notificationSignal::release);
        System.out.println("Starting scenario handler with Mercure SSE mode");
        try {
            while (true) {
                try {
                    processPendingRequests(apiClient, analysis, gson, notificationSignal);
                    notificationSignal.acquire();
                    notificationSignal.drainPermits();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    System.out.println("Interrupted, stopping...");
                    break;
                } catch (Exception ex) {
                    System.out.println("Error while handling scenario request: " + ex.getMessage());
                    ex.printStackTrace();
                }
            }
        } finally {
            mercureClient.stopListening();
            brain.stopKataBrain();
        }
    }

    private void processPendingRequests(ApiClient apiClient, Analysis analysis, Gson gson,
                                         java.util.concurrent.Semaphore notificationSignal) throws Exception {
        while (true) {
            notificationSignal.drainPermits();
            String queryString = buildNextRequestQueryString();
            ApiClient.ApiResponse<AnalysisRequest> response =
                    apiClient.makeGetRequest("api.analysis.requests.next", null, queryString, AnalysisRequest.class, props);
            recordServerContact();
            if (!response.isSuccess() || response.getData() == null) {
                int status = response.getStatusCode();
                if (status != 204 && status != 404) {
                    System.out.println("Failed to fetch analysis request. Status: " + status +
                            ", message: " + response.getErrorMessage());
                } else {
                    System.out.println("No pending analysis requests. Waiting for notification...");
                }
                if (status == 401 || status == 403) {
                    System.out.println((status == 401 ? "Unauthorized" : "Forbidden") +
                            " access. Stopping scenario handler.");
                    Thread.currentThread().interrupt();
                    break;
                }
                break;
            }

            AnalysisRequest request = response.getData();
            System.out.println("Processing analysis request id=" + request.id +
                    ", scenarioId=" + request.scenario.id +
                    ", path=" + request.path +
                    ", difficulty=" + request.difficulty);

            try {
                processRequest(request, analysis, apiClient, gson);
            } catch (Exception ex) {
                System.out.println("Error processing request " + request.id + ": " + ex.getMessage());
                ex.printStackTrace();
            }
        }
    }

    /**
     * Run using polling
     */
    private void runWithPolling() throws Exception {
        ApiClient apiClient = new ApiClient();
        Gson gson = new GsonBuilder().create();

        KataBrain brain = new KataBrain(props);
        Analysis analysis = new Analysis(props, brain);

        System.out.println("Starting scenario handler loop. Poll interval: " + sleepMs + "ms");

        try {
            while (true) {
                try {
                    String queryString = buildNextRequestQueryString();
                    ApiClient.ApiResponse<AnalysisRequest> response =
                            apiClient.makeGetRequest("api.analysis.requests.next", null, queryString, AnalysisRequest.class, props);
                    recordServerContact();

                    if (!response.isSuccess() || response.getData() == null) {
                        int status = response != null ? response.getStatusCode() : -1;
                        if (status == 204 || status == 404) {
                            if (Boolean.parseBoolean(props.getProperty("debug", "false"))) {
                                System.out.println("No pending analysis requests. Sleeping...");
                            }
                        } else {
                            System.out.println("Failed to fetch analysis request. Status: " + status +
                                    ", message: " + response.getErrorMessage());
                        }
                        if (status == 401 || status == 403) {
                            System.out.println((status == 401 ? "Unauthorized" : "Forbidden") +
                                    " access. Stopping scenario handler.");
                            Thread.currentThread().interrupt();
                            break;
                        }
                        Thread.sleep(sleepMs);
                        continue;
                    }

                    AnalysisRequest request = response.getData();
                    System.out.println("Processing analysis request id=" + request.id +
                            ", scenarioId=" + request.scenario.id +
                            ", path=" + request.path +
                            ", difficulty=" + request.difficulty);

                    processRequest(request, analysis, apiClient, gson);

                } catch (Exception ex) {
                    System.out.println("Error while handling scenario request: " + ex.getMessage());
                    ex.printStackTrace();
                }

                Thread.sleep(sleepMs);
            }
        } finally {
            brain.stopKataBrain();
        }
    }

    /**
     * Process a single analysis request.
     */
    private void processRequest(AnalysisRequest request, Analysis analysis,
                                ApiClient apiClient, Gson gson) throws Exception {
        Map<String, String> pathParams = new HashMap<>();
        pathParams.put("scenarioId", String.valueOf(request.scenario.id));

        long analysisStartTime = System.currentTimeMillis();

        analysis.setResultSubmitter(batchResults -> {
            ScenarioResultSubmitBody submission = new ScenarioResultSubmitBody();
            submission.requestId = request.id;
            submission.results = batchResults;
            submission.durationMs = System.currentTimeMillis() - analysisStartTime;
            String body = gson.toJson(submission);
            System.out.println("Submitting " + batchResults.length + " results");
            if (Boolean.parseBoolean(props.getProperty("debug", "false"))) {
                System.out.println("Submitting JSON: " + body);
            } else {
                for (int i = 0; i < batchResults.length; i++) {
                    AnalysisResult res = batchResults[i];
                    System.out.println(" Result " + i + " " + res.toStringBrief());
                }
            }
            ApiClient.ApiResponse<Object> submitResponse = apiClient.makePostRequest(
                    "api.analysis.requests.submit", pathParams, body, Object.class, props);
            recordServerContact();
            if (submitResponse.isSuccess()) {
                System.out.println("Submitted analysis results for request " + request.id +
                        " (duration: " + submission.durationMs + "ms)");
            } else {
                String errorMsg = "Failed to submit results for request " + request.id +
                        ". Status: " + submitResponse.getStatusCode() +
                        ", message: " + submitResponse.getErrorMessage();
                System.out.println(errorMsg);
                throw new RuntimeException(errorMsg);
            }
        });

        try {
            long defaultRequestTimeoutMs = Long.parseLong(props.getProperty("scenario.request.timeout.ms", "30000"));
            long requestTimeoutMs = request.getTimeoutMs(defaultRequestTimeoutMs);

            if (requestTimeoutMs > 0) {
                ExecutorService executor = Executors.newSingleThreadExecutor();
                Future<?> future = executor.submit(() -> {
                    try {
                        analysis.analyze(request);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
                try {
                    future.get(requestTimeoutMs, TimeUnit.MILLISECONDS);
                } catch (TimeoutException te) {
                    future.cancel(true);
                    throw new RuntimeException("Request timed out after " + requestTimeoutMs + "ms");
                } catch (ExecutionException ee) {
                    Throwable cause = ee.getCause();
                    if (cause instanceof RuntimeException re && re.getCause() instanceof Exception inner) {
                        throw inner;
                    }
                    throw (cause instanceof Exception ex2) ? ex2 : new RuntimeException(cause);
                } finally {
                    executor.shutdownNow();
                }
            } else {
                analysis.analyze(request);
            }
        } catch (Exception ex) {
            long durationMs = System.currentTimeMillis() - analysisStartTime;
            String errorMessage = ex.getMessage();
            if (errorMessage == null) errorMessage = ex.getClass().getSimpleName();
            if (errorMessage.length() > 2000) errorMessage = errorMessage.substring(0, 2000);
            System.out.println("Analysis failed for request " + request.id +
                    " after " + durationMs + "ms: " + errorMessage);
            reportError(request.id, errorMessage, apiClient);
            throw ex;
        }
    }

    private void reportError(int requestId, String message, ApiClient apiClient) {
        try {
            Map<String, String> pathParams = new HashMap<>();
            pathParams.put("id", String.valueOf(requestId));
            String body = "{\"message\":" + new Gson().toJson(message) + "}";
            ApiClient.ApiResponse<Object> response = apiClient.makePostRequest(
                    "api.analysis.requests.error", pathParams, body, Object.class, props);
            recordServerContact();
            if (response.isSuccess()) {
                System.out.println("Reported error for request " + requestId);
            } else {
                System.out.println("Failed to report error for request " + requestId +
                        ". Status: " + response.getStatusCode() +
                        ", message: " + response.getErrorMessage());
            }
        } catch (Exception e) {
            System.out.println("Exception reporting error for request " + requestId + ": " + e.getMessage());
        }
    }
}
