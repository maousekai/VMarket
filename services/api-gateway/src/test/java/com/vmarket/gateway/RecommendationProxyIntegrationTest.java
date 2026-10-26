package com.vmarket.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import com.sun.net.httpserver.HttpServer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "app.rate-limit.enabled=false")
class RecommendationProxyIntegrationTest {
    static final HttpServer backend = backend();
    @LocalServerPort int port;

    static HttpServer backend() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/ai/recommendations", exchange -> {
                boolean spoofed = exchange.getRequestHeaders().keySet().stream()
                    .anyMatch(key -> key.toLowerCase(java.util.Locale.ROOT).startsWith("x-user-"));
                boolean bearer = "Bearer forwarded-token".equals(exchange.getRequestHeaders().getFirst("Authorization"));
                byte[] body = ("{\"spoofed\":" + spoofed + ",\"bearer\":" + bearer + "}")
                    .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return server;
        } catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
    }

    @DynamicPropertySource static void destination(DynamicPropertyRegistry properties) {
        properties.add("RECOMMENDATION_SERVICE_URL", () -> "http://127.0.0.1:" + backend.getAddress().getPort());
    }
    @AfterAll static void close() { backend.stop(0); }

    @Test void guestGetsArePublicAndBearerForwardingDoesNotTrustIdentityHeaders() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        String base = "http://127.0.0.1:" + port;
        for (String path : new String[] {"/for-you", "/similar/product-1", "/health"}) {
            var response = client.send(HttpRequest.newBuilder(URI.create(base + "/api/ai/recommendations" + path))
                .header("X-User-Id", "forged").header("X-USER-CUSTOM", "forged").GET().build(),
                HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("\"spoofed\":false");
        }
        var bearer = client.send(HttpRequest.newBuilder(URI.create(base + "/api/ai/recommendations/for-you"))
            .header("Authorization", "Bearer forwarded-token").GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(bearer.statusCode()).isEqualTo(200);
        assertThat(bearer.body()).contains("\"bearer\":true");
        var protectedPost = client.send(HttpRequest.newBuilder(URI.create(base + "/api/ai/recommendations/for-you"))
            .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(protectedPost.statusCode()).isEqualTo(401);
        var internal = client.send(HttpRequest.newBuilder(URI.create(base + "/api/products/internal/search-snapshots"))
            .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(internal.statusCode()).isEqualTo(404);
    }
}
