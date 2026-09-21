package com.vmarket.user.service;

import org.springframework.http.HttpStatus;

/**
 * Ba kết cục có thể có khi một request mang {@code Idempotency-Key} đi qua
 * {@code IdempotencyService.begin(...)}.
 *
 * <p>Tách thành kiểu riêng thay vì để filter tự suy ra từ bản ghi: quy tắc "khi nào
 * thì chạy, khi nào phát lại, khi nào từ chối" nằm gọn một chỗ và test được mà không
 * cần dựng MockMvc.
 */
public sealed interface IdempotencyOutcome {

	/** Chưa từng thấy key này — chạy handler như bình thường rồi lưu kết quả lại. */
	record Proceed(String recordId) implements IdempotencyOutcome {
	}

	/** Đã xử lý xong trước đó — trả lại nguyên response cũ, không chạy handler. */
	record Replay(int status, String body, String contentType) implements IdempotencyOutcome {
	}

	/** Không chạy được: key đang bận, hoặc bị dùng lại cho một request khác. */
	record Rejected(HttpStatus status, String code, String message) implements IdempotencyOutcome {
	}
}
