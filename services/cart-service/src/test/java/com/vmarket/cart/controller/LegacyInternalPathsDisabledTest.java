package com.vmarket.cart.controller;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.vmarket.cart.config.InternalApiProperties;
import com.vmarket.cart.dto.CartResponse;
import com.vmarket.cart.service.CartService;
import com.vmarket.cart.service.CheckoutService;

/**
 * PBL6-50: tắt cờ chuyển tiếp thì khoá nội bộ chỉ còn hiệu lực trên {@code /internal/**};
 * ba route {@code /api/cart} của order-service bản cũ trả 401.
 */
@SpringBootTest(properties = "app.internal-api.legacy-cart-paths-enabled=false")
@AutoConfigureMockMvc
class LegacyInternalPathsDisabledTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private InternalApiProperties internalApiProperties;

	@MockitoBean
	private CartService cartService;

	@MockitoBean
	private CheckoutService checkoutService;

	@Test
	void legacyRoutesRejectInternalKey() throws Exception {
		String key = internalApiProperties.getApiKey();
		mockMvc.perform(get("/api/cart").header(InternalApiProperties.HEADER, key)
				.header(InternalApiProperties.USER_ID_HEADER, "user-1")).andExpect(status().isUnauthorized());
		mockMvc.perform(delete("/api/cart").header(InternalApiProperties.HEADER, key)
				.header(InternalApiProperties.USER_ID_HEADER, "user-1")).andExpect(status().isUnauthorized());
		mockMvc.perform(delete("/api/cart/items/p1").header(InternalApiProperties.HEADER, key)
				.header(InternalApiProperties.USER_ID_HEADER, "user-1")).andExpect(status().isUnauthorized());
		verifyNoInteractions(cartService);
	}

	@Test
	void internalRouteStillWorks() throws Exception {
		when(cartService.getCart("user-1"))
				.thenReturn(new CartResponse("user-1", List.of(), 0, BigDecimal.ZERO, Instant.now()));
		mockMvc.perform(get("/internal/cart").header(InternalApiProperties.HEADER, internalApiProperties.getApiKey())
				.header(InternalApiProperties.USER_ID_HEADER, "user-1")).andExpect(status().isOk());
	}
}
