package autoprob.scenario;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * Mercure SSE (Server-Sent Events) client for receiving real-time analysis requests.
 * Connects to a Mercure hub and subscribes to analysis request topics.
 * Supports JWT authentication with automatic token refresh.
 */
public class MercureClient {
    private final Properties props;
    private final Gson gson = new Gson();
    private volatile boolean running = false;
    private Thread listenerThread;

    private String jwtToken = null;
    private long tokenExpiryTime = 0;
    private long lastLoginAttemptTime = 0;
    private static final long TOKEN_REFRESH_BUFFER_MS = 5 * 60 * 1000;
    private static final long TOKEN_VALIDITY_MS = 60 * 60 * 1000;
    private static final long LOGIN_RETRY_DELAY_MS = 10 * 1000;

    private static final String MERCURE_BASEURL = "mercure.baseurl";
    private static final String MERCURE_HUB_URL = "mercure.hub.url";
    private static final String MERCURE_TOPIC = "mercure.topic";
    private static final String AUTH_USERNAME = "auth.username";
    private static final String AUTH_PASSWORD = "auth.password";
    private static final String NOTIFICATION_TYPE_ANALYSIS_REQUEST_CREATED = "scenario_analysis_request_created";

    public MercureClient(Properties props) {
        this.props = props;
    }

    private String maskToken(String token) {
        if (token == null) {
            return "null";
        }
        return "[MASKED]";
    }

    private String getMercureBaseUrl() {
        String mercureBaseUrl = props.getProperty(MERCURE_BASEURL, "").trim();
        if (!mercureBaseUrl.isEmpty()) {
            return mercureBaseUrl.endsWith("/") ? mercureBaseUrl.substring(0, mercureBaseUrl.length() - 1) : mercureBaseUrl;
        }
        String baseUrl = props.getProperty("baseurl", "https://staging.goproblems.com/");
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /**
     * Start listening for SSE events from Mercure hub.
     * This method is non-blocking - it starts a background thread.
     *
     * @param onNotification Callback invoked when analysis_request_created notification is received
     */
    public void startListening(Runnable onNotification) {
        if (running) {
            System.out.println("MercureClient is already running");
            return;
        }

        running = true;
        listenerThread = new Thread(() -> {
            while (running) {
                try {
                    ensureValidToken();
                    connectAndListen(onNotification);
                } catch (Exception e) {
                    if (running) {
                        System.out.println("Mercure connection error: " + e.getMessage());
                        // Check if it's an auth error (401/403), invalidate token
                        if (e.getMessage() != null && 
                            (e.getMessage().contains("401") || e.getMessage().contains("403"))) {
                            System.out.println("Authentication error, will refresh token on reconnect");
                            jwtToken = null;
                            tokenExpiryTime = 0;
                        }
                    }
                }
            }
        }, "MercureClient-Listener");
        listenerThread.setDaemon(true);
        listenerThread.start();
        System.out.println("MercureClient started listening");
    }

    /**
     * Stop listening for SSE events.
     */
    public void stopListening() {
        running = false;
        if (listenerThread != null) {
            listenerThread.interrupt();
            try {
                listenerThread.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        System.out.println("MercureClient stopped");
    }

    /**
     * Ensure we have a valid JWT token, refreshing if necessary.
     */
    private synchronized void ensureValidToken() throws Exception {
        long now = System.currentTimeMillis();

        if (jwtToken != null && now < (tokenExpiryTime - TOKEN_REFRESH_BUFFER_MS)) {
            return; // Token is still valid
        }

        System.out.println("Obtaining new JWT token...");
        waitForLoginRetryDelay();
        jwtToken = fetchJwtToken();
        tokenExpiryTime = now + TOKEN_VALIDITY_MS;
        System.out.println("JWT token obtained, valid until: " + new java.util.Date(tokenExpiryTime));
    }

    private void waitForLoginRetryDelay() throws InterruptedException {
        long now = System.currentTimeMillis();
        long nextAllowedLoginTime = lastLoginAttemptTime + LOGIN_RETRY_DELAY_MS;
        if (lastLoginAttemptTime > 0 && now < nextAllowedLoginTime) {
            long waitMs = nextAllowedLoginTime - now;
            System.out.println("Waiting " + (waitMs / 1000) + "s before retrying login...");
            Thread.sleep(waitMs);
        }
        lastLoginAttemptTime = System.currentTimeMillis();
    }

    /**
     * Fetch JWT token from the auth API using username and password.
     */
    private String fetchJwtToken() throws Exception {
        String baseUrl = props.getProperty("baseurl", "https://staging.goproblems.com/");
        String username = props.getProperty(AUTH_USERNAME);
        String password = props.getProperty(AUTH_PASSWORD);

        if (username == null || username.isEmpty() || password == null || password.isEmpty()) {
            throw new IllegalStateException("Authentication credentials not configured. Set " +
                AUTH_USERNAME + " and " + AUTH_PASSWORD + " in properties.");
        }

        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }

        String loginUrl = baseUrl + "/api/auth/login";

        if (Boolean.parseBoolean(props.getProperty("debug", "false"))) {
            System.out.println("Logging in to: " + loginUrl);
        }

        URL url = URI.create(loginUrl).toURL();
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setDoOutput(true);
        connection.setConnectTimeout(30000);
        connection.setReadTimeout(30000);

        JsonObject loginBody = new JsonObject();
        loginBody.addProperty("username", username);
        loginBody.addProperty("password", password);
        String requestBody = gson.toJson(loginBody);

        try (OutputStream os = connection.getOutputStream()) {
            byte[] input = requestBody.getBytes(StandardCharsets.UTF_8);
            os.write(input, 0, input.length);
        }

        int responseCode = connection.getResponseCode();
        if (responseCode != 200) {
            String errorMsg = "HTTP " + responseCode;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getErrorStream(), StandardCharsets.UTF_8))) {
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                errorMsg += ": " + response.toString();
            } catch (Exception ignored) {}
            throw new RuntimeException("Login failed: " + errorMsg);
        }

        StringBuilder response = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
        }

        JsonObject jsonResponse = gson.fromJson(response.toString(), JsonObject.class);
        if (!jsonResponse.has("token") || jsonResponse.get("token").isJsonNull()) {
            throw new RuntimeException("Login response does not contain token: " + response);
        }

        String token = jsonResponse.get("token").getAsString();
        if (Boolean.parseBoolean(props.getProperty("debug", "false"))) {
            System.out.println("Token obtained: " + maskToken(token));
        }

        return token;
    }

    private void connectAndListen(Runnable onNotification) throws Exception {
        String baseUrl = getMercureBaseUrl();
        String hubPath = props.getProperty(MERCURE_HUB_URL, "/.well-known/mercure");
        String topic = props.getProperty(MERCURE_TOPIC, "/invasions/analysis-requests");

        String hubUrl = baseUrl + hubPath;
        String encodedTopic = URLEncoder.encode(topic, StandardCharsets.UTF_8);

        StringBuilder subscribeUrl = new StringBuilder(hubUrl);
        subscribeUrl.append("?topic=").append(encodedTopic);
        if (jwtToken != null && !jwtToken.isEmpty()) {
            subscribeUrl.append("&authorization=").append(jwtToken);
        }

        System.out.println("Connecting to Mercure hub: " + hubUrl + "?topic=" + encodedTopic + "&authorization=" + maskToken(jwtToken));

        URL url = URI.create(subscribeUrl.toString()).toURL();
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Accept", "text/event-stream");
        connection.setRequestProperty("Cache-Control", "no-cache");

        connection.setDoInput(true);
        connection.setConnectTimeout(30000);
        connection.setReadTimeout(30000);

        int responseCode = connection.getResponseCode();
        if (responseCode != 200) {
            throw new RuntimeException("Failed to connect to Mercure hub. HTTP " + responseCode);
        }

        System.out.println("Connected to Mercure hub. Listening for events...");

        long connectionStartTime = System.currentTimeMillis();
        long lastDataReceivedTime = connectionStartTime;
        final long MAX_IDLE_TIME_MS = 30 * 60 * 1000; // 30 minutes without any data = reconnect to verify

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {

            StringBuilder eventData = new StringBuilder();
            String line;

            while (running) {
                try {
                    line = reader.readLine();
                    if (line == null) {
                        System.out.println("Connection closed by server");
                        break;
                    }

                    long now = System.currentTimeMillis();
                    lastDataReceivedTime = now;
                    // System.out.println("SSE received line: [" + line + "]");

                    if (line.isEmpty()) {
                        if (eventData.length() > 0) {
                            processEvent(eventData.toString().trim(), onNotification);
                            eventData.setLength(0);
                        }
                    } else if (line.startsWith("data:")) {
                        if (eventData.length() > 0) {
                            eventData.append("\n");
                        }
                        eventData.append(line.substring(5).trim());
                    }
                } catch (java.net.SocketTimeoutException e) {
                    long now = System.currentTimeMillis();

                    if (now >= (tokenExpiryTime - TOKEN_REFRESH_BUFFER_MS)) {
                        System.out.println("Token expiring soon, reconnecting to refresh token...");
                        break;
                    }

                    long idleTime = now - lastDataReceivedTime;
                    if (idleTime > MAX_IDLE_TIME_MS) {
                        System.out.println("No data received for " + (idleTime / 60000) + " minutes, reconnecting to verify connection...");
                        break;
                    }

                    if (Boolean.parseBoolean(props.getProperty("debug", "false"))) {
                        System.out.println("Connection active");
                    }
                }
            }
        } finally {
            connection.disconnect();
            System.out.println("Disconnected from Mercure hub");
            // Trigger refetch after any disconnection (token refresh, server close, idle timeout)
            if (running) {
                System.out.println("Triggering refetch of pending requests after disconnection...");
                onNotification.run();
            }
        }
    }

    private void processEvent(String data, Runnable onNotification) {
        try {
            JsonObject json = gson.fromJson(data, JsonObject.class);
            String type = json.has("type") ? json.get("type").getAsString() : null;
            if (NOTIFICATION_TYPE_ANALYSIS_REQUEST_CREATED.equals(type)) {
                JsonObject payload = json.has("payload") ? json.getAsJsonObject("payload") : null;
                String pendingInfo = (payload != null && payload.has("pendingRequestCount"))
                    ? " (pending: " + payload.get("pendingRequestCount").getAsInt() + ")" : "";
                System.out.println("Received " + NOTIFICATION_TYPE_ANALYSIS_REQUEST_CREATED + " notification" + pendingInfo);
                onNotification.run();
            }
        } catch (Exception e) {
            System.out.println("Failed to parse Mercure event: " + e.getMessage() + ", payload=" + data);
        }
    }

    public boolean isRunning() {
        return running;
    }
}
