package com.vmarket.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.vmarket.events.EventType;
import com.vmarket.events.ReviewCreated;
import com.vmarket.review.exception.ApiException;
import com.vmarket.review.service.RatingService;
import com.vmarket.review.service.ReviewOutboxPublisher;

/** FR-REV-01 (viết đánh giá) và FR-REV-02 (tổng hợp điểm + ReviewCreated). */
class ReviewWriteAndRatingTest extends ReviewTestSupport {

	@Autowired
	RatingService ratingService;
	@Autowired
	Clock clock;
	@Autowired
	PlatformTransactionManager transactionManager;

	@Test
	void buyerReviewsProductOfCompletedOrder() throws Exception {
		givenOrder("order-completed", "COMPLETED", PRODUCT);

		mvc.perform(post("/api/reviews")
				.header("Authorization", token(BUYER, "BUYER"))
				.contentType(MediaType.APPLICATION_JSON)
				.content(createBody("order-completed", PRODUCT, 4, List.of())))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.rating").value(4));
	}

	@Test
	void buyerReviewsDeliveredProductWithImages() throws Exception {
		givenOrder("order-1", "DELIVERED", PRODUCT);
		List<String> images = List.of("https://cdn.vmarket.vn/r/1.jpg", "https://cdn.vmarket.vn/r/2.jpg");

		mvc.perform(post("/api/reviews")
				.header("Authorization", token(BUYER, "BUYER"))
				.contentType(MediaType.APPLICATION_JSON)
				.content(createBody("order-1", PRODUCT, 5, images)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.buyerId").value(BUYER))
				.andExpect(jsonPath("$.shopId").value(SHOP))
				.andExpect(jsonPath("$.variantId").value(PRODUCT + "-v1"))
				.andExpect(jsonPath("$.rating").value(5))
				.andExpect(jsonPath("$.status").value("VISIBLE"))
				.andExpect(jsonPath("$.imageUrls.length()").value(2))
				.andExpect(jsonPath("$.imageUrls[0]").value(images.get(0)));

		assertThat(jdbc.queryForObject("select count(*) from review_outbox where product_id = ?", Long.class, PRODUCT))
				.isEqualTo(1);
	}

	@Test
	void eachProductInOrderCanBeReviewedOnlyOnce() throws Exception {
		createReview(BUYER, "order-1", PRODUCT, 4);

		mvc.perform(post("/api/reviews")
				.header("Authorization", token(BUYER, "BUYER"))
				.contentType(MediaType.APPLICATION_JSON)
				.content(createBody("order-1", PRODUCT, 1, List.of())))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("REVIEW_ALREADY_EXISTS"));

		// Sản phẩm khác trong cùng đơn vẫn đánh giá được; cùng sản phẩm ở đơn khác cũng vậy.
		givenOrder("order-1", "DELIVERED", PRODUCT, "product-2");
		createReview(BUYER, "order-1", "product-2", 3);
		createReview(BUYER, "order-2", PRODUCT, 5);
		assertThat(jdbc.queryForObject("select count(*) from reviews", Long.class)).isEqualTo(3);
	}

	@Test
	void orderMustBeCompleted() throws Exception {
		givenOrder("order-1", "SHIPPED", PRODUCT);

		mvc.perform(post("/api/reviews")
				.header("Authorization", token(BUYER, "BUYER"))
				.contentType(MediaType.APPLICATION_JSON)
				.content(createBody("order-1", PRODUCT, 5, List.of())))
				.andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.error.code").value("ORDER_NOT_COMPLETED"));
	}

	@Test
	void productMustBelongToOrder() throws Exception {
		givenOrder("order-1", "DELIVERED", "product-other");

		mvc.perform(post("/api/reviews")
				.header("Authorization", token(BUYER, "BUYER"))
				.contentType(MediaType.APPLICATION_JSON)
				.content(createBody("order-1", PRODUCT, 5, List.of())))
				.andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.error.code").value("PRODUCT_NOT_IN_ORDER"));
	}

	@Test
	void orderOfAnotherBuyerIsNotFound() throws Exception {
		when(clients.myOrder(eq("order-x"), anyString()))
				.thenThrow(ApiException.notFound("ORDER_NOT_FOUND", "Không tìm thấy đơn hàng của bạn"));

		mvc.perform(post("/api/reviews")
				.header("Authorization", token(OTHER_BUYER, "BUYER"))
				.contentType(MediaType.APPLICATION_JSON)
				.content(createBody("order-x", PRODUCT, 5, List.of())))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));
	}

	@Test
	void ratingAndImageLimitsAreValidated() throws Exception {
		givenOrder("order-1", "DELIVERED", PRODUCT);
		List<String> sixImages = IntStream.range(0, 6).mapToObj(i -> "https://cdn.vmarket.vn/" + i + ".jpg").toList();

		mvc.perform(post("/api/reviews")
				.header("Authorization", token(BUYER, "BUYER"))
				.contentType(MediaType.APPLICATION_JSON)
				.content(createBody("order-1", PRODUCT, 5, sixImages)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

		for (int rating : new int[] { 0, 6 }) {
			mvc.perform(post("/api/reviews")
					.header("Authorization", token(BUYER, "BUYER"))
					.contentType(MediaType.APPLICATION_JSON)
					.content(createBody("order-1", PRODUCT, rating, List.of())))
					.andExpect(status().isBadRequest());
		}

		mvc.perform(post("/api/reviews")
				.header("Authorization", token(BUYER, "BUYER"))
				.contentType(MediaType.APPLICATION_JSON)
				.content(createBody("order-1", PRODUCT, 5, List.of("javascript:alert(1)"))))
				.andExpect(status().isBadRequest());

		assertThat(jdbc.queryForObject("select count(*) from reviews", Long.class)).isZero();
	}

	@Test
	void writingRequiresBuyerToken() throws Exception {
		mvc.perform(post("/api/reviews")
				.contentType(MediaType.APPLICATION_JSON)
				.content(createBody("order-1", PRODUCT, 5, List.of())))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));

		mvc.perform(post("/api/reviews")
				.header("Authorization", token(ADMIN, "ADMIN"))
				.contentType(MediaType.APPLICATION_JSON)
				.content(createBody("order-1", PRODUCT, 5, List.of())))
				.andExpect(status().isForbidden());
	}

	@Test
	void summaryAveragesVisibleReviewsOfProductAndShop() throws Exception {
		createReview(BUYER, "order-1", PRODUCT, 5);
		createReview(BUYER, "order-2", PRODUCT, 4);
		createReview(OTHER_BUYER, "order-3", PRODUCT, 4);
		createReview(OTHER_BUYER, "order-3b", "product-2", 1);

		mvc.perform(get("/api/reviews/products/{id}/summary", PRODUCT))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.average").value(4.33))
				.andExpect(jsonPath("$.count").value(3))
				.andExpect(jsonPath("$.distribution.5").value(1))
				.andExpect(jsonPath("$.distribution.4").value(2))
				.andExpect(jsonPath("$.distribution.1").value(0));

		mvc.perform(get("/api/reviews/shops/{id}/summary", SHOP))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.average").value(3.5))
				.andExpect(jsonPath("$.count").value(4));

		mvc.perform(get("/api/reviews/products/{id}/summary", "no-reviews"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.average").value(0.0))
				.andExpect(jsonPath("$.count").value(0));
	}

	@Test
	void productReviewsArePublicPagedAndFilterable() throws Exception {
		createReview(BUYER, "order-1", PRODUCT, 5);
		createReview(BUYER, "order-2", PRODUCT, 3);
		createReview(OTHER_BUYER, "order-3", PRODUCT, 5);

		mvc.perform(get("/api/reviews/products/{id}", PRODUCT).param("size", "2"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(2))
				.andExpect(jsonPath("$.totalElements").value(3))
				.andExpect(jsonPath("$.totalPages").value(2))
				.andExpect(jsonPath("$.items[0].moderationReason").doesNotExist())
				.andExpect(jsonPath("$.items[0].buyerId").doesNotExist())
				.andExpect(jsonPath("$.items[0].orderId").doesNotExist())
				.andExpect(jsonPath("$.items[0].variantId").doesNotExist())
				.andExpect(jsonPath("$.items[0].productId").value(PRODUCT));

		mvc.perform(get("/api/reviews/products/{id}", PRODUCT).param("rating", "5"))
				.andExpect(jsonPath("$.totalElements").value(2));

		mvc.perform(get("/api/reviews/products/{id}", PRODUCT).param("size", "500"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void myReviewsCanBeFilteredByOrder() throws Exception {
		givenOrder("order-1", "DELIVERED", PRODUCT, "product-2");
		createReview(BUYER, "order-1", PRODUCT, 5);
		createReview(BUYER, "order-9", "product-9", 2);
		createReview(OTHER_BUYER, "order-5", PRODUCT, 4);

		mvc.perform(get("/api/reviews/me").param("orderId", "order-1").header("Authorization", token(BUYER, "BUYER")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(1))
				.andExpect(jsonPath("$.items[0].productId").value(PRODUCT))
				.andExpect(jsonPath("$.items[0].buyerId").value(BUYER));

		mvc.perform(get("/api/reviews/me").header("Authorization", token(BUYER, "BUYER")))
				.andExpect(jsonPath("$.totalElements").value(2));

		mvc.perform(get("/api/reviews/me").param("size", "1").header("Authorization", token(BUYER, "BUYER")))
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.totalPages").value(2));

		mvc.perform(get("/api/reviews/me").param("size", "51").header("Authorization", token(BUYER, "BUYER")))
				.andExpect(status().isBadRequest());
	}

	@Test
	void outboxPublishesLatestAggregateAndRetriesOnFailure() throws Exception {
		String first = createReview(BUYER, "order-1", PRODUCT, 5);
		createReview(BUYER, "order-2", PRODUCT, 2);
		var outbox = new ReviewOutboxPublisher(jdbc, publisher, ratingService, clock, transactionManager);
		var tx = new TransactionTemplate(transactionManager);

		// Broker lỗi: dòng được giữ lại, hẹn giờ thử lại.
		doThrow(new IllegalStateException("broker down")).when(publisher).publish(anyString(), any());
		assertThat(tx.<Boolean>execute(s -> outbox.publishOne())).isTrue();
		assertThat(jdbc.queryForObject("select max(retry_attempts) from review_outbox", Integer.class)).isEqualTo(1);
		assertThat(jdbc.queryForObject("select count(*) from review_outbox", Long.class)).isEqualTo(2);

		// Broker hồi phục: dòng còn lại đến hạn được phát với aggregate MỚI NHẤT (cả 2 đánh giá).
		org.mockito.Mockito.reset(publisher);
		assertThat(tx.<Boolean>execute(s -> outbox.publishOne())).isTrue();
		ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
		verify(publisher).publish(eq(EventType.REVIEW_CREATED), payload.capture());
		ReviewCreated event = (ReviewCreated) payload.getValue();
		assertThat(event.productId()).isEqualTo(PRODUCT);
		assertThat(event.ratingAverage()).isEqualTo(3.5);
		assertThat(event.ratingCount()).isEqualTo(2);
		// Lần publish lỗi đã tiêu revision 1; revision không bao giờ dùng lại.
		assertThat(event.revision()).isEqualTo(2);
		assertThat(jdbc.queryForObject("select count(*) from review_outbox", Long.class)).isEqualTo(1);

		// Dòng thất bại chưa đến hạn thử lại -> không phát.
		org.mockito.Mockito.reset(publisher);
		assertThat(tx.<Boolean>execute(s -> outbox.publishOne())).isFalse();
		verify(publisher, never()).publish(anyString(), any());
		assertThat(first).isNotBlank();
	}

	@Test
	void dependencyOutageIsReportedAs503() throws Exception {
		when(clients.myOrder(eq("order-1"), anyString()))
				.thenThrow(new ApiException("DEPENDENCY_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE, "down"));

		mvc.perform(post("/api/reviews")
				.header("Authorization", token(BUYER, "BUYER"))
				.contentType(MediaType.APPLICATION_JSON)
				.content(createBody("order-1", PRODUCT, 5, List.of())))
				.andExpect(status().isServiceUnavailable());
	}

	@Test
	void imagesMustComeFromConfiguredStorageOrigin() throws Exception {
		givenOrder("order-1", "DELIVERED", PRODUCT);
		for (String url : List.of("https://tracker.example.com/pixel.gif", "http://cdn.vmarket.vn/r/1.jpg",
				"https://cdn.vmarket.vn.evil.com/r/1.jpg", "https://user@cdn.vmarket.vn/r/1.jpg",
				"https://cdn.vmarket.vn/")) {
			mvc.perform(post("/api/reviews")
					.header("Authorization", token(BUYER, "BUYER"))
					.contentType(MediaType.APPLICATION_JSON)
					.content(createBody("order-1", PRODUCT, 5, List.of(url))))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.error.code").value("IMAGE_URL_NOT_ALLOWED"));
		}
		assertThat(jdbc.queryForObject("select count(*) from reviews", Long.class)).isZero();

		mvc.perform(post("/api/reviews")
				.header("Authorization", token(BUYER, "BUYER"))
				.contentType(MediaType.APPLICATION_JSON)
				.content(createBody("order-1", PRODUCT, 5, List.of("https://CDN.vmarket.vn:443/r/1.jpg"))))
				.andExpect(status().isCreated());
	}

	@Test
	void anotherBuyerGets404EvenWhenOrderWasReviewed() throws Exception {
		createReview(BUYER, "order-1", PRODUCT, 5);
		when(clients.myOrder(eq("order-1"), anyString()))
				.thenThrow(ApiException.notFound("ORDER_NOT_FOUND", "Không tìm thấy đơn hàng của bạn"));

		mvc.perform(post("/api/reviews")
				.header("Authorization", token(OTHER_BUYER, "BUYER"))
				.contentType(MediaType.APPLICATION_JSON)
				.content(createBody("order-1", PRODUCT, 1, List.of())))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));
	}

	@Test
	void reviewIdsAreUlids() throws Exception {
		String id = createReview(BUYER, "order-1", PRODUCT, 5);
		assertThat(id).matches("[0-9A-HJKMNP-TV-Z]{26}");
		assertThat(jdbc.queryForObject("select id from review_outbox", String.class)).hasSize(26);
	}

	/** F-01: đồng hồ replica sau CHẬM hơn replica trước, revision vẫn tăng nên Product không bỏ qua. */
	@Test
	void revisionIncreasesEvenWhenReplicaClocksGoBackwards() throws Exception {
		createReview(BUYER, "order-1", PRODUCT, 5);
		createReview(BUYER, "order-2", PRODUCT, 3);
		var fastClock = Clock.offset(clock, java.time.Duration.ofHours(2));
		var slowClock = Clock.offset(clock, java.time.Duration.ofHours(1));
		var replicaA = new ReviewOutboxPublisher(jdbc, publisher, ratingService, fastClock, transactionManager);
		var replicaB = new ReviewOutboxPublisher(jdbc, publisher, ratingService, slowClock, transactionManager);
		var tx = new TransactionTemplate(transactionManager);

		tx.execute(s -> replicaA.publishOne());
		tx.execute(s -> replicaB.publishOne());

		ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
		verify(publisher, org.mockito.Mockito.times(2)).publish(eq(EventType.REVIEW_CREATED), payload.capture());
		ReviewCreated first = (ReviewCreated) payload.getAllValues().get(0);
		ReviewCreated second = (ReviewCreated) payload.getAllValues().get(1);
		assertThat(second.revision()).isGreaterThan(first.revision());
		assertThat(second.ratingCount()).isEqualTo(2);
		// Sản phẩm khác có dãy revision riêng.
		createReview(BUYER, "order-3", "product-2", 4);
		tx.execute(s -> replicaB.publishOne());
		verify(publisher, org.mockito.Mockito.times(3)).publish(eq(EventType.REVIEW_CREATED), payload.capture());
		assertThat(((ReviewCreated) payload.getValue()).revision()).isEqualTo(1);
	}
}
