package autoprob.analysis;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Properties;

/** Bounded HTTP calls; heartbeat traffic can proceed independently of result uploads. */
public class TaskQueueClient {
    private final Gson gson = new Gson();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final String endpoint;
    private final String apiKey;
    private final String clientId;

    public TaskQueueClient(Properties props) {
        String base = props.getProperty("analysis.baseurl", props.getProperty("baseurl", "")).trim();
        if (base.isEmpty()) throw new IllegalArgumentException("Configure baseurl for goproblems");
        endpoint = base.replaceAll("/+$", "") + "/api/v2/analysis-tasks";
        apiKey = props.getProperty("analysis.apikey", props.getProperty("apikey", "")).trim();
        clientId = props.getProperty("clientId", "").trim();
        if (apiKey.isEmpty() || clientId.isEmpty()) {
            throw new IllegalArgumentException("Unified analysis requires apikey and a stable unique clientId");
        }
    }

    public AnalysisTask next(List<String> types) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("clientId", clientId);
        body.addProperty("clientVersion", AnalysisHandler.CLIENT_VERSION);
        body.add("types", gson.toJsonTree(types));
        JsonObject response = request("POST", "/next", body);
        if (response == null) return null;
        // A new task has no result yet. Gson's JsonObject adapter rejects explicit JsonNull,
        // whereas an absent optional field correctly leaves AnalysisTask.result as Java null.
        if (response.has("result") && response.get("result").isJsonNull()) response.remove("result");
        return gson.fromJson(response, AnalysisTask.class);
    }

    public JsonObject update(AnalysisTask task, String action, JsonObject fields) throws Exception {
        JsonObject body = fields == null ? new JsonObject() : fields.deepCopy();
        body.addProperty("clientId", clientId);
        body.addProperty("claimToken", task.claimToken);
        return request("POST", "/" + task.id + "/" + action, body);
    }

    public JsonObject request(String method, String path, JsonObject body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint + path))
            .timeout(Duration.ofSeconds(20)).header("X-Api-Key", apiKey)
            .header("Accept", "application/json").header("Content-Type", "application/json");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(gson.toJson(body)));
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            String message = response.body();
            if (message.length() > 1000) message = message.substring(0, 1000);
            throw new ApiException(status, "Analysis queue HTTP " + status + ": " + message);
        }
        return response.body().isBlank() ? null : gson.fromJson(response.body(), JsonObject.class);
    }

    public static class ApiException extends IOException {
        public final int status;
        public ApiException(int status, String message) {
            super(message);
            this.status = status;
        }
        public boolean authorizationFailure() { return status == 401 || status == 403 || status == 426; }
    }
}
