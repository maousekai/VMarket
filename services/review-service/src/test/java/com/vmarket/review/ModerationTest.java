package com.vmarket.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import com.vmarket.review.client.ReviewClients;

/** FR-REV-04: admin ẩn / khôi phục đánh giá; điểm trung bình đi theo. */
class ModerationTest extends ReviewTestSupport {

	private static final String HIDE = "{\"reason\":\"Ngôn từ xúc phạm\"}";

	@Test
	void hiddenReviewLeavesPublicListAndAverageThenComesBack() throws Exception {
		createReview(BUYER, "order-1", PRODUCT, 5);
		String abusive = createReview(OTHER_BUYER, "order-2", PRODUCT, 1);
		long outboxBefore = outboxRows();

		mvc.perform(post("/api/reviews/admin/{id}/hide", abusive)
				.header("Authorization", token(ADMIN, "ADMIN"))
				.contentType(MediaType.APPLICATION_JSON).content(HIDE))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("HIDDEN"))
				.andExpect(jsonPath("$.moderationReason").value("Ngôn từ xúc phạm"));

		mvc.perform(get("/api/reviews/products/{id}/summary", PRODUCT))
				.andExpect(jsonPath("$.average").value(5.0))
				.andExpect(jsonPath("$.count").value(1));
		mvc.perform(get("/api/reviews/products/{id}", PRODUCT))
				.andExpect(jsonPath("$.totalElements").value(1));
		assertThat(outboxRows()).isEqualTo(outboxBefore + 1);

		mvc.perform(get("/api/reviews/admin").param("status", "HIDDEN").header("Authorization", token(ADMIN, "ADMIN")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(1))
				.andExpect(jsonPath("$.items[0].id").value(abusive));

		mvc.perform(post("/api/reviews/admin/{id}/restore", abusive).header("Authorization", token(ADMIN, "ADMIN")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("VISIBLE"));
		mvc.perform(get("/api/reviews/products/{id}/summary", PRODUCT))
				.andExpect(jsonPath("$.average").value(3.0))
				.andExpect(jsonPath("$.count").value(2));
		assertThat(outboxRows()).isEqualTo(outboxBefore + 2);
	}

	@Test
	void invalidTransitionsAndMissingReasonAreRejected() throws Exception {
		String reviewId = createReview(BUYER, "order-1", PRODUCT, 3);

		mvc.perform(post("/api/reviews/admin/{id}/restore", reviewId).header("Authorization", token(ADMIN, "ADMIN")))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("REVIEW_NOT_HIDDEN"));

		mvc.perform(post("/api/reviews/admin/{id}/hide", reviewId)
				.header("Authorization", token(ADMIN, "ADMIN"))
				.contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"\"}"))
				.andExpect(status().isBadRequest());

		mvc.perform(post("/api/reviews/admin/{id}/hide", "missing")
				.header("Authorization", token(ADMIN, "ADMIN"))
				.contentType(MediaType.APPLICATION_JSON).content(HIDE))
				.andExpect(status().isNotFound());
	}

	@Test
	void onlyAdminCanModerateAndSellerCannotReplyToHiddenReview() throws Exception {
		String reviewId = createReview(BUYER, "order-1", PRODUCT, 1);

		mvc.perform(post("/api/reviews/admin/{id}/hide", reviewId)
				.header("Authorization", token(SELLER, "SELLER"))
				.contentType(MediaType.APPLICATION_JSON).content(HIDE))
				.andExpect(status().isForbidden());

		mvc.perform(post("/api/reviews/admin/{id}/hide", reviewId)
				.header("Authorization", token(ADMIN, "ADMIN"))
				.contentType(MediaType.APPLICATION_JSON).content(HIDE))
				.andExpect(status().isOk());

		when(clients.myShop(anyString())).thenReturn(new ReviewClients.MyShop(SHOP, "ACTIVE"));
		mvc.perform(put("/api/reviews/seller/{id}/reply", reviewId)
				.header("Authorization", token(SELLER, "SELLER"))
				.contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"Xin lỗi bạn\"}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("REVIEW_HIDDEN"));
	}

	private long outboxRows() {
		return jdbc.queryForObject("select count(*) from review_outbox", Long.class);
	}
}
