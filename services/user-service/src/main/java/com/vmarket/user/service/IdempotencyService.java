package com.vmarket.user.service;

import java.time.Instant;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.vmarket.user.config.IdempotencyProperties;
import com.vmarket.user.entity.IdempotencyRecord;
import com.vmarket.user.repository.IdempotencyRecordRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Chống xử lý trùng cho các endpoint ghi dữ liệu, dựa trên header
 * {@code Idempotency-Key} (xem {@code IdempotencyFilter}).
 *
 * <p>Chốt chặn là ràng buộc {@code uq_idempotency_user_key} ở CSDL chứ không phải
 * một câu "kiểm tra xem đã tồn tại chưa" ở ứng dụng: hai request song song đều đọc
 * thấy "chưa có" rồi cùng chạy là đúng cái tình huống cần chặn, nên phải để CSDL
 * phân xử.
 *
 * <p><b>Không method nào ở đây được giữ transaction bắc ngang qua lúc handler
 * chạy.</b> Dòng "đang xử lý" phải commit NGAY thì request song song mới nhìn thấy
 * mà dừng lại. Vì vậy mỗi bước là một transaction ngắn riêng.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IdempotencyService {

	private final IdempotencyRecordRepository repository;
	private final IdempotencyProperties properties;

	/**
	 * Xin quyền chạy một request.
	 *
	 * <p>Cố ý chèn trước rồi bắt lỗi, thay vì đọc trước rồi mới chèn: cách đọc-trước
	 * vẫn để lọt hai request chèn cùng lúc.
	 */
	public IdempotencyOutcome begin(String userId, String key, String method, String path, String fingerprint) {
		IdempotencyRecord claim = new IdempotencyRecord();
		claim.setUserId(userId);
		claim.setIdempotencyKey(key);
		claim.setRequestMethod(method);
		claim.setRequestPath(path);
		claim.setRequestFingerprint(fingerprint);

		try {
			// saveAndFlush chứ không save: cần lỗi ràng buộc bung ra NGAY ở đây để bắt,
			// chứ không phải lúc transaction đóng lại ở đâu đó phía sau.
			return new IdempotencyOutcome.Proceed(repository.saveAndFlush(claim).getId());
		} catch (DataIntegrityViolationException ex) {
			return resolveExisting(userId, key, fingerprint);
		}
	}

	/** Lưu lại response để lần gửi lại cùng key nhận đúng kết quả này. */
	@Transactional
	public void complete(String recordId, int status, String body, String contentType) {
		repository.findById(recordId).ifPresent(record -> {
			record.setResponseStatus(status);
			record.setResponseBody(body);
			record.setResponseContentType(contentType);
			record.setCompletedAt(Instant.now());
		});
	}

	/**
	 * Nhả key ra.
	 *
	 * <p>Gọi khi request KHÔNG thành công (4xx/5xx) hoặc khi response quá lớn để lưu.
	 * Giữ lại một kết quả lỗi sẽ khoá luôn key đó: client sửa dữ liệu rồi gửi lại vẫn
	 * nhận về đúng lỗi cũ.
	 */
	public void release(String recordId) {
		repository.deleteById(recordId);
	}

	/**
	 * Một request khác đã giữ key này — quyết định xem nên phát lại, từ chối, hay
	 * bảo client thử lại.
	 */
	private IdempotencyOutcome resolveExisting(String userId, String key, String fingerprint) {
		IdempotencyRecord existing = repository.findByUserIdAndIdempotencyKey(userId, key).orElse(null);
		if (existing == null) {
			// Dòng vừa bị nhả/dọn đúng vào khoảng giữa hai câu lệnh. Hiếm; bảo client
			// gửi lại rẻ hơn là thêm một vòng thử lại ở đây.
			return retryLater();
		}

		if (!existing.getRequestFingerprint().equals(fingerprint)) {
			// Cùng key nhưng khác nội dung: gần như chắc chắn là client sinh key sai
			// (dùng lại một key cho mọi request). Im lặng phát lại response cũ sẽ nuốt
			// mất request thật mà không ai biết, nên báo lỗi rõ ràng.
			log.warn("Idempotency-Key bị dùng lại cho request khác (userId={})", userId);
			return new IdempotencyOutcome.Rejected(HttpStatus.UNPROCESSABLE_ENTITY, "IDEMPOTENCY_KEY_REUSED",
					"Idempotency-Key này đã dùng cho một request khác, hãy sinh key mới");
		}

		if (existing.isCompleted()) {
			return new IdempotencyOutcome.Replay(existing.getResponseStatus(), existing.getResponseBody(),
					existing.getResponseContentType());
		}

		if (existing.getCreatedAt().isBefore(Instant.now().minus(properties.getClaimTimeout()))) {
			// Request gốc treo quá lâu — nhiều khả năng tiến trình xử lý nó đã chết
			// (service bị kill / restart giữa chừng). Không nhả thì key đó hỏng vĩnh viễn.
			log.warn("Nhả Idempotency-Key treo quá {} (userId={})", properties.getClaimTimeout(), userId);
			repository.deleteById(existing.getId());
		}

		return retryLater();
	}

	private static IdempotencyOutcome retryLater() {
		return new IdempotencyOutcome.Rejected(HttpStatus.CONFLICT, "IDEMPOTENCY_IN_PROGRESS",
				"Một request trước với cùng Idempotency-Key đang được xử lý, vui lòng thử lại sau giây lát");
	}

	/**
	 * Dọn các bản ghi đã quá hạn giữ.
	 *
	 * <p>Chạy ở mọi instance nên cùng một dòng có thể bị nhiều instance cùng xoá —
	 * vô hại, xoá dòng không còn tồn tại chỉ là một câu lệnh không tác dụng.
	 */
	// initialDelay = fixedDelay: khong can quet ngay luc khoi dong (bang vua sach sau
	// mot chu ky), va de vong chay dau tien khong roi vao luc service dang ban khoi dong.
	@Scheduled(initialDelayString = "PT1H", fixedDelayString = "PT1H")
	@Transactional
	public void purgeExpired() {
		int deleted = repository.deleteCreatedBefore(Instant.now().minus(properties.getRetention()));
		if (deleted > 0) {
			log.info("Dọn {} bản ghi Idempotency-Key đã quá hạn", deleted);
		}
	}
}
