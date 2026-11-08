package com.vmarket.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import com.vmarket.review.client.ReviewClients;
import com.vmarket.review.exception.ApiException;

/** FR-REV-03: seller phản hồi đánh giá trong gian hàng mình. */
class SellerReplyTest extends ReviewTestSupport {

	private static final String REPLY = "{\"content\":\"Cảm ơn bạn đã ủng hộ shop!\"}";

	@Test
	void sellerRepliesPubliclyAndCanEditAndDelete() throws Exception {
		String reviewId = createReview(BUYER, "order-1", PRODUCT, 5);
		when(clients.myShop(anyString())).thenReturn(new ReviewClients.MyShop(SHOP, "ACTIVE"));

		mvc.perform(put("/api/reviews/seller/{id}/reply", reviewId)
				.header("Authorization", token(SELLER, "SELLER"))
				.contentType(MediaType.APPLICATION_JSON).content(REPLY))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.reply.content").value("Cảm ơn bạn đã ủng hộ shop!"));

		// Phản hồi hiển thị công khai trên trang sản phẩm.
		mvc.perform(get("/api/reviews/products/{id}", PRODUCT))
				.andExpect(jsonPath("$.items[0].reply.content").value("Cảm ơn bạn đã ủng hộ shop!"));

		// Gửi lại = sửa, vẫn chỉ một phản hồi cho mỗi đánh giá.
		mvc.perform(put("/api/reviews/seller/{id}/reply", reviewId)
				.header("Authorization", token(SELLER, "SELLER"))
				.contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"Shop đã gửi quà\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.reply.content").value("Shop đã gửi quà"));
		assertThat(jdbc.queryForObject("select count(*) from seller_replies", Long.class)).isEqualTo(1);

		mvc.perform(delete("/api/reviews/seller/{id}/reply", reviewId).header("Authorization", token(SELLER, "SELLER")))
				.andExpect(status().isNoContent());
		assertThat(jdbc.queryForObject("select count(*) from seller_replies", Long.class)).isZero();
	}

	@Test
	void sellerCannotReplyToAnotherShopsReview() throws Exception {
		String reviewId = createReview(BUYER, "order-1", PRODUCT, 2);
		when(clients.myShop(anyString())).thenReturn(new ReviewClients.MyShop("shop-other", "ACTIVE"));

		mvc.perform(put("/api/reviews/seller/{id}/reply", reviewId)
				.header("Authorization", token("seller-2", "SELLER"))
				.contentType(MediaType.APPLICATION_JSON).content(REPLY))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("REVIEW_NOT_FOUND"));
	}

	@Test
	void replyRequiresSellerRoleShopAndContent() throws Exception {
		String reviewId = createReview(BUYER, "order-1", PRODUCT, 5);

		mvc.perform(put("/api/reviews/seller/{id}/reply", reviewId)
				.header("Authorization", token(BUYER, "BUYER"))
				.contentType(MediaType.APPLICATION_JSON).content(REPLY))
				.andExpect(status().isForbidden());

		mvc.perform(put("/api/reviews/seller/{id}/reply", reviewId)
				.header("Authorization", token(SELLER, "SELLER"))
				.contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"  \"}"))
				.andExpect(status().isBadRequest());

		when(clients.myShop(anyString())).thenThrow(ApiException.forbidden("SHOP_NOT_FOUND", "Bạn chưa có gian hàng"));
		mvc.perform(put("/api/reviews/seller/{id}/reply", reviewId)
				.header("Authorization", token(SELLER, "SELLER"))
				.contentType(MediaType.APPLICATION_JSON).content(REPLY))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("SHOP_NOT_FOUND"));
	}

	@Test
	void suspendedShopCannotReply() throws Exception {
		String reviewId = createReview(BUYER, "order-1", PRODUCT, 2);
		when(clients.myShop(anyString())).thenReturn(new ReviewClients.MyShop(SHOP, "SUSPENDED"));

		mvc.perform(put("/api/reviews/seller/{id}/reply", reviewId)
				.header("Authorization", token(SELLER, "SELLER"))
				.contentType(MediaType.APPLICATION_JSON).content(REPLY))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("SHOP_NOT_ACTIVE"));

		// Vẫn xem được danh sách đánh giá của shop mình.
		mvc.perform(get("/api/reviews/seller").header("Authorization", token(SELLER, "SELLER")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(1));
	}

	@Test
	void sellerListsOwnShopReviews() throws Exception {
		createReview(BUYER, "order-1", PRODUCT, 5);
		createReview(OTHER_BUYER, "order-2", PRODUCT, 1);
		when(clients.myShop(anyString())).thenReturn(new ReviewClients.MyShop(SHOP, "ACTIVE"));

		mvc.perform(get("/api/reviews/seller").header("Authorization", token(SELLER, "SELLER")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(2));

		mvc.perform(get("/api/reviews/seller").param("rating", "1").header("Authorization", token(SELLER, "SELLER")))
				.andExpect(jsonPath("$.totalElements").value(1))
				.andExpect(jsonPath("$.items[0].orderId").value("order-2"))
				.andExpect(jsonPath("$.items[0].buyerId").doesNotExist());
	}
}
