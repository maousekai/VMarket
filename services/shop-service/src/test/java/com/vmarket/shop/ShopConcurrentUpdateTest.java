package com.vmarket.shop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;

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
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import com.vmarket.events.EventPublisher;
import com.vmarket.shop.dto.ShopRequest;
import com.vmarket.shop.entity.ShopStatus;
import com.vmarket.shop.exception.GlobalExceptionHandler;
import com.vmarket.shop.repository.ShopProfileChangeRepository;
import com.vmarket.shop.repository.ShopRepository;
import com.vmarket.shop.repository.ShopStatusHistoryRepository;
import com.vmarket.shop.service.ShopModerationService;
import com.vmarket.shop.service.ShopService;

/**
 * Hai thao tác ghi cùng một gian hàng chạy song song (ra soát PR #24, gợi ý 1) — cùng
 * tinh thần với {@code AccountSuspensionRaceTest} của auth-service.
 *
 * <p>Kịch bản thật: Admin mở hồ sơ rồi bấm "Duyệt" đúng lúc người bán bấm "Lưu". Không
 * có {@code @Version} thì một bên ghi đè bên kia trong im lặng — Admin duyệt một bản hồ
 * sơ khác bản mình vừa đọc, mà không ai biết.
 *
 * <p><b>Cách dựng cửa sổ tranh chấp:</b> hai transaction thật, mở bằng
 * {@link TransactionTemplate} trên hai luồng. Transaction của Admin nạp gian hàng
 * (chốt version đang thấy) rồi dừng lại; người bán lưu và commit xen vào; Admin mới gọi
 * {@code approve} — nó dùng lại đúng thực thể đã nạp trong transaction của mình
 * (persistence context), nên câu UPDATE đi ra vẫn mang version cũ và bị CSDL từ chối.
 * Đây đúng là khoảng hở trong production: giữa lúc transaction đọc và lúc nó ghi.
 *
 * <p>Cố ý <b>không</b> dùng Mockito spy để chặn giữa chừng: các bean nghiệp vụ ở đây đã
 * bị Spring bọc proxy transaction, spy lên chúng làm lời gọi lúc stub chạy thật và nổ
 * {@code No existing transaction found for transaction marked with propagation 'mandatory'}.
 */
@SpringBootTest
class ShopConcurrentUpdateTest {

	private static final String OWNER = "01JBQ9YDX7K3M8N5P2R4T6V8W0";
	private static final String ADMIN = "01JBQ9YDX7K3M8N5P2R4T6V8WA";
	private static final int TIMEOUT_SECONDS = 15;

	@Autowired ShopService shopService;
	@Autowired ShopModerationService moderationService;
	@Autowired ShopRepository shopRepository;
	@Autowired ShopStatusHistoryRepository historyRepository;
	@Autowired ShopProfileChangeRepository profileChangeRepository;
	@Autowired TransactionTemplate transactionTemplate;
	@Autowired GlobalExceptionHandler handler;

	@MockitoBean EventPublisher eventPublisher;

	private ExecutorService pool;

	@BeforeEach
	void setUp() {
		profileChangeRepository.deleteAll();
		historyRepository.deleteAll();
		shopRepository.deleteAll();
		pool = Executors.newSingleThreadExecutor();
	}

	@AfterEach
	void tearDown() {
		pool.shutdownNow();
	}

	private static ShopRequest request(String name) {
		return new ShopRequest(name, "Gốm thủ công", null, null, null, "lienhe@gomhoian.vn",
				"0912345678", "Quảng Nam", "Hội An", "Thanh Hà", "12 Phạm Phán");
	}

	@Test
	void adminDuyet_dungLucNguoiBanLuuHoSo_benToiSauThua_khongGhiDe_vaKhongPhatSuKien() throws Exception {
		String shopId = shopService.register(OWNER, request("Tiệm Gốm Hội An")).id();

		CountDownLatch adminDaDocHoSo = new CountDownLatch(1);
		CountDownLatch nguoiBanDaLuu = new CountDownLatch(1);

		Future<?> duyetCuaAdmin = pool.submit(() -> transactionTemplate.execute(status -> {
			// Admin đọc hồ sơ trong transaction của mình (thấy version hiện tại)...
			shopRepository.findById(shopId).orElseThrow();
			adminDaDocHoSo.countDown();
			try {
				if (!nguoiBanDaLuu.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
					throw new IllegalStateException("Người bán chưa lưu xong trong thời gian chờ");
				}
			} catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException(ex);
			}
			// ...rồi mới bấm duyệt, trên bản hồ sơ đã cũ.
			return moderationService.approve(ADMIN, shopId);
		}));

		assertThat(adminDaDocHoSo.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
		// Người bán lưu hồ sơ và commit trong lúc transaction của Admin còn dở.
		shopService.updateMine(OWNER, request("Tiệm Gốm Thanh Hà"));
		nguoiBanDaLuu.countDown();

		assertThatThrownBy(duyetCuaAdmin::get)
				.hasCauseInstanceOf(ObjectOptimisticLockingFailureException.class);

		// Bên thua bị rollback trọn vẹn: trạng thái, lịch sử và sự kiện đều không đổi;
		// thay đổi của người bán còn nguyên (không bị ghi đè ngược).
		var shop = shopRepository.findById(shopId).orElseThrow();
		assertThat(shop.getStatus()).isEqualTo(ShopStatus.PENDING);
		assertThat(shop.getName()).isEqualTo("Tiệm Gốm Thanh Hà");
		assertThat(shop.getApprovedAt()).isNull();
		assertThat(historyRepository.findByShopIdOrderByCreatedAtAscIdAsc(shopId)).hasSize(1);
		verifyNoInteractions(eventPublisher);
	}

	/**
	 * Lỗi của bên thua phải ra {@code 409 CONCURRENT_MODIFICATION} chứ không phải 500:
	 * đây là tình huống bình thường, client tải lại rồi thao tác tiếp là xong.
	 */
	@Test
	void xungDotVersion_duocDichThanh409_CONCURRENT_MODIFICATION() {
		var response = handler.handleOptimisticLock(
				new ObjectOptimisticLockingFailureException("shops", "01JBQ9YDX7K3M8N5P2R4T6V8W0"));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(response.getBody().error().code()).isEqualTo("CONCURRENT_MODIFICATION");
	}
}
