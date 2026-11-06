package com.vmarket.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.vmarket.events.EventType;
import com.vmarket.events.ReviewCreated;
import com.vmarket.review.dto.ReviewRequests;
import com.vmarket.review.exception.ApiException;
import com.vmarket.review.service.ModerationService;
import com.vmarket.review.service.RatingService;
import com.vmarket.review.service.ReviewOutboxPublisher;
import com.vmarket.review.service.ReviewService;

/**
 * Các đường race chạy trên PostgreSQL thật (H2 không mô phỏng đúng {@code SKIP LOCKED}, khoá dòng
 * và vi phạm UNIQUE đồng thời). Tự bỏ qua khi máy không có Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
class ReviewConcurrencyPostgresTest extends ReviewTestSupport {

	@Container
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

	@DynamicPropertySource
	static void database(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
		registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
		registry.add("spring.datasource.hikari.maximum-pool-size", () -> "20");
	}

	@Autowired
	ReviewService reviewService;
	@Autowired
	ModerationService moderationService;
	@Autowired
	RatingService ratingService;
	@Autowired
	Clock clock;
	@Autowired
	PlatformTransactionManager transactionManager;

	private ExecutorService pool;

	@BeforeEach
	void startPool() {
		pool = Executors.newFixedThreadPool(8);
		jdbc.update("delete from product_rating_locks");
	}

	@AfterEach
	void stopPool() {
		pool.shutdownNow();
	}

	@Test
	void concurrentReviewsOfSameOrderProductYieldExactlyOne() throws Exception {
		givenOrder("order-1", "DELIVERED", PRODUCT);
		var request = new ReviewRequests.CreateReview("order-1", PRODUCT, 5, null, List.of());
		CountDownLatch start = new CountDownLatch(1);

		List<Future<HttpStatus>> results = new ArrayList<>();
		for (int i = 0; i < 8; i++) {
			results.add(pool.submit(() -> {
				start.await();
				try {
					reviewService.create(BUYER, "Bearer x", request);
					return HttpStatus.CREATED;
				} catch (ApiException ex) {
					return ex.getStatus();
				}
			}));
		}
		start.countDown();

		List<HttpStatus> statuses = collect(results);
		assertThat(statuses).containsOnly(HttpStatus.CREATED, HttpStatus.CONFLICT);
		assertThat(Collections.frequency(statuses, HttpStatus.CREATED)).isEqualTo(1);
		assertThat(jdbc.queryForObject("select count(*) from reviews", Long.class)).isEqualTo(1);
		assertThat(jdbc.queryForObject("select count(*) from review_outbox", Long.class)).isEqualTo(1);
	}

	@Test
	void concurrentHideSucceedsOnceAndEnqueuesOneEvent() throws Exception {
		String reviewId = createReview(BUYER, "order-1", PRODUCT, 1);
		jdbc.update("delete from review_outbox");
		CountDownLatch start = new CountDownLatch(1);

		List<Future<Boolean>> results = new ArrayList<>();
		for (int i = 0; i < 4; i++) {
			String admin = "admin-" + i;
			results.add(pool.submit(() -> {
				start.await();
				try {
					moderationService.hide(admin, reviewId, "Vi phạm");
					return true;
				} catch (ApiException | org.springframework.dao.ConcurrencyFailureException ex) {
					return false;
				}
			}));
		}
		start.countDown();

		assertThat(Collections.frequency(collect(results), true)).isEqualTo(1);
		assertThat(jdbc.queryForObject("select status from reviews", String.class)).isEqualTo("HIDDEN");
		assertThat(jdbc.queryForObject("select count(*) from review_outbox", Long.class)).isEqualTo(1);
	}

	/**
	 * Replica A đang publish (chờ broker) cho sản phẩm X. Trong lúc đó có thêm đánh giá mới; replica B
	 * claim một dòng KHÁC của X (skip locked) nhưng phải đợi khoá sản phẩm, nên chỉ tính aggregate sau
	 * khi A commit — sự kiện publish sau cùng mang đủ 3 đánh giá, không bị A ghi đè bằng số cũ.
	 */
	@Test
	void publishersOfSameProductAreSerializedSoLastEventIsLatest() throws Exception {
		createReview(BUYER, "order-1", PRODUCT, 5);
		createReview(BUYER, "order-2", PRODUCT, 4);

		CountDownLatch aPublishing = new CountDownLatch(1);
		CountDownLatch releaseA = new CountDownLatch(1);
		List<ReviewCreated> published = Collections.synchronizedList(new ArrayList<>());
		doAnswer(inv -> {
			ReviewCreated event = inv.getArgument(1);
			if ("replica-A".equals(Thread.currentThread().getName())) {
				aPublishing.countDown();
				assertThat(releaseA.await(20, TimeUnit.SECONDS)).isTrue();
			}
			published.add(event);
			return null;
		}).when(publisher).publish(eq(EventType.REVIEW_CREATED), any());

		var outbox = new ReviewOutboxPublisher(jdbc, publisher, ratingService, clock, transactionManager);
		var tx = new TransactionTemplate(transactionManager);
		Future<?> a = pool.submit(named("replica-A", () -> tx.execute(s -> outbox.publishOne())));
		assertThat(aPublishing.await(20, TimeUnit.SECONDS)).isTrue();

		// Đánh giá thứ 3 commit trong lúc A còn giữ khoá.
		createReview(OTHER_BUYER, "order-3", PRODUCT, 3);

		Future<?> b = pool.submit(named("replica-B", () -> tx.execute(s -> outbox.publishOne())));
		Thread.sleep(500);
		assertThat(b.isDone()).as("B phải đợi khoá sản phẩm của A").isFalse();

		releaseA.countDown();
		a.get(20, TimeUnit.SECONDS);
		b.get(20, TimeUnit.SECONDS);

		assertThat(published).hasSize(2);
		assertThat(published.get(0).ratingCount()).isEqualTo(2);
		ReviewCreated last = published.get(1);
		assertThat(last.ratingCount()).isEqualTo(3);
		assertThat(last.ratingAverage()).isEqualTo(4.0);
		// Hai replica claim hai dòng khác nhau; còn đúng một dòng (của đánh giá thứ 3).
		assertThat(jdbc.queryForObject("select count(*) from review_outbox", Long.class)).isEqualTo(1);
	}

	@Test
	void skipLockedLetsReplicasDrainDifferentProductsInParallel() throws Exception {
		createReview(BUYER, "order-1", PRODUCT, 5);
		createReview(BUYER, "order-2", "product-2", 3);
		doAnswer(inv -> null).when(publisher).publish(anyString(), any());

		var outbox = new ReviewOutboxPublisher(jdbc, publisher, ratingService, clock, transactionManager);
		var tx = new TransactionTemplate(transactionManager);
		List<Future<Boolean>> results = new ArrayList<>();
		for (int i = 0; i < 4; i++) {
			results.add(pool.submit(() -> Boolean.TRUE.equals(tx.execute(s -> outbox.publishOne()))));
		}
		List<Boolean> claimed = collect(results);

		assertThat(Collections.frequency(claimed, true)).isEqualTo(2);
		assertThat(jdbc.queryForObject("select count(*) from review_outbox", Long.class)).isZero();
	}

	private static <T> Callable<T> named(String name, Callable<T> body) {
		return () -> {
			String previous = Thread.currentThread().getName();
			Thread.currentThread().setName(name);
			try {
				return body.call();
			} finally {
				Thread.currentThread().setName(previous);
			}
		};
	}

	private static <T> List<T> collect(List<Future<T>> futures) throws Exception {
		List<T> values = new ArrayList<>();
		for (Future<T> f : futures) {
			values.add(f.get(30, TimeUnit.SECONDS));
		}
		return values;
	}
}
