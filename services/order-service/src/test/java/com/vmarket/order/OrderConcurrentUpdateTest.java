package com.vmarket.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import com.vmarket.order.client.CartServiceClient;
import com.vmarket.order.client.UserServiceClient;
import com.vmarket.order.entity.Order;
import com.vmarket.order.entity.OrderStatus;
import com.vmarket.order.repository.OrderRepository;

/**
 * Kiểm tra riêng cơ chế {@code @Version} trên Order (PBL6-53): hai transaction cố ý đọc đơn
 * KHÔNG khoá dòng thì transaction ghi sau bị từ chối thay vì âm thầm ghi đè.
 *
 * <p>Các luồng nghiệp vụ hiện tại đều khoá dòng trước khi ghi nên không đi vào nhánh này; hành vi
 * tuần tự hoá của chúng nằm ở {@link OrderConcurrentFlowTest}. Test này bảo vệ lớp phòng thủ cho
 * các đường ghi không khoá về sau.
 */
@SpringBootTest
class OrderConcurrentUpdateTest {

	private static final String ALICE = "01JBQ9YDX7K3M8N5P2R4T6V8W0";
	private static final int TIMEOUT_SECONDS = 15;

	@Autowired OrderRepository orderRepository;
	@Autowired TransactionTemplate transactionTemplate;

	@MockitoBean UserServiceClient userServiceClient;
	@MockitoBean CartServiceClient cartServiceClient;

	private ExecutorService pool;

	@BeforeEach
	void setUp() {
		orderRepository.deleteAll();
		pool = Executors.newFixedThreadPool(2);
	}

	@AfterEach
	void tearDown() {
		pool.shutdownNow();
	}

	private Order createSampleOrder() {
		Order order = new Order();
		order.setUserId(ALICE);
		order.setStatus(OrderStatus.PENDING);
		order.setRecipientName("Nguyễn Văn An");
		order.setPhone("0912345678");
		order.setProvince("Đà Nẵng");
		order.setDistrict("Hải Châu");
		order.setWard("Thạch Thang");
		order.setStreetAddress("54 Nguyễn Lương Bằng");
		order.setTotalAmount(new BigDecimal("1000.00"));
		return orderRepository.saveAndFlush(order);
	}

	@Test
	void concurrentUpdate_secondTransactionFailsWithOptimisticLockException_andDoesNotOverwriteFirst() throws Exception {
		Order initial = createSampleOrder();
		String orderId = initial.getId();
		assertThat(initial.getVersion()).isEqualTo(0L);

		CountDownLatch thread1ReadOrder = new CountDownLatch(1);
		CountDownLatch thread2Committed = new CountDownLatch(1);

		// Luồng 1: Mở transaction, nạp Order (thấy version = 0), rồi dừng chờ Luồng 2 hoàn tất
		Future<?> thread1Future = pool.submit(() -> transactionTemplate.execute(status -> {
			Order orderInTx1 = orderRepository.findById(orderId).orElseThrow();
			assertThat(orderInTx1.getVersion()).isEqualTo(0L);
			thread1ReadOrder.countDown();

			try {
				if (!thread2Committed.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
					throw new IllegalStateException("Luồng 2 chưa commit xong trong thời gian chờ");
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException(e);
			}

			// Cố gắng cập nhật trạng thái đơn dựa trên version cũ (0)
			orderInTx1.setStatus(OrderStatus.CANCELLED);
			orderRepository.saveAndFlush(orderInTx1);
			return null;
		}));

		// Đảm bảo Luồng 1 đã đọc xong Order (version = 0)
		assertThat(thread1ReadOrder.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

		// Luồng 2 (Main thread): Cập nhật đơn hàng trong transaction riêng và COMMIT thành công (version -> 1)
		transactionTemplate.execute(status -> {
			Order orderInTx2 = orderRepository.findById(orderId).orElseThrow();
			orderInTx2.setStatus(OrderStatus.PROCESSING);
			orderInTx2.setNote("Đang xử lý bởi người bán");
			return orderRepository.saveAndFlush(orderInTx2);
		});

		// Báo cho Luồng 1 tiếp tục lưu
		thread2Committed.countDown();

		// Luồng 1 khi flush/commit sẽ phát hiện version trong DB đã là 1, ném ObjectOptimisticLockingFailureException
		assertThatThrownBy(thread1Future::get)
				.hasCauseInstanceOf(ObjectOptimisticLockingFailureException.class);

		// Dữ liệu trong CSDL giữ nguyên thay đổi của Luồng 2 (chiến thắng), không bị Luồng 1 ghi đè (Lost Update)
		Order finalOrder = orderRepository.findById(orderId).orElseThrow();
		assertThat(finalOrder.getStatus()).isEqualTo(OrderStatus.PROCESSING);
		assertThat(finalOrder.getNote()).isEqualTo("Đang xử lý bởi người bán");
		assertThat(finalOrder.getVersion()).isEqualTo(1L);
	}
}

