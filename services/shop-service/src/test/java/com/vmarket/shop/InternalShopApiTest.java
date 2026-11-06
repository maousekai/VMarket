package com.vmarket.shop;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;

/** API nội bộ tra chủ gian hàng cho notification-service (PBL6-23). */
class InternalShopApiTest extends ShopApiTestSupport {

	private static final String KEY = "test-internal-key-0123456789";

	@Test
	void returnsOwnerForAnyStatusWithInternalKey() throws Exception {
		String shopId = register(OWNER_A, "Tiệm Gốm Nội Bộ");

		// Chưa duyệt vẫn trả (đơn cũ của shop bị đình chỉ vẫn cần báo chủ shop).
		mockMvc.perform(get("/internal/shops/{id}/owner", shopId).header("X-Internal-Api-Key", KEY))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.shopId").value(shopId))
				.andExpect(jsonPath("$.ownerId").value(OWNER_A))
				.andExpect(jsonPath("$.name").value("Tiệm Gốm Nội Bộ"));
	}

	@Test
	void rejectsMissingOrWrongKeyAndUnknownShop() throws Exception {
		String shopId = register(OWNER_B, "Tiệm Khoá Sai");

		mockMvc.perform(get("/internal/shops/{id}/owner", shopId))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(get("/internal/shops/{id}/owner", shopId).header("X-Internal-Api-Key", "wrong"))
				.andExpect(status().isUnauthorized());
		// Token người dùng không thay được khoá nội bộ.
		mockMvc.perform(get("/internal/shops/{id}/owner", shopId).header("Authorization", admin()))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(get("/internal/shops/{id}/owner", "01JBQ9YDX7K3M8N5P2R4T6V8ZZ")
						.header("X-Internal-Api-Key", KEY))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("SHOP_NOT_FOUND"));
	}
}
