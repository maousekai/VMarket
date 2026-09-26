package com.vmarket.cart.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.vmarket.cart.CartLimits;
import com.vmarket.cart.TestTokens;
import com.vmarket.cart.config.CartJwtProperties;
import com.vmarket.cart.config.InternalApiProperties;
import com.vmarket.cart.dto.CartGroupDto;
import com.vmarket.cart.dto.CartItemDto;
import com.vmarket.cart.dto.CartResponse;
import com.vmarket.cart.exception.ApiException;
import com.vmarket.cart.service.CartService;
import com.vmarket.cart.service.CheckoutService;

/**
 * Test tầng web của CartController: mapping, validation, body lỗi chuẩn và — quan
 * trọng nhất — <b>chính sách danh tính</b>.
 *
 * <p>Dùng {@code @SpringBootTest} + {@code @AutoConfigureMockMvc} (không phải
 * {@code @WebMvcTest}) để chạy qua ĐÚNG chuỗi filter của Spring Security và
 * {@code CartAuthenticationFilter} với token THẬT (không giả lập SecurityContext).
 * Đây là chỗ từng có lỗi P1 của review PR #23: tin thẳng header {@code X-User-Id}
 * khiến ai gọi được cổng 8085 cũng mạo danh được người khác. Các test dưới đây là
 * lưới chặn cho đúng kịch bản đó.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CartControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private CartJwtProperties jwtProperties;

	@Autowired
	private InternalApiProperties internalApiProperties;

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

	private String tokenFor(String userId) {
		return TestTokens.bearer(TestTokens.accessToken(jwtProperties.getSecret(), userId, List.of("BUYER")));
	}

	// ------------------------------------------------------------ danh tính

	@Test
	void khongCoToken_tra401_voiBodyLoiChuan() throws Exception {
		mockMvc.perform(get("/api/cart"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));

		verifyNoInteractions(cartService);
	}

	/**
	 * Kịch bản P1 của review: gọi thẳng service và tự đặt {@code X-User-Id} của
	 * người khác — trước đây đọc được giỏ của họ, nay phải 401 vì không có danh
	 * tính nào được xác thực.
	 */
	@Test
	void giaMaoHeaderXUserId_khongKemToken_tra401() throws Exception {
		mockMvc.perform(get("/api/cart").header(InternalApiProperties.USER_ID_HEADER, "nan-nhan"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));

		verifyNoInteractions(cartService);
	}

	/** Có header khoá nội bộ nhưng SAI khoá → không được tin {@code X-User-Id}. */
	@Test
	void saiKhoaNoiBo_tra401() throws Exception {
		mockMvc.perform(get("/api/cart")
						.header(InternalApiProperties.HEADER, "khong-phai-khoa-that")
						.header(InternalApiProperties.USER_ID_HEADER, "user-1"))
				.andExpect(status().isUnauthorized());

		verifyNoInteractions(cartService);
	}

	/** Token hết hạn và token sai issuer đều không được coi là đã đăng nhập. */
	@Test
	void tokenHetHanHoacSaiIssuer_tra401() throws Exception {
		for (String token : List.of(
				TestTokens.expiredToken(jwtProperties.getSecret(), "user-1"),
				TestTokens.wrongIssuerToken(jwtProperties.getSecret(), "user-1"))) {
			mockMvc.perform(get("/api/cart").header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(token)))
					.andExpect(status().isUnauthorized());
		}

		verifyNoInteractions(cartService);
	}

	@Test
	void tokenHopLe_tra200_vaDanhTinhLayTuToken() throws Exception {
		when(cartService.getCart("user-1")).thenReturn(sampleCart());

		mockMvc.perform(get("/api/cart").header(HttpHeaders.AUTHORIZATION, tokenFor("user-1")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.userId").value("user-1"))
				.andExpect(jsonPath("$.groups[0].shopId").value("shop-1"))
				.andExpect(jsonPath("$.groups[0].subtotal").value(200000))
				.andExpect(jsonPath("$.totalAmount").value(200000))
				.andExpect(jsonPath("$.totalQuantity").value(2));
	}

	/**
	 * Header {@code X-User-Id} giả mạo bị BỎ QUA khi có token hợp lệ: danh tính lấy
	 * từ {@code sub} của token, không phải từ thứ client tự khai.
	 */
	@Test
	void tokenHopLe_thiHeaderXUserIdGiaBiBoQua() throws Exception {
		when(cartService.getCart("user-1")).thenReturn(sampleCart());

		mockMvc.perform(get("/api/cart")
						.header(HttpHeaders.AUTHORIZATION, tokenFor("user-1"))
						.header(InternalApiProperties.USER_ID_HEADER, "ke-tan-cong"))
				.andExpect(status().isOk());

		verify(cartService).getCart("user-1");
	}

	/** Đường service-to-service: khoá nội bộ đúng + {@code X-User-Id} → dùng được. */
	@Test
	void khoaNoiBoDung_choPhepDocGioCuaUserId() throws Exception {
		when(cartService.getCart("user-1")).thenReturn(sampleCart());

		mockMvc.perform(get("/api/cart")
						.header(InternalApiProperties.HEADER, internalApiProperties.getApiKey())
						.header(InternalApiProperties.USER_ID_HEADER, "user-1"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.userId").value("user-1"));
	}

	// ------------------------------------------------------------ mapping & lỗi

	@Test
	void themItem_thieuTruongBatBuoc_tra400_voiChiTietTungTruong() throws Exception {
		mockMvc.perform(post("/api/cart/items")
						.header(HttpHeaders.AUTHORIZATION, tokenFor("user-1"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"productId": "", "quantity": 0}
								"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
				.andExpect(jsonPath("$.error.details").isArray());
	}

	/** Trần số lượng của {@link CartLimits} được cưỡng chế ngay ở tầng DTO. */
	@Test
	void themItem_quantityVuotTran_tra400() throws Exception {
		mockMvc.perform(post("/api/cart/items")
						.header(HttpHeaders.AUTHORIZATION, tokenFor("user-1"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"productId":"p1","shopId":"shop-1","quantity":%d,"unitPrice":100000}
								""".formatted(CartLimits.MAX_QUANTITY_PER_ITEM + 1)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
	}

	@Test
	void themItem_hopLe_tra201() throws Exception {
		when(cartService.addItem(eq("user-1"), any())).thenReturn(sampleCart());

		mockMvc.perform(post("/api/cart/items")
						.header(HttpHeaders.AUTHORIZATION, tokenFor("user-1"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"productId":"p1","shopId":"shop-1","quantity":2,"unitPrice":100000}
								"""))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.totalAmount").value(200000));
	}

	@Test
	void capNhatItemKhongTonTai_tra404() throws Exception {
		when(cartService.updateQuantity("user-1", "missing", null, 1))
				.thenThrow(ApiException.notFound("CART_ITEM_NOT_FOUND", "Sản phẩm không có trong giỏ hàng"));

		mockMvc.perform(patch("/api/cart/items/missing")
						.header(HttpHeaders.AUTHORIZATION, tokenFor("user-1"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"quantity\":1}"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("CART_ITEM_NOT_FOUND"));
	}

	@Test
	void redisKhongKhaDung_tra503() throws Exception {
		when(cartService.getCart("user-1"))
				.thenThrow(new com.vmarket.cart.exception.CartStorageException("Redis down", null));

		mockMvc.perform(get("/api/cart").header(HttpHeaders.AUTHORIZATION, tokenFor("user-1")))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.error.code").value("CART_STORAGE_UNAVAILABLE"));
	}
}