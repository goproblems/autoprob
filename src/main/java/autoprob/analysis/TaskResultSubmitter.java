package autoprob.analysis;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Calls the application using the task-scoped capability supplied by its producer. */
final class TaskResultSubmitter {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    void submit(AnalysisTask task) throws IOException, InterruptedException {
        JsonObject destination = task.payload.getAsJsonObject("resultSubmission");
        JsonObject body = new JsonObject();
        body.addProperty("taskId", task.id);
        body.addProperty("claimToken", task.claimToken);
        request(destination, "", body);
    }

    JsonObject request(JsonObject destination, String action, JsonObject body) throws IOException, InterruptedException {
        URI url = URI.create(destination.get("url").getAsString() + action);
        if (!("https".equals(url.getScheme()) || "http".equals(url.getScheme())) || url.getUserInfo() != null) {
            throw new IllegalArgumentException("Invalid analysis application URL");
        }
        HttpRequest request = HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(action.isEmpty() ? 30 : 120))
            .header("Content-Type", "application/json")
            .header("X-Analysis-Submission-Token", destination.get("token").getAsString())
            .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Analysis application HTTP " + response.statusCode());
        }
        return response.body().isBlank() ? new JsonObject()
            : com.google.gson.JsonParser.parseString(response.body()).getAsJsonObject();
    }
}
