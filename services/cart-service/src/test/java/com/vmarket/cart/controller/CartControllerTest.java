package com.vmarket.cart.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.vmarket.cart.dto.CartGroupDto;
import com.vmarket.cart.dto.CartItemDto;
import com.vmarket.cart.dto.CartResponse;
import com.vmarket.cart.exception.ApiException;
import com.vmarket.cart.service.CartService;
import com.vmarket.cart.service.CheckoutService;

/**
 * Test tầng web của CartController: mapping, validation, body lỗi chuẩn và
 * chính sách danh tính (thiếu X-User-Id → 401).
 */
@WebMvcTest(CartController.class)
class CartControllerTest {

	private static final String USER_ID_HEADER = CartController.HEADER_USER_ID;

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private CartService cartService;

	@MockitoBean
	private CheckoutService checkoutService;

	private static CartResponse sampleCart() {
		CartItemDto item = new CartItemDto("p1", null, "shop-1", 2,
				new BigDecimal("100000"), new BigDecimal("200000"), Instant.now(), Instant.now());
		CartGroupDto group = new CartGroupDto("shop-1", List.of(item), new BigDecimal("200000"));
		return new CartResponse("user-1", List.of(group), 2, new BigDecimal("200000"), Instant.now());
	}

	@Test
	void getCartWithoutUserIdReturns401WithStandardErrorBody() throws Exception {
		mockMvc.perform(get("/api/cart"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
	}

	@Test
	void getCartReturnsGroupedByShop() throws Exception {
		when(cartService.getCart("user-1")).thenReturn(sampleCart());

		mockMvc.perform(get("/api/cart").header(USER_ID_HEADER, "user-1"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.userId").value("user-1"))
				.andExpect(jsonPath("$.groups[0].shopId").value("shop-1"))
				.andExpect(jsonPath("$.groups[0].subtotal").value(200000))
				.andExpect(jsonPath("$.totalAmount").value(200000))
				.andExpect(jsonPath("$.totalQuantity").value(2));
	}

	@Test
	void addItemWithoutRequiredFieldsReturns400WithFieldDetails() throws Exception {
		mockMvc.perform(post("/api/cart/items")
						.header(USER_ID_HEADER, "user-1")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"productId": "", "quantity": 0}
								"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
				.andExpect(jsonPath("$.error.details").isArray());
	}

	@Test
	void addItemReturns201WithUpdatedCart() throws Exception {
		when(cartService.addItem(eq("user-1"), any())).thenReturn(sampleCart());

		mockMvc.perform(post("/api/cart/items")
						.header(USER_ID_HEADER, "user-1")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"productId":"p1","shopId":"shop-1","quantity":2,"unitPrice":100000}
								"""))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.totalAmount").value(200000));
	}

	@Test
	void updateUnknownItemReturns404WithStandardErrorBody() throws Exception {
		when(cartService.updateQuantity("user-1", "missing", null, 1))
				.thenThrow(ApiException.notFound("CART_ITEM_NOT_FOUND", "Sản phẩm không có trong giỏ hàng"));

		mockMvc.perform(patch("/api/cart/items/missing")
						.header(USER_ID_HEADER, "user-1")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"quantity\":1}"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("CART_ITEM_NOT_FOUND"));
	}

	@Test
	void redisFailureReturns503WithServiceUnavailableBody() throws Exception {
		when(cartService.getCart("user-1"))
				.thenThrow(new com.vmarket.cart.exception.CartStorageException("Redis down", null));

		mockMvc.perform(get("/api/cart").header(USER_ID_HEADER, "user-1"))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.error.code").value("CART_STORAGE_UNAVAILABLE"));
	}
}
