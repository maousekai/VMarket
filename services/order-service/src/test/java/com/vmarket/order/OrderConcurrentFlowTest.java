package com.vmarket.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import com.vmarket.events.DeliveryStatusChanged;
import com.vmarket.order.delivery.OrderDeliveryBridge;
import com.vmarket.order.entity.Order;
import com.vmarket.order.entity.OrderStatus;
import com.vmarket.order.exception.ApiException;
import com.vmarket.order.repository.OrderRepository;
import com.vmarket.order.service.OrderService;

/**
 * Người mua huỷ đơn đồng thời với delivery cập nhật trạng thái, chạy qua luồng nghiệp vụ thật
 * (PBL6-53).
 *
 * <p>Thứ tự được ép cố định: một luồng giữ transaction đang mở (đã khoá dòng), luồng kia phải chờ
 * tới khi luồng đầu commit. Nếu một trong hai luồng bỏ khoá dòng, luồng tới sau sẽ đọc trạng thái
 * cũ và không còn bị chặn, khiến test thất bại.
 */
@SpringBootTest(properties = "spring.datasource.url="
		+ "jdbc:h2:mem:order-concurrent-flow;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=15000")
class OrderConcurrentFlowTest {

	private static final String BUYER = "01JBQ9YDX7K3M8N5P2R4T6V8W0";
	private static final long BLOCKED_WINDOW_MS = 500;
	private static final int TIMEOUT_SECONDS = 15;

	@Autowired OrderRepository orders;
	@Autowired OrderService orderService;
	@Autowired OrderDeliveryBridge bridge;
	@Autowired TransactionTemplate transactionTemplate;
	@Autowired JdbcTemplate jdbc;

	private ExecutorService pool;
	private String orderId;

	@BeforeEach
	void setUp() {
		jdbc.execute("alter table orders add column if not exists delivery_version bigint not null default 0");
		jdbc.update("delete from event_outbox");
		pool =Executors.newSingleThreadExecutor();

		Order order = new Order();
		order.setUserId(BUYER);
		order.setStatus(OrderStatus.PENDING);
		order.setTotalAmount(new BigDecimal("100000"));
		order.setRecipientName("Buyer");
		order.setPhone("0901234567");
		order.setProvince("Đà Nẵng");
		order.setDistrict("Hải Châu");
		order.setWard("Phường 1");
		order.setStreetAddress("1 đường giao");
		orderId = orders.saveAndFlush(order).getId();
	}

	@AfterEach
	void tearDown() {
		pool.shutdownNow();
	}

	@Test
	void deliveryCommitsFirst_buyerCancelWaitsThenIsRejected() throws Exception {
		Future<?> cancel = runWhileHoldingRowLock(
				() -> bridge.apply(new DeliveryStatusChanged(orderId, "PICKED_UP", 1)),
				() -> orderService.cancelMyOrder(BUYER, orderId));

		Throwable failure = catchThrowable(() -> cancel.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
		assertThat(failure).isInstanceOf(ExecutionException.class)
				.hasCauseInstanceOf(ApiException.class);
		assertThat(((ApiException) failure.getCause()).getCode()).isEqualTo("ORDER_NOT_CANCELLABLE");

		assertFinalState(OrderStatus.SHIPPED);
		assertThat(outboxPayloads()).allSatisfy(p -> assertThat(p).contains("SHIPPED").doesNotContain("CANCELLED"));
	}

	@Test
	void buyerCancelCommitsFirst_deliveryWaitsThenChangesNothing() throws Exception {
		Future<?> delivery = runWhileHoldingRowLock(
				() -> orderService.cancelMyOrder(BUYER, orderId),
				() -> {
					bridge.apply(new DeliveryStatusChanged(orderId, "PICKED_UP", 1));
					return null;
				});

		delivery.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

		assertFinalState(OrderStatus.CANCELLED);
		assertThat(outboxPayloads()).allSatisfy(p -> assertThat(p).contains("CANCELLED").doesNotContain("SHIPPED"));
	}

	/** Chạy {@code contender} trong lúc transaction của {@code lockHolder} chưa commit. */
	private <T> Future<T> runWhileHoldingRowLock(Runnable lockHolder, Callable<T> contender) {
		return transactionTemplate.execute(status -> {
			lockHolder.run();
			Future<T> future = pool.submit(contender);
			assertThatThrownBy(() -> future.get(BLOCKED_WINDOW_MS, TimeUnit.MILLISECONDS))
					.as("luồng tới sau phải chờ khoá dòng")
					.isInstanceOf(TimeoutException.class);
			return future;
		});
	}

	/** Chỉ một thao tác được ghi: đúng một event và version chỉ tăng một lần. */
	private void assertFinalState(OrderStatus expected) {
		Order order = orders.findById(orderId).orElseThrow();
		assertThat(order.getStatus()).isEqualTo(expected);
		assertThat(order.getVersion()).isEqualTo(1L);
		assertThat(outboxPayloads()).hasSize(1);
	}

	private List<String> outboxPayloads() {
		return jdbc.queryForList("select payload from event_outbox", String.class);
	}
}
