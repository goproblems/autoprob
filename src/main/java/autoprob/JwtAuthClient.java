package autoprob;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Properties;

final class JwtAuthClient {
    private static final long DEFAULT_REFRESH_BUFFER_MS = 60_000L;

    private final Gson gson = new Gson();
    private String configurationFingerprint;
    private String accessToken;
    private String refreshToken;
    private long accessTokenExpiresAtMs;
    private long refreshTokenExpiresAtMs;

    boolean hasConfiguredCredentials(Properties props) {
        String username = props.getProperty("api.joseki.username", "").trim();
        String password = props.getProperty("api.joseki.password", "");

        if (username.isEmpty() != password.isEmpty()) {
            throw new IllegalArgumentException(
                "Both api.joseki.username and api.joseki.password must be configured"
            );
        }

        return !username.isEmpty();
    }

    synchronized String getAccessToken(Properties props) throws Exception {
        ensureConfiguration(props);

        long refreshBefore = System.currentTimeMillis() + refreshBufferMs(props);
        if (accessToken != null && accessTokenExpiresAtMs > refreshBefore) {
            return accessToken;
        }

        if (canRefresh(props)) {
            try {
                refresh(props);
                return accessToken;
            } catch (AuthRequestException exception) {
                if (Boolean.parseBoolean(props.getProperty("debug", "false"))) {
                    System.out.println(
                        "JWT refresh failed with HTTP " + exception.statusCode
                            + "; logging in again"
                    );
                }
            }
        }

        login(props);
        return accessToken;
    }

    synchronized String refreshAfterUnauthorized(Properties props, String rejectedToken) throws Exception {
        ensureConfiguration(props);

        if (accessToken != null && !accessToken.equals(rejectedToken)) {
            return accessToken;
        }

        if (canRefresh(props)) {
            try {
                refresh(props);
                return accessToken;
            } catch (AuthRequestException exception) {
                if (Boolean.parseBoolean(props.getProperty("debug", "false"))) {
                    System.out.println(
                        "Rejected JWT could not be refreshed; logging in again"
                    );
                }
            }
        }

        login(props);
        return accessToken;
    }

    private void ensureConfiguration(Properties props) {
        if (!hasConfiguredCredentials(props)) {
            throw new IllegalStateException(
                "Josekipedia JWT credentials are not configured"
            );
        }

        String fingerprint = Integer.toHexString(Objects.hash(
            props.getProperty("baseurl", "").trim(),
            props.getProperty("api.joseki.username", "").trim(),
            props.getProperty("api.joseki.password", "")
        ));
        if (!fingerprint.equals(configurationFingerprint)) {
            configurationFingerprint = fingerprint;
            clearSession();
        }
    }

    private boolean canRefresh(Properties props) {
        return refreshToken != null
            && refreshTokenExpiresAtMs > System.currentTimeMillis() + refreshBufferMs(props);
    }

    private long refreshBufferMs(Properties props) {
        return Long.parseLong(props.getProperty(
            "api.joseki.refresh_buffer_ms",
            String.valueOf(DEFAULT_REFRESH_BUFFER_MS)
        ));
    }

    private void login(Properties props) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("username", props.getProperty("api.joseki.username", "").trim());
        body.addProperty("password", props.getProperty("api.joseki.password", ""));

        applySession(request(
            props,
            props.getProperty("api.joseki.login", "api/auth/login"),
            body
        ));
        logSuccess(props, "login");
    }

    private void refresh(Properties props) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("refreshToken", refreshToken);

        applySession(request(
            props,
            props.getProperty("api.joseki.refresh", "api/auth/refresh"),
            body
        ));
        logSuccess(props, "refresh");
    }

    private JsonObject request(Properties props, String endpoint, JsonObject body) throws Exception {
        URL url = URI.create(buildUrl(props, endpoint)).toURL();
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setConnectTimeout(60_000);
        connection.setReadTimeout(60_000);
        connection.setDoOutput(true);

        byte[] content = gson.toJson(body).getBytes(StandardCharsets.UTF_8);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(content);
        }

        int statusCode = connection.getResponseCode();
        String responseContent = readResponse(connection, statusCode);
        if (statusCode < 200 || statusCode >= 300) {
            throw new AuthRequestException(statusCode, readErrorMessage(responseContent));
        }

        JsonElement response = JsonParser.parseString(responseContent);
        if (!response.isJsonObject()) {
            throw new IllegalStateException("Josekipedia authentication returned invalid JSON");
        }

        return response.getAsJsonObject();
    }

    private void applySession(JsonObject response) {
        accessToken = requiredString(response, "token");
        refreshToken = requiredString(response, "refreshToken");
        accessTokenExpiresAtMs = parseTimestamp(requiredString(response, "expiresAt"));
        refreshTokenExpiresAtMs = parseTimestamp(requiredString(response, "refreshExpiresAt"));
    }

    private String requiredString(JsonObject response, String property) {
        if (!response.has(property) || response.get(property).isJsonNull()) {
            throw new IllegalStateException(
                "Josekipedia authentication response is missing " + property
            );
        }

        String value = response.get(property).getAsString();
        if (value.isEmpty()) {
            throw new IllegalStateException(
                "Josekipedia authentication response contains an empty " + property
            );
        }

        return value;
    }

    private long parseTimestamp(String timestamp) {
        return OffsetDateTime.parse(timestamp).toInstant().toEpochMilli();
    }

    private String buildUrl(Properties props, String endpoint) {
        String baseUrl = props.getProperty("baseurl", "").trim();
        if (baseUrl.isEmpty()) {
            throw new IllegalArgumentException("Missing required property: baseurl");
        }

        return baseUrl.replaceAll("/+$", "") + "/" + endpoint.replaceFirst("^/+", "");
    }

    private String readResponse(HttpURLConnection connection, int statusCode) throws Exception {
        InputStream stream = statusCode >= 200 && statusCode < 300
            ? connection.getInputStream()
            : connection.getErrorStream();
        if (stream == null) {
            return "";
        }

        StringBuilder response = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(stream, StandardCharsets.UTF_8)
        )) {
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
        }
        return response.toString();
    }

    private String readErrorMessage(String responseContent) {
        try {
            JsonElement response = JsonParser.parseString(responseContent);
            if (response.isJsonObject() && response.getAsJsonObject().has("message")) {
                return response.getAsJsonObject().get("message").getAsString();
            }
        } catch (Exception ignored) {
        }
        return responseContent;
    }

    private void logSuccess(Properties props, String action) {
        if (Boolean.parseBoolean(props.getProperty("debug", "false"))) {
            System.out.println(
                "Josekipedia JWT " + action + " succeeded; access token expires at "
                    + OffsetDateTime.ofInstant(
                        java.time.Instant.ofEpochMilli(accessTokenExpiresAtMs),
                        java.time.ZoneOffset.UTC
                    )
            );
        }
    }

    private void clearSession() {
        accessToken = null;
        refreshToken = null;
        accessTokenExpiresAtMs = 0;
        refreshTokenExpiresAtMs = 0;
    }

    private static final class AuthRequestException extends RuntimeException {
        private final int statusCode;

        private AuthRequestException(int statusCode, String message) {
            super("Josekipedia authentication failed: HTTP " + statusCode + " - " + message);
            this.statusCode = statusCode;
        }
    }
}
