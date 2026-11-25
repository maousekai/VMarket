package com.vmarket.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.sun.net.httpserver.HttpServer;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * PBL6-50: đi qua servlet + proxy thật của gateway tới một service giả lập, để chứng minh
 * {@code X-Internal-Api-Key} do client gửi không bao giờ tới service đích (test filter
 * đơn lẻ không bắt được nếu bước proxy thêm lại header).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "app.rate-limit.enabled=false",
				"auth.jwt.secret=" + InternalKeyProxyIntegrationTest.JWT_KEY })
class InternalKeyProxyIntegrationTest {

	static final String JWT_KEY = "proxy-test-only-hs256-key-0123456789abcdef";

	static final AtomicInteger forwarded = new AtomicInteger();
	static final HttpServer backend = backend();

	@LocalServerPort
	int port;

	/** Service giả: trả về có nhận được header khoá nội bộ hay không (không phân biệt hoa thường). */
	static HttpServer backend() {
		try {
			HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			for (String context : List.of("/api/products", "/api/cart")) {
				server.createContext(context, exchange -> {
					forwarded.incrementAndGet();
					long keyValues = exchange.getRequestHeaders().entrySet().stream()
							.filter(e -> e.getKey().toLowerCase(Locale.ROOT).equals("x-internal-api-key"))
							.mapToLong(e -> e.getValue().size())
							.sum();
					byte[] body = ("{\"internalKeyValues\":" + keyValues + "}").getBytes(StandardCharsets.UTF_8);
					exchange.getResponseHeaders().set("Content-Type", "application/json");
					exchange.sendResponseHeaders(200, body.length);
					exchange.getResponseBody().write(body);
					exchange.close();
				});
			}
			server.start();
			return server;
		} catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	@DynamicPropertySource
	static void destinations(DynamicPropertyRegistry properties) {
		String url = "http://127.0.0.1:" + backend.getAddress().getPort();
		properties.add("PRODUCT_SERVICE_URL", () -> url);
		properties.add("CART_SERVICE_URL", () -> url);
	}

	@AfterAll
	static void close() {
		backend.stop(0);
	}

	private static String token() {
		return Jwts.builder()
				.issuer("auth-service")
				.subject("01JRX8Z0M0P8QF3W9K2T7Y6C4B")
				.claim("roles", List.of("BUYER"))
				.claim("email_verified", true)
				.issuedAt(Date.from(Instant.now()))
				.expiration(Date.from(Instant.now().plusSeconds(60)))
				.signWith(Keys.hmacShaKeyFor(JWT_KEY.getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS256)
				.compact();
	}

	private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

	private HttpResponse<String> send(HttpRequest.Builder builder) throws Exception {
		return CLIENT.send(builder.timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
	}

	private URI uri(String path) {
		return URI.create("http://127.0.0.1:" + port + path);
	}

	@Test
	void publicRouteDoesNotForwardClientInternalKey() throws Exception {
		var res = send(HttpRequest.newBuilder(uri("/api/products/01JRX8Z0M0P8QF3W9K2T7Y6C4C"))
				.header("X-Internal-Api-Key", "leaked").GET());
		assertThat(res.statusCode()).isEqualTo(200);
		assertThat(res.body()).contains("\"internalKeyValues\":0");
	}

	@Test
	void protectedRouteDoesNotForwardClientInternalKey() throws Exception {
		var res = send(HttpRequest.newBuilder(uri("/api/cart"))
				.header("Authorization", "Bearer " + token())
				.header("X-Internal-Api-Key", "leaked").GET());
		assertThat(res.statusCode()).isEqualTo(200);
		assertThat(res.body()).contains("\"internalKeyValues\":0");
	}

	@Test
	void lowercaseHeaderIsNotForwarded() throws Exception {
		var res = send(HttpRequest.newBuilder(uri("/api/products/01JRX8Z0M0P8QF3W9K2T7Y6C4C"))
				.header("x-internal-api-key", "leaked").GET());
		assertThat(res.statusCode()).isEqualTo(200);
		assertThat(res.body()).contains("\"internalKeyValues\":0");
	}

	/**
	 * Gửi thô qua socket để giữ nguyên nhiều dòng header trùng tên khác hoa thường
	 * (HttpClient gộp chúng thành một tên).
	 */
	@Test
	void repeatedHeaderLinesWithMixedCaseAreNotForwarded() throws Exception {
		for (String path : List.of("/api/products/01JRX8Z0M0P8QF3W9K2T7Y6C4C", "/api/cart")) {
			String auth = path.startsWith("/api/cart") ? "Authorization: Bearer " + token() + "\r\n" : "";
			String raw = "GET " + path + " HTTP/1.1\r\n"
					+ "Host: 127.0.0.1:" + port + "\r\n"
					+ auth
					+ "X-Internal-Api-Key: leaked-1\r\n"
					+ "x-internal-api-key: leaked-2\r\n"
					+ "X-INTERNAL-API-KEY: leaked-3\r\n"
					+ "Connection: close\r\n\r\n";
			String response = sendRaw(raw);
			assertThat(response).as(path).startsWith("HTTP/1.1 200");
			assertThat(response).as(path).contains("\"internalKeyValues\":0");
		}
	}

	/** HttpClient không cho tạo URI có %xx hỏng, nên gửi thô qua socket. */
	@Test
	void malformedEncodingIsRejectedAndNeverForwarded() throws Exception {
		for (String path : List.of("/api/products/%ZZ/internal/test", "/api/products/%2/internal/test")) {
			int before = forwarded.get();
			String response = sendRaw("GET " + path + " HTTP/1.1\r\nHost: 127.0.0.1:" + port
					+ "\r\nConnection: close\r\n\r\n");
			// Qua server thật, Tomcat từ chối %xx hỏng (400) trước cả filter; body INVALID_PATH
			// của filter (khi chạy sau một connector dễ dãi hơn) đã chốt ở JwtAuthenticationFilterTest.
			assertThat(response).as(path).startsWith("HTTP/1.1 400");
			assertThat(forwarded.get()).as(path).isEqualTo(before);
		}
	}

	/** Path nội bộ mã hoá %xx vẫn bị chặn 404 khi đi qua proxy thật, không tới service. */
	@Test
	void encodedInternalPathIsBlockedThroughProxy() throws Exception {
		for (String path : List.of("/api/products/%69nternal/search-snapshots", "/api/cart/%69nternal/x")) {
			int before = forwarded.get();
			String response = sendRaw("GET " + path + " HTTP/1.1\r\nHost: 127.0.0.1:" + port
					+ "\r\nConnection: close\r\n\r\n");
			assertThat(response).as(path).startsWith("HTTP/1.1 404");
			assertThat(forwarded.get()).as(path).isEqualTo(before);
		}
	}

	private String sendRaw(String request) throws IOException {
		try (Socket socket = newSocket();
				BufferedReader in = new BufferedReader(
						new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
			OutputStream out = socket.getOutputStream();
			out.write(request.getBytes(StandardCharsets.US_ASCII));
			out.flush();
			return in.lines().reduce("", (a, b) -> a + b + "\n");
		}
	}

	/** Có timeout đọc: server không đóng kết nối thì test fail thay vì treo. */
	private Socket newSocket() throws IOException {
		Socket socket = new Socket("127.0.0.1", port);
		socket.setSoTimeout(5_000);
		return socket;
	}
}
