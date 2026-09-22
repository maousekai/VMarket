package com.vmarket.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.vmarket.events.ProductDeleted;
import com.vmarket.events.EventEnvelope;
import com.vmarket.events.EventType;
import com.vmarket.events.EventsJson;
import com.vmarket.events.ReviewCreated;
import com.vmarket.product.dto.InventoryRequest;
import com.vmarket.product.event.CatalogOutbox;
import com.vmarket.product.event.OutboxDispatcher;
import com.vmarket.product.model.Product;
import com.vmarket.product.model.ProductStatus;
import com.vmarket.product.model.ProductVariant;
import com.vmarket.product.repository.InventoryReservationRepository;
import com.vmarket.product.repository.OutboxEventRepository;
import com.vmarket.product.repository.ProductRepository;
import com.vmarket.product.service.InventoryService;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
		"app.events.listen=true",
		"app.events.queue=product.events.v2.it",
		"app.events.bindings[0]=ReviewCreated",
		"app.outbox.enabled=true",
		"app.outbox.initial-delay-ms=3600000",
		"app.catalog-migration.enabled=false",
		"spring.data.mongodb.auto-index-creation=true",
		"spring.rabbitmq.publisher-confirm-type=correlated",
		"spring.rabbitmq.publisher-returns=true"
})
class ProductInfrastructureIntegrationTest {
	@Container
	static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse("mongo:7.0"));
	@Container
	static final RabbitMQContainer RABBIT = new RabbitMQContainer(DockerImageName.parse("rabbitmq:3.13-management"));

	@DynamicPropertySource
	static void infrastructure(DynamicPropertyRegistry registry) {
		registry.add("spring.mongodb.uri", () -> MONGO.getReplicaSetUrl("vmarket_product_it"));
		registry.add("spring.rabbitmq.host", RABBIT::getHost);
		registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
		registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
		registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
	}

	@Autowired ProductRepository productRepository;
	@Autowired InventoryReservationRepository reservationRepository;
	@Autowired OutboxEventRepository outboxRepository;
	@Autowired InventoryService inventoryService;
	@Autowired CatalogOutbox outbox;
	@Autowired OutboxDispatcher dispatcher;
	@Autowired TransactionTemplate transactions;
	@Autowired RabbitTemplate rabbitTemplate;
	@Autowired TopicExchange eventExchange;
	@Autowired EventsJson eventsJson;

	@BeforeEach
	void cleanData() {
		reservationRepository.deleteAll();
		outboxRepository.deleteAll();
		productRepository.deleteAll();
	}

	@Test
	void mongoTransactionRollsBackDomainAndOutboxTogether() {
		assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
			Product product = product();
			productRepository.save(product);
			outbox.enqueue("ProductDeleted", new ProductDeleted("p1", "shop-1"));
			throw new IllegalStateException("rollback");
		})).isInstanceOf(IllegalStateException.class);

		assertThat(productRepository.count()).isZero();
		assertThat(outboxRepository.count()).isZero();
	}

	@Test
	void concurrentReservationNeverDoubleReservesStock() throws Exception {
		productRepository.save(product());
		InventoryRequest request = new InventoryRequest("order-1",
				List.of(new InventoryRequest.InventoryItem("p1", "v1", 3)));
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			CompletableFuture<?> first = CompletableFuture.runAsync(() -> reserveIgnoringRaceLoser(request), executor);
			CompletableFuture<?> second = CompletableFuture.runAsync(() -> reserveIgnoringRaceLoser(request), executor);
			CompletableFuture.allOf(first, second).get(15, TimeUnit.SECONDS);
		} finally {
			executor.shutdownNow();
		}

		Product saved = productRepository.findById("p1").orElseThrow();
		assertThat(saved.getVariants().get(0).getReservedStock()).isEqualTo(3);
		assertThat(reservationRepository.count()).isEqualTo(1);
	}

	@Test
	void competingOrdersCannotOversellOrLeaveAnOrphanReservation() throws Exception {
		Product product = product();
		product.getVariants().get(0).setStock(5);
		productRepository.save(product);
		InventoryRequest firstRequest = inventoryRequest("order-1", 3);
		InventoryRequest secondRequest = inventoryRequest("order-2", 3);
		AtomicInteger successes = new AtomicInteger();
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			CompletableFuture<?> first = CompletableFuture.runAsync(
					() -> reserveAndCountSuccess(firstRequest, successes), executor);
			CompletableFuture<?> second = CompletableFuture.runAsync(
					() -> reserveAndCountSuccess(secondRequest, successes), executor);
			CompletableFuture.allOf(first, second).get(15, TimeUnit.SECONDS);
		} finally {
			executor.shutdownNow();
		}

		Product saved = productRepository.findById("p1").orElseThrow();
		assertThat(successes).hasValue(1);
		assertThat(saved.getVariants().get(0).getReservedStock()).isEqualTo(3);
		assertThat(reservationRepository.count()).isEqualTo(1);
	}

	@Test
	void outboxMarksPublishedOnlyAfterRabbitBrokerAckAndRouting() {
		RabbitAdmin admin = new RabbitAdmin(rabbitTemplate);
		Queue queue = new Queue("product.outbox-confirm.it", false, true, true);
		admin.declareQueue(queue);
		admin.declareBinding(BindingBuilder.bind(queue).to(eventExchange).with("ProductDeleted"));
		outbox.enqueue("ProductDeleted", new ProductDeleted("p1", "shop-1"));

		dispatcher.publishPending();

		assertThat(outboxRepository.findAll()).singleElement()
				.extracting(event -> event.getPublishedAt()).isNotNull();
	}

	@Test
	void outboxKeepsUnroutableEventPendingForRetry() {
		outbox.enqueue("UnroutableProductEvent", new ProductDeleted("p1", "shop-1"));

		dispatcher.publishPending();

		assertThat(outboxRepository.findAll()).singleElement().satisfies(event -> {
			assertThat(event.getPublishedAt()).isNull();
			assertThat(event.getAttempts()).isEqualTo(1);
			assertThat(event.getLastError()).contains("không được route");
		});
	}

	@Test
	void poisonEventIsRetriedAndDeadLetteredByRealListener() {
		EventEnvelope envelope = new EventEnvelope("poison-review", EventType.REVIEW_CREATED,
				Instant.now().toEpochMilli(), new ReviewCreated("review-1", "p1", 6, 1));
		MessageProperties properties = new MessageProperties();
		properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
		rabbitTemplate.send(eventExchange.getName(), EventType.REVIEW_CREATED,
				new Message(eventsJson.write(envelope), properties));

		Message deadLetter = rabbitTemplate.receive("product.events.v2.it.dead", Duration.ofSeconds(10).toMillis());

		assertThat(deadLetter).isNotNull();
		assertThat(deadLetter.getMessageProperties().getReceivedRoutingKey())
				.isEqualTo("product.events.v2.it.dead");
	}

	private void reserveIgnoringRaceLoser(InventoryRequest request) {
		try {
			inventoryService.reserve(request);
		} catch (RuntimeException expectedRaceLoser) {
			// The other transaction owns the unique orderId/version; final state is asserted below.
		}
	}

	private void reserveAndCountSuccess(InventoryRequest request, AtomicInteger successes) {
		try {
			inventoryService.reserve(request);
			successes.incrementAndGet();
		} catch (RuntimeException expectedRaceLoser) {
			// Either the optimistic write or the stock re-check rejects the losing order.
		}
	}

	private InventoryRequest inventoryRequest(String orderId, int quantity) {
		return new InventoryRequest(orderId,
				List.of(new InventoryRequest.InventoryItem("p1", "v1", quantity)));
	}

	private Product product() {
		Product product = new Product();
		product.setId("p1");
		product.setShopId("shop-1");
		product.setSellerId("seller-1");
		product.setName("Sản phẩm IT");
		product.setStatus(ProductStatus.ACTIVE);
		product.setCategoryVisible(true);
		product.setVariants(new ArrayList<>(List.of(new ProductVariant("v1", "SKU-IT", java.util.Map.of(),
				10L, 10, 0, 0))));
		return product;
	}
}
