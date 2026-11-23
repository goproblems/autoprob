package autoprob;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ApiClientJwtAuthTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void logsInRefreshesAfterUnauthorizedAndRetriesRequest() throws Exception {
        AtomicInteger loginRequests = new AtomicInteger();
        AtomicInteger refreshRequests = new AtomicInteger();
        AtomicInteger protectedRequests = new AtomicInteger();

        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/auth/login", exchange -> {
            loginRequests.incrementAndGet();
            assertEquals("POST", exchange.getRequestMethod());
            writeJson(exchange, 200, authResponse("access-one", "refresh-one"));
        });
        server.createContext("/api/auth/refresh", exchange -> {
            refreshRequests.incrementAndGet();
            assertEquals("POST", exchange.getRequestMethod());
            writeJson(exchange, 200, authResponse("access-two", "refresh-two"));
        });
        server.createContext("/protected", exchange -> {
            protectedRequests.incrementAndGet();
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            if ("Bearer access-two".equals(authorization)) {
                writeJson(exchange, 200, "{\"value\":\"ok\"}");
            } else {
                writeJson(exchange, 401, "{\"message\":\"expired\"}");
            }
        });
        server.start();

        Properties props = new Properties();
        props.setProperty("baseurl", "http://127.0.0.1:" + server.getAddress().getPort() + "/");
        props.setProperty("api.joseki.test", "protected");
        props.setProperty("api.joseki.username", "worker");
        props.setProperty("api.joseki.password", "password");

        ApiClient.ApiResponse<TestResponse> response = new ApiClient().makeGetRequest(
            "api.joseki.test",
            null,
            null,
            TestResponse.class,
            props
        );

        assertEquals(200, response.getStatusCode());
        assertEquals("ok", response.getData().value);
        assertEquals(1, loginRequests.get());
        assertEquals(1, refreshRequests.get());
        assertEquals(2, protectedRequests.get());
    }

    @Test
    void josekiApiDoesNotFallBackToApiKey() {
        Properties props = new Properties();
        props.setProperty("baseurl", "http://127.0.0.1/");
        props.setProperty("api.joseki.test", "protected");
        props.setProperty("apikey", "legacy-key");

        RuntimeException exception = assertThrows(RuntimeException.class, () ->
            new ApiClient().makeGetRequest(
                "api.joseki.test",
                null,
                null,
                TestResponse.class,
                props
            )
        );

        assertEquals(
            "Missing Josekipedia credentials: configure api.joseki.username and api.joseki.password",
            exception.getMessage()
        );
    }

    private String authResponse(String accessToken, String refreshToken) {
        return String.format(
            "{\"token\":\"%s\",\"expiresAt\":\"%s\","
                + "\"refreshToken\":\"%s\",\"refreshExpiresAt\":\"%s\"}",
            accessToken,
            OffsetDateTime.now().plusHours(1),
            refreshToken,
            OffsetDateTime.now().plusDays(1)
        );
    }

    private void writeJson(HttpExchange exchange, int statusCode, String body) throws IOException {
        byte[] content = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, content.length);
        exchange.getResponseBody().write(content);
        exchange.close();
    }

    private static final class TestResponse {
        private String value;
    }
}
