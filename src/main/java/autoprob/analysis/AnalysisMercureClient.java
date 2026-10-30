package autoprob.analysis;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.net.SocketTimeoutException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Properties;

/** Queue wake-ups over Mercure, independent of the legacy scenario subscriber. */
public final class AnalysisMercureClient implements AutoCloseable {
    private final Properties props = new Properties();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private volatile boolean running;
    private volatile boolean connected;
    private volatile HttpURLConnection connection;
    private Thread listener;
    private String token;
    private long tokenExpiresAt;
    private String lastEventId;

    public AnalysisMercureClient(Properties configured) { props.putAll(configured); }

    public boolean isConnected() { return connected; }

    public void startListening(Runnable onQueueChanged) {
        if (!Boolean.parseBoolean(props.getProperty("analysis.use.mercure", "true"))) return;
        if (props.getProperty("auth.username", "").isBlank() || props.getProperty("auth.password", "").isEmpty()) {
            System.err.println("Analysis Mercure requires auth.username/password; using polling until configured");
            return;
        }
        running = true;
        listener = new Thread(() -> {
            while (running) {
                try {
                    ensureToken();
                    listen(onQueueChanged);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception error) {
                    if (running) System.err.println("Analysis Mercure: " + error.getMessage());
                } finally {
                    connected = false;
                    HttpURLConnection current = connection;
                    if (current != null) current.disconnect();
                    connection = null;
                    if (running) onQueueChanged.run();
                }
                if (running) {
                    try { Thread.sleep(2000); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
                }
            }
        }, "analysis-mercure");
        listener.setDaemon(true);
        listener.start();
    }

    private String baseUrl() {
        return props.getProperty("analysis.baseurl", props.getProperty("baseurl", "")).replaceAll("/+$", "");
    }

    private void ensureToken() throws Exception {
        if (token != null && System.currentTimeMillis() < tokenExpiresAt - 60000) return;
        JsonObject body = new JsonObject();
        body.addProperty("username", props.getProperty("auth.username"));
        body.addProperty("password", props.getProperty("auth.password"));
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/auth/login"))
            .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(new Gson().toJson(body))).build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IOException("Login HTTP " + response.statusCode());
        JsonObject session = JsonParser.parseString(response.body()).getAsJsonObject();
        if (!session.has("token") || session.get("token").isJsonNull()) throw new IOException("Login returned no token");
        token = session.get("token").getAsString();
        // Read expiry only to schedule refresh; the hub validates the JWT signature.
        String claims = new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]), StandardCharsets.UTF_8);
        tokenExpiresAt = JsonParser.parseString(claims).getAsJsonObject().get("exp").getAsLong() * 1000;
    }

    private void listen(Runnable onQueueChanged) throws Exception {
        String base = props.getProperty("mercure.baseurl", baseUrl()).replaceAll("/+$", "");
        String hub = base + props.getProperty("mercure.hub.url", "/.well-known/mercure");
        // This is a separate topic: old scenario workers receive no new queue events.
        String url = hub + (hub.contains("?") ? "&" : "?")
            + "match=" + URLEncoder.encode("/analysis/tasks", StandardCharsets.UTF_8);
        HttpURLConnection current = (HttpURLConnection) URI.create(url).toURL().openConnection();
        connection = current;
        current.setConnectTimeout(10000);
        current.setReadTimeout(30000);
        current.setRequestProperty("Accept", "text/event-stream");
        current.setRequestProperty("Cache-Control", "no-cache");
        current.setRequestProperty("Authorization", "Bearer " + token);
        if (lastEventId != null) current.setRequestProperty("Last-Event-ID", lastEventId);
        int status = current.getResponseCode();
        if (status == 401 || status == 403) token = null;
        if (status != 200) throw new IOException("Hub HTTP " + status);
        connected = true;
        System.out.println("Analysis Mercure connected; listening for queue changes");
        onQueueChanged.run(); // Recover notifications missed during startup or disconnection.
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(current.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder data = new StringBuilder();
            String eventId = null;
            long lastDataAt = System.currentTimeMillis();
            while (running && System.currentTimeMillis() < tokenExpiresAt - 60000) {
                String line;
                try { line = reader.readLine(); }
                catch (SocketTimeoutException timeout) {
                    if (System.currentTimeMillis() - lastDataAt >= 90000) throw new IOException("Hub stream timed out");
                    continue;
                }
                if (line == null) break;
                lastDataAt = System.currentTimeMillis();
                if (line.isEmpty()) {
                    if (eventId != null) lastEventId = eventId;
                    if (!data.isEmpty()) dispatch(data.toString(), onQueueChanged);
                    data.setLength(0);
                    eventId = null;
                } else if (line.startsWith("data:")) {
                    if (!data.isEmpty()) data.append('\n');
                    data.append(line.substring(5));
                    if (data.length() > 65536) throw new IOException("Queue event exceeds size limit");
                } else if (line.startsWith("id:")) {
                    String id = line.substring(3).stripLeading();
                    if (!id.contains("\u0000")) eventId = id;
                }
            }
        }
    }

    private void dispatch(String data, Runnable onQueueChanged) {
        try {
            JsonObject event = JsonParser.parseString(data).getAsJsonObject();
            if (event.has("type") && "analysis_tasks_changed".equals(event.get("type").getAsString())) {
                onQueueChanged.run();
            }
        } catch (RuntimeException invalidEvent) {
            System.err.println("Ignored invalid analysis queue event");
        }
    }

    @Override public void close() {
        running = false;
        connected = false;
        HttpURLConnection current = connection;
        if (current != null) current.disconnect();
        if (listener != null) {
            listener.interrupt();
            try { listener.join(2000); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
    }
}
