package com.vmarket.review;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.vmarket.events.EventPublisher;
import com.vmarket.review.client.ReviewClients;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Nền chung: H2 + Flyway thật, security thật; Order/Shop Service và RabbitMQ được mock. */
@SpringBootTest
@AutoConfigureMockMvc
abstract class ReviewTestSupport {

	static final String SECRET = "test-only-secret-0123456789-0123456789-0123456789";
	static final String BUYER = "buyer-1";
	static final String OTHER_BUYER = "buyer-2";
	static final String SELLER = "seller-1";
	static final String ADMIN = "admin-1";
	static final String SHOP = "shop-1";
	static final String PRODUCT = "product-1";

	@Autowired
	MockMvc mvc;
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	ObjectMapper json;

	@MockitoBean
	ReviewClients clients;
	@MockitoBean
	EventPublisher publisher;

	@BeforeEach
	void cleanDatabase() {
		for (String table : List.of("review_outbox", "seller_replies", "review_images", "reviews")) {
			jdbc.update("delete from " + table);
		}
	}

	static String token(String userId, String... roles) {
		return "Bearer " + Jwts.builder()
				.subject(userId)
				.issuer("auth-service")
				.claim("roles", List.of(roles))
				.issuedAt(new Date())
				.expiration(Date.from(Instant.now().plusSeconds(600)))
				.signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS256)
				.compact();
	}

	/** Order Service trả đơn {@code orderId} ở trạng thái {@code status} chứa các sản phẩm của SHOP. */
	void givenOrder(String orderId, String status, String... productIds) {
		List<Map<String, Object>> items = java.util.Arrays.stream(productIds)
				.map(p -> Map.<String, Object>of("productId", p, "variantId", p + "-v1", "shopId", SHOP, "quantity", 1))
				.toList();
		JsonNode order = json.valueToTree(Map.of("id", orderId, "status", status, "items", items));
		when(clients.myOrder(eq(orderId), anyString())).thenReturn(order);
	}

	String createBody(String orderId, String productId, int rating, List<String> images) {
		return json.writeValueAsString(Map.of("orderId", orderId, "productId", productId, "rating", rating,
				"comment", "Hàng tốt", "imageUrls", images));
	}

	/** Tạo một đánh giá hợp lệ và trả id. */
	String createReview(String buyer, String orderId, String productId, int rating) throws Exception {
		givenOrder(orderId, "DELIVERED", productId);
		String body = mvc.perform(post("/api/reviews")
				.header("Authorization", token(buyer, "BUYER"))
				.contentType(MediaType.APPLICATION_JSON)
				.content(createBody(orderId, productId, rating, List.of())))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
		return json.readTree(body).path("id").asString();
	}
}
