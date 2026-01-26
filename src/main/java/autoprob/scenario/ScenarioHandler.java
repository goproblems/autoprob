package autoprob.scenario;

import autoprob.ApiClient;
import autoprob.KataBrain;
import autoprob.api.AnalysisRequest;
import autoprob.api.AnalysisResult;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

public class ScenarioHandler {
    private final Properties props;
    private final long sleepMs;
    private final boolean useMercure;

    public ScenarioHandler(Properties props) {
        this.props = props;
        this.sleepMs = Long.parseLong(props.getProperty("scenario.poll.interval.ms", "5000"));
        this.useMercure = Boolean.parseBoolean(props.getProperty("scenario.use.mercure", "false"));
    }

    private static class ScenarioResultSubmitBody {
        Integer requestId;  // Optional field for request tracking
        AnalysisResult[] results;
    }

    public void run() throws Exception {
        if (useMercure) {
            runWithMercure();
        } else {
            runWithPolling();
        }
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
            processPendingRequests(apiClient, analysis, gson, notificationSignal);
            while (true) {
                try {
                    notificationSignal.acquire();
                    notificationSignal.drainPermits();
                    processPendingRequests(apiClient, analysis, gson, notificationSignal);
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
            ApiClient.ApiResponse<AnalysisRequest> response =
                    apiClient.makeGetRequest("api.analysis.requests.next", null, null, AnalysisRequest.class, props);
            if (!response.isSuccess() || response.getData() == null) {
                int status = response.getStatusCode();
                if (status != 204 && status != 404) {
                    System.out.println("Failed to fetch analysis request. Status: " + status +
                            ", message: " + response.getErrorMessage());
                } else {
                    System.out.println("No pending analysis requests. Waiting for notification...");
                }
                if (status == 401) {
                    System.out.println("Unauthorized access. Stopping scenario handler.");
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

            processRequest(request, analysis, apiClient, gson);
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
                    ApiClient.ApiResponse<AnalysisRequest> response =
                            apiClient.makeGetRequest("api.analysis.requests.next", null, null, AnalysisRequest.class, props);

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
                        if (status == 401) {
                            System.out.println("Unauthorized access. Stopping scenario handler.");
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

        analysis.setResultSubmitter(batchResults -> {
            ScenarioResultSubmitBody submission = new ScenarioResultSubmitBody();
            submission.requestId = request.id;
            submission.results = batchResults;
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
            if (submitResponse.isSuccess()) {
                System.out.println("Submitted analysis results for request " + request.id);
            } else {
                System.out.println("Failed to submit results for request " + request.id +
                        ". Status: " + submitResponse.getStatusCode() +
                        ", message: " + submitResponse.getErrorMessage());
            }
        });

        analysis.analyze(request);
    }
}
