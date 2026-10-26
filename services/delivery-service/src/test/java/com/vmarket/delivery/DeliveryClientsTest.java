package com.vmarket.delivery;

import static org.assertj.core.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import com.vmarket.delivery.client.DeliveryClients;
import com.vmarket.delivery.config.DeliveryProperties;
import com.vmarket.delivery.exception.ApiException;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

class DeliveryClientsTest {
    @Test
    void missingAuthProfileIsAnInactiveCandidateWhileOutagesRemainUnavailable() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/internal/shippers/",
                exchange -> {
                    String path = exchange.getRequestURI().getPath();
                    int status =
                            path.contains("missing") ? 404 : path.contains("offline") ? 503 : 200;
                    byte[] body = "{\"active\":true}".getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(status, body.length);
                    try (var output = exchange.getResponseBody()) {
                        output.write(body);
                    }
                });
        server.start();
        try {
            var properties = new DeliveryProperties();
            properties.setAuthUrl("http://127.0.0.1:" + server.getAddress().getPort());
            properties.setInternalApiKey("test-only-internal-key-0123456789-0123456789");
            var clients = new DeliveryClients(properties, RestClient.builder());
            assertThat(clients.activeShipper("missing")).isFalse();
            assertThat(clients.activeShipper("active")).isTrue();
            assertThatThrownBy(() -> clients.activeShipper("offline"))
                    .isInstanceOfSatisfying(
                            ApiException.class,
                            error ->
                                    assertThat(error.getStatus())
                                            .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        } finally {
            server.stop(0);
        }
    }
}
