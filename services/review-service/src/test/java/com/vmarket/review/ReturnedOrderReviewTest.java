package com.vmarket.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import com.vmarket.events.EventEnvelope;
import com.vmarket.events.ReturnResolved;
import com.vmarket.events.StockItem;
import com.vmarket.review.client.ReviewClients;
import com.vmarket.review.event.ReturnResolvedReviewConsumer;
import com.vmarket.review.exception.ApiException;
import com.vmarket.review.service.LegacyReturnReconciler;

/**
 * PBL6-35: đánh giá chỉ hiện từ khi nhận hàng tới trước khi trả hàng. Khi Order Service chốt trả hàng /
 * hoàn tiền ({@code ReturnResolved}), đánh giá của các sản phẩm được trả không còn hiển thị và không viết
 * thêm được; sản phẩm khác trong cùng đơn (gian hàng khác) không bị ảnh hưởng.
 */
class ReturnedOrderReviewTest extends ReviewTestSupport {

	@Autowired
	ReturnResolvedReviewConsumer consumer;
	@Autowired
	LegacyReturnReconciler reconciler;

	private void returnResolved(String orderId) {
		returnResolved(orderId, PRODUCT);
	}

	/** Yêu cầu trả hàng chỉ gồm các sản phẩm {@code productIds} của đơn (một gian hàng). */
	private void returnResolved(String orderId, String... productIds) {
		List<StockItem> items = java.util.Arrays.stream(productIds).map(p -> new StockItem(p, p + "-v1", 1)).toList();
		consumer.handle(new ReturnResolved("return-" + orderId, orderId, BigDecimal.TEN, true, items),
				(EventEnvelope) null);
	}

	private long outboxRows() {
		return jdbc.queryForObject("select count(*) from review_outbox", Long.class);
	}

	@Test
	void reviewOfReturnedOrderLeavesPublicListAndAverage() throws Exception {
		String returned = createReview(BUYER, "order-1", PRODUCT, 5);
		createReview(OTHER_BUYER, "order-2", PRODUCT, 3);
		long before = outboxRows();

		returnResolved("order-1");

		mvc.perform(get("/api/reviews/products/{id}", PRODUCT))
				.andExpect(jsonPath("$.totalElements").value(1))
				.andExpect(jsonPath("$.items[0].rating").value(3));
		mvc.perform(get("/api/reviews/products/{id}/summary", PRODUCT))
				.andExpect(jsonPath("$.count").value(1))
				.andExpect(jsonPath("$.average").value(3.0));
		// Product Service nhận điểm mới qua outbox.
		assertThat(outboxRows()).isEqualTo(before + 1);

		// Người viết vẫn thấy đánh giá của mình với trạng thái RETURNED để giao diện giải thích.
		mvc.perform(get("/api/reviews/me").param("orderId", "order-1").header("Authorization", token(BUYER, "BUYER")))
				.andExpect(jsonPath("$.items[0].id").value(returned))
				.andExpect(jsonPath("$.items[0].status").value("RETURNED"));
	}

	@Test
	void returnedOrderCannotBeReviewedAnymore() throws Exception {
		returnResolved("order-1");
		givenOrder("order-1", "COMPLETED", PRODUCT);

		mvc.perform(post("/api/reviews")
				.header("Authorization", token(BUYER, "BUYER"))
				.contentType(MediaType.APPLICATION_JSON)
				.content(createBody("order-1", PRODUCT, 5, List.of())))
				.andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.error.code").value("ORDER_RETURNED"));
		assertThat(jdbc.queryForObject("select count(*) from reviews", Long.class)).isZero();
	}

	@Test
	void returnedReviewCannotBeRestoredHiddenOrReplied() throws Exception {
		String reviewId = createReview(BUYER, "order-1", PRODUCT, 4);
		returnResolved("order-1");

		mvc.perform(post("/api/reviews/admin/{id}/restore", reviewId).header("Authorization", token(ADMIN, "ADMIN")))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("REVIEW_RETURNED"));
		mvc.perform(post("/api/reviews/admin/{id}/hide", reviewId)
				.header("Authorization", token(ADMIN, "ADMIN"))
				.contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"spam\"}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("REVIEW_RETURNED"));

		when(clients.myShop(anyString())).thenReturn(new ReviewClients.MyShop(SHOP, "ACTIVE"));
		mvc.perform(put("/api/reviews/seller/{id}/reply", reviewId)
				.header("Authorization", token(SELLER, "SELLER"))
				.contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"Cảm ơn\"}"))
				.andExpect(status().isConflict());
		// Seller vẫn thấy đánh giá kèm lý do trong danh sách của shop.
		mvc.perform(get("/api/reviews/seller").header("Authorization", token(SELLER, "SELLER")))
				.andExpect(jsonPath("$.items[0].status").value("RETURNED"))
				.andExpect(jsonPath("$.items[0].moderationReason").value("Sản phẩm đã được trả hàng / hoàn tiền"));
	}

	@Test
	void repeatedEventIsIdempotentAndAdminCanFilterReturnedReviews() throws Exception {
		createReview(BUYER, "order-1", PRODUCT, 2);
		returnResolved("order-1");
		long after = outboxRows();

		returnResolved("order-1");

		assertThat(outboxRows()).isEqualTo(after);
		assertThat(jdbc.queryForObject("select count(*) from returned_items", Long.class)).isEqualTo(1);
		mvc.perform(get("/api/reviews/admin").param("status", "RETURNED").header("Authorization", token(ADMIN, "ADMIN")))
				.andExpect(jsonPath("$.totalElements").value(1));
	}

	@Test
	void returnBeforeAnyReviewOnlyBlocksThatOrder() throws Exception {
		returnResolved("order-1");
		createReview(BUYER, "order-2", PRODUCT, 5);

		mvc.perform(get("/api/reviews/products/{id}", PRODUCT)).andExpect(jsonPath("$.totalElements").value(1));
	}

	@Test
	void returnOfOneShopKeepsReviewsOfOtherProductsInTheOrder() throws Exception {
		String otherProduct = "product-2";
		String returnedReview = createReview(BUYER, "order-1", PRODUCT, 2);
		String keptReview = createReview(BUYER, "order-1", otherProduct, 5);

		returnResolved("order-1", PRODUCT);

		mvc.perform(get("/api/reviews/me").param("orderId", "order-1").header("Authorization", token(BUYER, "BUYER")))
				.andExpect(jsonPath("$.items[?(@.id == '" + returnedReview + "')].status").value("RETURNED"))
				.andExpect(jsonPath("$.items[?(@.id == '" + keptReview + "')].status").value("VISIBLE"));
		mvc.perform(get("/api/reviews/products/{id}", otherProduct)).andExpect(jsonPath("$.totalElements").value(1));
	}

	@Test
	void productNotInTheReturnCanStillBeReviewed() throws Exception {
		String otherProduct = "product-2";
		returnResolved("order-1", PRODUCT);

		createReview(BUYER, "order-1", otherProduct, 4);

		givenOrder("order-1", "COMPLETED", PRODUCT, otherProduct);
		mvc.perform(post("/api/reviews")
				.header("Authorization", token(BUYER, "BUYER"))
				.contentType(MediaType.APPLICATION_JSON)
				.content(createBody("order-1", PRODUCT, 5, List.of())))
				.andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.error.code").value("ORDER_RETURNED"));
	}

	/** Dữ liệu trả hàng của bản đầu: chỉ biết đơn, mọi đánh giá của đơn đã bị chuyển RETURNED. */
	private void givenLegacyReturn(String orderId) {
		jdbc.update("insert into legacy_returned_orders (order_id, return_id, resolved_at) values (?, ?, current_timestamp)",
				orderId, "return-" + orderId);
		jdbc.update("update reviews set status = 'RETURNED', moderation_reason = 'Đơn hàng đã được trả hàng / hoàn tiền'"
				+ " where order_id = ?", orderId);
	}

	private void reviewNewProduct(String orderId, String productId, int expectedStatus) throws Exception {
		givenOrder(orderId, "COMPLETED", productId);
		mvc.perform(post("/api/reviews")
				.header("Authorization", token(BUYER, "BUYER"))
				.contentType(MediaType.APPLICATION_JSON)
				.content(createBody(orderId, productId, 5, List.of())))
				.andExpect(status().is(expectedStatus));
	}

	@Test
	void legacyReturnBlocksTheWholeOrderUntilReconciledWithOrderService() throws Exception {
		String returnedReview = createReview(BUYER, "order-1", PRODUCT, 2);
		String otherShopReview = createReview(BUYER, "order-1", "product-2", 5);
		givenLegacyReturn("order-1");

		// Chưa đối chiếu: chặn cả đơn như trước, kể cả sản phẩm chưa có đánh giá.
		reviewNewProduct("order-1", "product-3", 422);

		when(clients.returnedProducts("order-1")).thenReturn(List.of(PRODUCT));
		reconciler.reconcile();

		assertThat(jdbc.queryForObject("select count(*) from legacy_returned_orders", Long.class)).isZero();
		mvc.perform(get("/api/reviews/me").param("orderId", "order-1").header("Authorization", token(BUYER, "BUYER")))
				.andExpect(jsonPath("$.items[?(@.id == '" + returnedReview + "')].status").value("RETURNED"))
				.andExpect(jsonPath("$.items[?(@.id == '" + otherShopReview + "')].status").value("VISIBLE"));
		// Sản phẩm đã trả vẫn bị chặn (không cần có đánh giá trước), sản phẩm khác viết được.
		reviewNewProduct("order-1", PRODUCT, 422);
		assertThat(jdbc.queryForObject("select count(*) from returned_items where order_id = 'order-1'", Long.class))
				.isEqualTo(1);
		reviewNewProduct("order-1", "product-3", 201);
	}

	@Test
	void legacyReturnWithoutReviewsKeepsItsBlockForTheReturnedProduct() throws Exception {
		givenLegacyReturn("order-1");
		when(clients.returnedProducts("order-1")).thenReturn(List.of(PRODUCT));

		reconciler.reconcile();

		reviewNewProduct("order-1", PRODUCT, 422);
	}

	@Test
	void legacyReturnStaysBlockedWhenOrderServiceIsUnavailable() throws Exception {
		givenLegacyReturn("order-1");
		when(clients.returnedProducts("order-1"))
				.thenThrow(ApiException.unavailable("DEPENDENCY_UNAVAILABLE", "Order Service chưa sẵn sàng"));

		reconciler.reconcile();

		assertThat(jdbc.queryForObject("select count(*) from legacy_returned_orders", Long.class)).isEqualTo(1);
		reviewNewProduct("order-1", "product-3", 422);
	}
}
