package autoprob.analysis;

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
import java.time.Instant;
import java.util.List;
import java.util.stream.StreamSupport;
import java.util.Properties;
import java.util.function.Consumer;

/** Queue wake-ups over Mercure, independent of the legacy scenario subscriber. */
public final class AnalysisMercureClient implements AutoCloseable {
    private final Properties props = new Properties();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private volatile boolean running;
    private volatile boolean connected;
    private Thread listener;
    private String token;
    private long tokenExpiresAt;
    private String lastEventId;
    private String hubUrl;
    private List<String> topics = List.of();

    public AnalysisMercureClient(Properties configured) { props.putAll(configured); }

    public boolean isConnected() { return connected; }

    public void startListening(Runnable onQueueChanged, Consumer<JsonObject> onControl) {
        if (!Boolean.parseBoolean(props.getProperty("analysis.use.mercure", "true"))) return;
        running = true;
        listener = new Thread(() -> {
            while (running) {
                try {
                    ensureToken();
                    listen(onQueueChanged, onControl);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception error) {
                    if (running) System.err.println("Analysis Mercure: " + error.getMessage());
                } finally {
                    connected = false;
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
        String apiKey = props.getProperty("analysis.apikey", props.getProperty("apikey", "")).trim();
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/v2/analysis-clients/" + props.getProperty("clientId").trim() + "/subscription"))
            .timeout(Duration.ofSeconds(15)).header("Accept", "application/json").header("X-API-Key", apiKey).GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IOException("Worker subscription HTTP " + response.statusCode());
        JsonObject session = JsonParser.parseString(response.body()).getAsJsonObject();
        if (!session.has("token") || session.get("token").isJsonNull()) throw new IOException("Subscription returned no token");
        token = session.get("token").getAsString();
        tokenExpiresAt = Instant.parse(session.get("expiresAt").getAsString()).toEpochMilli();
        hubUrl = session.get("hubUrl").getAsString();
        topics = StreamSupport.stream(session.getAsJsonArray("topics").spliterator(), false).map(value -> value.getAsString()).toList();
    }

    private void listen(Runnable onQueueChanged, Consumer<JsonObject> onControl) throws Exception {
        String hub = hubUrl;
        if (!props.getProperty("mercure.baseurl", "").isBlank()) {
            hub = props.getProperty("mercure.baseurl").replaceAll("/+$", "") + props.getProperty("mercure.hub.url", URI.create(hubUrl).getRawPath());
        }
        String query = topics.stream().map(topic -> "match=" + URLEncoder.encode(topic, StandardCharsets.UTF_8)).collect(java.util.stream.Collectors.joining("&"));
        String url = hub + (hub.contains("?") ? "&" : "?") + query;
        HttpURLConnection current = (HttpURLConnection) URI.create(url).toURL().openConnection();
        try {
            current.setConnectTimeout(10000);
            current.setReadTimeout(30000);
            current.setRequestProperty("Accept", "text/event-stream");
            current.setRequestProperty("Cache-Control", "no-cache");
            current.setRequestProperty("Authorization", "Bearer " + token);
            if (lastEventId != null) current.setRequestProperty("Last-Event-ID", lastEventId);
            int status = current.getResponseCode();
            if (status == 401 || status == 403) token = null;
            if (status != 200) throw new IOException("Hub HTTP " + status);
            if (!running) return;
            connected = true;
            System.out.println("Analysis Mercure connected; listening for queue changes");
            JsonObject sync = new JsonObject();
            sync.addProperty("type", "analysis_control_sync");
            onControl.accept(sync);
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
                        if (running && !data.isEmpty()) dispatch(data.toString(), onQueueChanged, onControl);
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
        } finally {
            current.disconnect();
        }
    }

    private void dispatch(String data, Runnable onQueueChanged, Consumer<JsonObject> onControl) {
        try {
            JsonObject event = JsonParser.parseString(data).getAsJsonObject();
            if (event.has("type") && "analysis_tasks_changed".equals(event.get("type").getAsString())) {
                onQueueChanged.run();
            } else if (event.has("type") && List.of("live_analysis_control", "analysis_configuration_changed").contains(event.get("type").getAsString())) {
                onControl.accept(event);
            }
        } catch (RuntimeException invalidEvent) {
            System.err.println("Ignored invalid analysis queue event");
        }
    }

    @Override public void close() {
        running = false;
        connected = false;
        // disconnect() can block on the SSE reader's lock. Its daemon thread owns
        // the connection and closes it after the bounded read, without delaying shutdown.
        if (listener != null) listener.interrupt();
    }
}
