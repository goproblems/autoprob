package autoprob.analysis;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Delivers a saved calculation to the application before the queue completes the job. */
final class TaskResultSubmitter {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    void submit(AnalysisTask task) throws IOException, InterruptedException {
        JsonObject destination = task.payload.getAsJsonObject("resultSubmission");
        URI url = URI.create(destination.get("url").getAsString());
        if (!("https".equals(url.getScheme()) || "http".equals(url.getScheme())) || url.getUserInfo() != null) {
            throw new IllegalArgumentException("Invalid analysis result submission URL");
        }
        JsonObject body = new JsonObject();
        body.addProperty("taskId", task.id);
        body.addProperty("claimToken", task.claimToken);
        HttpRequest request = HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .header("X-Analysis-Submission-Token", destination.get("token").getAsString())
            .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Analysis result submission HTTP " + response.statusCode());
        }
    }
}
