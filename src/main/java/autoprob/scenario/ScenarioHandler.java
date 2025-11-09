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

    public ScenarioHandler(Properties props) {
        this.props = props;
        this.sleepMs = Long.parseLong(props.getProperty("scenario.poll.interval.ms", "5000"));
    }

    private static class ScenarioResultSubmission {
        AnalysisResult[] results;
    }

    public void run() throws Exception {
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
                            System.out.println("No pending analysis requests. Sleeping...");
                        } else {
                            System.out.println("Failed to fetch analysis request. Status: " + status +
                                    ", message: " + response.getErrorMessage());
                        }
                        Thread.sleep(sleepMs);
                        continue;
                    }

                    AnalysisRequest request = response.getData();
                    System.out.println("Processing analysis request id=" + request.id + ", path=" + request.path);
                    System.out.println("scenario id=" + request.scenario.id);

                    AnalysisResult[] results = analysis.analyze(request);

                    ScenarioResultSubmission submission = new ScenarioResultSubmission();
                    submission.results = results;

                    Map<String, String> pathParams = new HashMap<>();
                    pathParams.put("scenarioId", String.valueOf(request.scenario.id));
                    pathParams.put("id", String.valueOf(request.id));

                    String requestBody = gson.toJson(submission);
                    System.out.println("Submitting JSON: " + requestBody);

                    ApiClient.ApiResponse<Object> submitResponse = apiClient.makePostRequest(
                            "api.analysis.requests.submit", pathParams, requestBody, Object.class, props);

                    if (submitResponse.isSuccess()) {
                        System.out.println("Submitted analysis results for request " + request.id);
                    } else {
                        System.out.println("Failed to submit results for request " + request.id +
                                ". Status: " + submitResponse.getStatusCode() +
                                ", message: " + submitResponse.getErrorMessage());
                    }
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
}
