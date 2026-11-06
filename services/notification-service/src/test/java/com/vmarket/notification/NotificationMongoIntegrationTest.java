package com.vmarket.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.vmarket.events.EventEnvelope;
import com.vmarket.notification.client.InternalClients;
import com.vmarket.notification.client.UserContact;
import com.vmarket.notification.domain.DeviceToken;
import com.vmarket.notification.domain.EmailLog;
import com.vmarket.notification.domain.Notification;
import com.vmarket.notification.domain.NotificationType;
import com.vmarket.notification.email.EmailDeliveryException;
import com.vmarket.notification.email.EmailSender;
import com.vmarket.notification.exception.ApiException;
import com.vmarket.notification.repository.NotificationRepository;
import com.vmarket.notification.service.NotificationCenterService;
import com.vmarket.notification.service.NotificationDispatcher;
import com.vmarket.notification.service.NotificationMessage;

/**
 * Chạy với MongoDB thật (Testcontainers) để chứng minh unique index và cơ chế claim email
 * thực sự chống trùng — mock {@code DuplicateKeyException} không chứng minh được điều đó.
 * Bỏ qua khi máy không có Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = "spring.data.mongodb.auto-index-creation=true")
class NotificationMongoIntegrationTest {

	private static final String ULID = "[0-9A-HJKMNP-TV-Z]{26}";

	@Container
	static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse("mongo:7.0"));

	@DynamicPropertySource
	static void mongo(DynamicPropertyRegistry registry) {
		registry.add("spring.mongodb.uri", () -> MONGO.getReplicaSetUrl("vmarket_notification_it"));
	}

	@Autowired
	private NotificationDispatcher dispatcher;
	@Autowired
	private NotificationCenterService center;
	@Autowired
	private NotificationRepository notifications;
	@Autowired
	private MongoTemplate mongo;

	@MockitoBean
	private InternalClients clients;
	@MockitoBean
	private EmailSender emailSender;

	private final NotificationMessage withEmail = new NotificationMessage(NotificationType.PAYMENT_SUCCEEDED,
			"Thanh toán thành công", "Đơn o1", "/orders/o1", Map.of("orderId", "o1"), true,
			new NotificationMessage.Email("Thanh toán thành công", "<p>x</p>", "x"));

	@BeforeEach
	void clean() {
		mongo.remove(new Query(), Notification.class);
		mongo.remove(new Query(), EmailLog.class);
		mongo.remove(new Query(), DeviceToken.class);
		reset(clients, emailSender);
		when(emailSender.provider()).thenReturn("test");
		when(clients.contact("buyer-1"))
				.thenReturn(Optional.of(new UserContact("buyer-1", "buyer@example.com", true, false)));
	}

	private static EventEnvelope envelope(String eventId) {
		return new EventEnvelope(eventId, "PaymentSucceeded", 1L, Map.of());
	}

	@Test
	void uniqueIndexesAreCreatedAndEnforced() {
		assertThat(mongo.indexOps(Notification.class).getIndexInfo())
				.anySatisfy(index -> {
					assertThat(index.getName()).isEqualTo("dedupKey");
					assertThat(index.isUnique()).isTrue();
				});
		assertThat(mongo.indexOps(EmailLog.class).getIndexInfo())
				.anySatisfy(index -> {
					assertThat(index.getName()).isEqualTo("dedupKey");
					assertThat(index.isUnique()).isTrue();
				});

		notifications.insert(Notification.builder().id("01JD0000000000000000000001").dedupKey("k").userId("u").build());
		assertThatThrownBy(() -> notifications
				.insert(Notification.builder().id("01JD0000000000000000000002").dedupKey("k").userId("u").build()))
				.isInstanceOf(DuplicateKeyException.class);
	}

	@Test
	void redeliveredEventCreatesOneNotificationAndSendsOneEmail() {
		dispatcher.deliver("buyer-1", withEmail, envelope("evt-1"));
		dispatcher.deliver("buyer-1", withEmail, envelope("evt-1"));

		assertThat(mongo.count(new Query(), Notification.class)).isEqualTo(1);
		assertThat(mongo.findOne(new Query(), Notification.class).getId()).matches(ULID);
		verify(emailSender, times(1)).send(any());
		EmailLog log = mongo.findOne(Query.query(Criteria.where("dedupKey").is("evt-1:buyer-1:PAYMENT_SUCCEEDED")),
				EmailLog.class);
		assertThat(log.getStatus()).isEqualTo(EmailLog.Status.SENT);
		assertThat(log.getRecipient()).isEqualTo("buyer@example.com");
		assertThat(log.getSentAt()).isNotNull();
		assertThat(log.getId()).matches(ULID);
	}

	@Test
	void concurrentDeliveriesSendEmailOnlyOnce() throws Exception {
		CountDownLatch inProvider = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		doAnswer(invocation -> {
			inProvider.countDown();
			release.await(5, TimeUnit.SECONDS);
			return null;
		}).when(emailSender).send(any());

		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			Future<?> first = pool.submit(() -> dispatcher.deliver("buyer-1", withEmail, envelope("evt-2")));
			assertThat(inProvider.await(5, TimeUnit.SECONDS)).isTrue();
			// Lần hai tới khi lần một đang gọi provider (đã claim SENDING) -> không gửi.
			pool.submit(() -> dispatcher.deliver("buyer-1", withEmail, envelope("evt-2"))).get(5, TimeUnit.SECONDS);
			release.countDown();
			first.get(5, TimeUnit.SECONDS);
		} finally {
			pool.shutdownNow();
		}

		verify(emailSender, times(1)).send(any());
		assertThat(mongo.count(new Query(), Notification.class)).isEqualTo(1);
		assertThat(mongo.findOne(new Query(), EmailLog.class).getStatus()).isEqualTo(EmailLog.Status.SENT);
	}

	@Test
	void staleSendingClaimIsTakenOverAfterCrash() {
		Instant longAgo = Instant.now().minusSeconds(600);
		mongo.insert(EmailLog.builder().id("01JD0000000000000000000009").dedupKey("evt-7:buyer-1:PAYMENT_SUCCEEDED")
				.userId("buyer-1").status(EmailLog.Status.SENDING).attempts(1).createdAt(longAgo).updatedAt(longAgo)
				.build());

		dispatcher.deliver("buyer-1", withEmail, envelope("evt-7"));

		verify(emailSender, times(1)).send(any());
		EmailLog log = mongo.findOne(new Query(), EmailLog.class);
		assertThat(log.getStatus()).isEqualTo(EmailLog.Status.SENT);
		assertThat(log.getAttempts()).isEqualTo(2);
	}

	@Test
	void failedEmailIsRetriedOnRedeliveryWithoutDuplicatingInApp() {
		doThrow(new EmailDeliveryException("Brevo trả lỗi 500")).doNothing().when(emailSender).send(any());

		assertThatThrownBy(() -> dispatcher.deliver("buyer-1", withEmail, envelope("evt-3")))
				.isInstanceOf(EmailDeliveryException.class);
		EmailLog failed = mongo.findOne(new Query(), EmailLog.class);
		assertThat(failed.getStatus()).isEqualTo(EmailLog.Status.FAILED);
		assertThat(failed.getError()).isEqualTo("Brevo trả lỗi 500");

		dispatcher.deliver("buyer-1", withEmail, envelope("evt-3"));

		EmailLog sent = mongo.findOne(new Query(), EmailLog.class);
		assertThat(sent.getStatus()).isEqualTo(EmailLog.Status.SENT);
		assertThat(sent.getAttempts()).isEqualTo(2);
		assertThat(sent.getError()).isNull();
		assertThat(mongo.count(new Query(), Notification.class)).isEqualTo(1);
		verify(emailSender, times(2)).send(any());
	}

	@Test
	void unverifiedRecipientIsSkippedOnceAndNotRetried() {
		when(clients.contact("buyer-1"))
				.thenReturn(Optional.of(new UserContact("buyer-1", "buyer@example.com", false, false)));

		dispatcher.deliver("buyer-1", withEmail, envelope("evt-4"));
		dispatcher.deliver("buyer-1", withEmail, envelope("evt-4"));

		EmailLog skipped = mongo.findOne(new Query(), EmailLog.class);
		assertThat(skipped.getStatus()).isEqualTo(EmailLog.Status.SKIPPED);
		assertThat(skipped.getError()).isEqualTo("EMAIL_NOT_VERIFIED");
		verify(emailSender, never()).send(any());
		verify(clients, times(1)).contact("buyer-1");
	}

	@Test
	void sellerOwningTwoShopsInSameOrderGetsBothNotifications() {
		dispatcher.deliverAll(List.of(
				new NotificationDispatcher.Delivery("seller-1", new NotificationMessage(NotificationType.NEW_ORDER, "t",
						"b", null, Map.of("orderId", "o1", "shopId", "shop-a"), false, null)),
				new NotificationDispatcher.Delivery("seller-1", new NotificationMessage(NotificationType.NEW_ORDER, "t",
						"b", null, Map.of("orderId", "o1", "shopId", "shop-b"), false, null))), envelope("evt-5"));

		assertThat(mongo.count(Query.query(Criteria.where("userId").is("seller-1")), Notification.class)).isEqualTo(2);
	}

	@Test
	void notificationCenterIsScopedToOwner() {
		dispatcher.deliver("buyer-1", withEmail, envelope("evt-6"));
		String id = mongo.findOne(new Query(), Notification.class).getId();

		assertThatThrownBy(() -> center.markRead("someone-else", id)).isInstanceOfSatisfying(ApiException.class,
				ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
		assertThat(center.unreadCount("buyer-1")).isEqualTo(1);

		Instant firstRead = center.markRead("buyer-1", id).readAt();
		assertThat(center.markRead("buyer-1", id).readAt()).isEqualTo(firstRead);
		assertThat(center.unreadCount("buyer-1")).isZero();
		assertThat(center.list("buyer-1", 0, 20, false).items()).hasSize(1);
		assertThat(center.list("someone-else", 0, 20, false).items()).isEmpty();
	}

	@Test
	void deviceTokenMovesToNewOwnerWithUlidId() {
		center.registerDevice("user-a", "tok-1", DeviceToken.Platform.ANDROID);
		center.registerDevice("user-b", "tok-1", DeviceToken.Platform.ANDROID);

		List<DeviceToken> tokens = mongo.findAll(DeviceToken.class);
		assertThat(tokens).hasSize(1);
		assertThat(tokens.get(0).getUserId()).isEqualTo("user-b");
		assertThat(tokens.get(0).getId()).matches(ULID);

		center.unregisterDevice("user-a", "tok-1");
		assertThat(mongo.count(new Query(), DeviceToken.class)).isEqualTo(1);
		center.unregisterDevice("user-b", "tok-1");
		assertThat(mongo.count(new Query(), DeviceToken.class)).isZero();
	}
}
