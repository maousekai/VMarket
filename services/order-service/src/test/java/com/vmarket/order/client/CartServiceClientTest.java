package com.vmarket.order.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.vmarket.order.exception.ApiException;

/**
 * Chốt các route cart-service mà order-service gọi khi đặt hàng: phải là route nội
 * bộ {@code /internal/cart/**} (PBL6-50) — route công khai {@code /api/cart/**}
 * không còn nhận khoá nội bộ.
 */
class CartServiceClientTest {

	private MockRestServiceServer server;
	private CartServiceClient client;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder().baseUrl("http://cart");
		server = MockRestServiceServer.bindTo(builder).build();
		client = new CartServiceClient(builder.build());
	}

	@Test
	void getCart_callsInternalRouteWithUserId() {
		server.expect(requestTo("http://cart/internal/cart"))
				.andExpect(method(HttpMethod.GET))
				.andExpect(header("X-User-Id", "user-1"))
				.andRespond(withSuccess("""
						{"userId":"user-1","groups":[{"shopId":"s1","items":[{"productId":"p1","variantId":null,
						"shopId":"s1","quantity":2,"unitPrice":1000,"lineTotal":2000}],"subtotal":2000}],
						"totalQuantity":2,"totalAmount":2000}
						""", MediaType.APPLICATION_JSON));

		CartServiceClient.CartView cart = client.getCart("user-1");

		assertThat(cart.groups()).hasSize(1);
		assertThat(cart.groups().get(0).items().get(0).productId()).isEqualTo("p1");
		server.verify();
	}

	@Test
	void clearCart_andRemoveItem_callInternalRoutes() {
		server.expect(requestTo("http://cart/internal/cart"))
				.andExpect(method(HttpMethod.DELETE))
				.andExpect(header("X-User-Id", "user-1"))
				.andRespond(withSuccess());
		server.expect(requestTo("http://cart/internal/cart/items/p1?variantId=v1"))
				.andExpect(method(HttpMethod.DELETE))
				.andExpect(header("X-User-Id", "user-1"))
				.andRespond(withSuccess());

		assertThat(client.clearCart("user-1")).isTrue();
		assertThat(client.removeCartItem("user-1", "p1", "v1")).isTrue();
		server.verify();
	}

	/** cart-service bản cũ chưa có /internal/cart: 404 mặc định của Spring -> 502, không phải lỗi nghiệp vụ. */
	@Test
	void getCart_404WithoutErrorEnvelope_isCartServiceError() {
		server.expect(requestTo("http://cart/internal/cart"))
				.andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
						.body("{\"timestamp\":\"2026-11-23T00:00:00Z\",\"status\":404,\"path\":\"/internal/cart\"}"));

		assertThatThrownBy(() -> client.getCart("user-1"))
				.isInstanceOfSatisfying(ApiException.class, e -> {
					assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
					assertThat(e.getCode()).isEqualTo("CART_SERVICE_ERROR");
				});
	}

	@Test
	void getCart_401_failsWithoutReturningACart() {
		server.expect(requestTo("http://cart/internal/cart"))
				.andRespond(withStatus(HttpStatus.UNAUTHORIZED).contentType(MediaType.APPLICATION_JSON)
						.body("{\"error\":{\"code\":\"UNAUTHORIZED\",\"message\":\"x\"}}"));

		assertThatThrownBy(() -> client.getCart("user-1")).isInstanceOf(ApiException.class);
	}

	/** Xoá item sau khi đơn đã commit: 404/401 chỉ trả false, không ném lỗi làm hỏng đơn. */
	@Test
	void removeCartItem_404Or401_returnsFalse() {
		server.expect(requestTo("http://cart/internal/cart/items/p1"))
				.andRespond(withStatus(HttpStatus.NOT_FOUND));
		server.expect(requestTo("http://cart/internal/cart/items/p2"))
				.andRespond(withStatus(HttpStatus.UNAUTHORIZED));

		assertThat(client.removeCartItem("user-1", "p1", null)).isFalse();
		assertThat(client.removeCartItem("user-1", "p2", null)).isFalse();
		server.verify();
	}
}
