package com.vmarket.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.vmarket.review.service.RatingService;
import com.vmarket.review.service.ReviewOutboxPublisher;

/** Dòng outbox DEAD được báo cáo và phát lại được qua API admin. */
class OutboxRecoveryTest extends ReviewTestSupport {

	private static final String REPLAY = "{\"reason\":\"Broker đã hồi phục\"}";

	@Autowired
	RatingService ratingService;
	@Autowired
	Clock clock;
	@Autowired
	PlatformTransactionManager transactionManager;

	@Test
	void failedPublishKeepsErrorMessageAndBecomesDeadAfterMaxAttempts() throws Exception {
		createReview(BUYER, "order-1", PRODUCT, 4);
		jdbc.update("update review_outbox set retry_attempts = ?", ReviewOutboxPublisher.MAX_ATTEMPTS - 1);
		doThrow(new IllegalStateException("channel closed by broker")).when(publisher).publish(anyString(), any());

		var outbox = new ReviewOutboxPublisher(jdbc, publisher, ratingService, clock, transactionManager);
		new TransactionTemplate(transactionManager).execute(s -> outbox.publishOne());

		assertThat(jdbc.queryForObject("select state from review_outbox", String.class)).isEqualTo("DEAD");
		assertThat(jdbc.queryForObject("select last_error from review_outbox", String.class))
				.isEqualTo("IllegalStateException: channel closed by broker");
	}

	@Test
	void adminSeesAndReplaysDeadEvents() throws Exception {
		insertDead("01J0000000000000000000DEA1");
		insertDead("01J0000000000000000000DEA2");

		mvc.perform(get("/api/reviews/admin/outbox/status").header("Authorization", token(ADMIN, "ADMIN")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.dead").value(2))
				.andExpect(jsonPath("$.pending").value(0));

		mvc.perform(get("/api/reviews/admin/outbox/dead").header("Authorization", token(ADMIN, "ADMIN")))
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].lastError").value("AmqpException: timeout"));

		mvc.perform(post("/api/reviews/admin/outbox/{id}/replay", "01J0000000000000000000DEA1")
				.header("Authorization", token(ADMIN, "ADMIN"))
				.contentType(MediaType.APPLICATION_JSON).content(REPLAY))
				.andExpect(status().isNoContent());
		assertThat(jdbc.queryForObject(
				"select state from review_outbox where id = '01J0000000000000000000DEA1'", String.class))
				.isEqualTo("PENDING");

		mvc.perform(post("/api/reviews/admin/outbox/replay-dead")
				.header("Authorization", token(ADMIN, "ADMIN"))
				.contentType(MediaType.APPLICATION_JSON).content(REPLAY))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.replayed").value(1));
		assertThat(jdbc.queryForObject("select count(*) from review_outbox where state = 'PENDING'"
				+ " and retry_attempts = 0", Long.class)).isEqualTo(2);

		mvc.perform(post("/api/reviews/admin/outbox/{id}/replay", "01J0000000000000000000DEA1")
				.header("Authorization", token(ADMIN, "ADMIN"))
				.contentType(MediaType.APPLICATION_JSON).content(REPLAY))
				.andExpect(status().isNotFound());
	}

	@Test
	void outboxAdminRequiresAdminAndReason() throws Exception {
		mvc.perform(get("/api/reviews/admin/outbox/status").header("Authorization", token(SELLER, "SELLER")))
				.andExpect(status().isForbidden());
		mvc.perform(post("/api/reviews/admin/outbox/replay-dead")
				.header("Authorization", token(ADMIN, "ADMIN"))
				.contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"\"}"))
				.andExpect(status().isBadRequest());
	}

	private void insertDead(String id) {
		Timestamp now = Timestamp.from(Instant.now());
		jdbc.update("insert into review_outbox (id, event_type, review_id, product_id, state, retry_attempts,"
				+ " next_attempt_at, last_error, created_at) values (?, 'ReviewCreated', 'r', ?, 'DEAD', 10, ?,"
				+ " 'AmqpException: timeout', ?)", id, PRODUCT, now, now);
	}
}
