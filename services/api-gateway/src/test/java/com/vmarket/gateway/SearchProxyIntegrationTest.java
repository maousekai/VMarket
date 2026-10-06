package com.vmarket.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import com.sun.net.httpserver.HttpServer;

/** Real servlet/proxy path: mocks alone cannot detect multipart resolver bypasses. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "app.rate-limit.enabled=false")
class SearchProxyIntegrationTest {
    static final AtomicInteger forwarded = new AtomicInteger();
    static final HttpServer search = backend();
    @LocalServerPort int port;

    static HttpServer backend() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/ai/search", exchange -> {
                forwarded.incrementAndGet();
                boolean spoofed = exchange.getRequestHeaders().keySet().stream()
                    .anyMatch(key -> key.toLowerCase(java.util.Locale.ROOT).startsWith("x-user-"));
                byte[] body = exchange.getRequestBody().readAllBytes();
                byte[] response = ("{\"spoofed\":" + spoofed + ",\"bytes\":" + body.length + "}")
                    .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
                exchange.close();
            });
            server.start();
            return server;
        } catch (java.io.IOException ex) { throw new java.io.UncheckedIOException(ex); }
    }

    @DynamicPropertySource static void destination(DynamicPropertyRegistry properties) {
        properties.add("AI_SEARCH_SERVICE_URL", () -> "http://127.0.0.1:" + search.getAddress().getPort());
    }
    @AfterAll static void close() { search.stop(0); }

    @Test void guestProxyStripsIdentityAndBoundsChunkedUploads() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        String base = "http://127.0.0.1:" + port;
        byte[] multipart = "--boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"query.png\"\r\nContent-Type: image/png\r\n\r\nphoto\r\n--boundary--\r\n"
            .getBytes(StandardCharsets.UTF_8);
        var result = client.send(HttpRequest.newBuilder(URI.create(base + "/api/ai/search/image"))
            .header("Content-Type", "multipart/form-data; boundary=boundary")
            .header("x-user-id", "forged").header("X-USER-CUSTOM", "forged")
            .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(multipart))).build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(result.body()).contains("\"spoofed\":false", "\"bytes\":" + multipart.length);
        int before = forwarded.get();
        var internal = client.send(HttpRequest.newBuilder(URI.create(base + "/api/products/internal/search-snapshots"))
            .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(internal.statusCode()).isEqualTo(404);
        var oversized = client.send(HttpRequest.newBuilder(URI.create(base + "/api/ai/search/image"))
            .header("Content-Type", "multipart/form-data; boundary=boundary")
            .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(new byte[11_000_001]))).build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(oversized.statusCode()).isEqualTo(413);
        assertThat(oversized.body()).contains("IMAGE_TOO_LARGE");
        // A streaming proxy may open the backend before the limit, but must not report success.
        assertThat(forwarded.get()).isBetween(before, before + 1);
    }
}
