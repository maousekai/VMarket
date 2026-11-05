package com.vmarket.notification.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.vmarket.notification.config.NotificationProperties;

/** Gọi API nội bộ qua HTTP thật (server JDK cục bộ): header khoá, ánh xạ JSON, xử lý 404 / 5xx. */
class InternalClientsTest {

	private HttpServer server;
	private final List<String> keys = new CopyOnWriteArrayList<>();
	private InternalClients clients;

	@BeforeEach
	void start() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/internal/delivery/orders/o1", ex -> reply(ex, 200, """
				{"id":"o1","status":"PENDING","userId":"buyer-1","totalAmount":125000.00,"paymentMethod":"COD",
				 "recipientName":"An","items":[{"productId":"p1","shopId":"s1","quantity":1,"unitPrice":1},
				 {"productId":"p2","shopId":"s1","quantity":1},{"productId":"p3","shopId":"s2","quantity":1}]}"""));
		server.createContext("/internal/delivery/orders/missing", ex -> reply(ex, 404, "{}"));
		server.createContext("/internal/delivery/orders/boom", ex -> reply(ex, 503, "{}"));
		server.createContext("/internal/users/u1/contact", ex -> reply(ex, 200,
				"{\"userId\":\"u1\",\"email\":\"an@example.com\",\"username\":\"an\",\"emailVerified\":true,\"suspended\":false}"));
		server.createContext("/internal/users/ghost/contact", ex -> reply(ex, 404, "{}"));
		server.start();

		String base = "http://127.0.0.1:" + server.getAddress().getPort();
		NotificationProperties properties = new NotificationProperties();
		properties.setInternalApiKey("secret-key");
		properties.setOrderUrl(base);
		properties.setAuthUrl(base);
		clients = new InternalClients(properties, RestClient.builder());
	}

	@AfterEach
	void stop() {
		server.stop(0);
	}

	private void reply(HttpExchange exchange, int status, String body) throws IOException {
		keys.add(exchange.getRequestHeaders().getFirst("X-Internal-Api-Key"));
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("Content-Type", "application/json");
		exchange.sendResponseHeaders(status, bytes.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(bytes);
		}
	}

	@Test
	void readsOrderSnapshotWithInternalKey() {
		OrderSnapshot order = clients.order("o1").orElseThrow();

		assertThat(order.userId()).isEqualTo("buyer-1");
		assertThat(order.isCod()).isTrue();
		assertThat(order.shopIds()).containsExactly("s1", "s2");
		assertThat(keys).containsOnly("secret-key");
	}

	@Test
	void notFoundBecomesEmptyButServerErrorIsThrownForRetry() {
		assertThat(clients.order("missing")).isEmpty();
		assertThat(clients.contact("ghost")).isEmpty();
		assertThatThrownBy(() -> clients.order("boom")).isInstanceOf(HttpServerErrorException.class);
	}

	@Test
	void readsContact() {
		UserContact contact = clients.contact("u1").orElseThrow();

		assertThat(contact.email()).isEqualTo("an@example.com");
		assertThat(contact.emailVerified()).isTrue();
	}
}
