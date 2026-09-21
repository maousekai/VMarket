package com.vmarket.user.entity;

import java.time.Instant;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Một lần sử dụng header {@code Idempotency-Key}: đã nhận request nào, và nếu đã
 * xử lý xong thì response là gì để phát lại.
 *
 * <p>Vì sao cần: client hết thời gian chờ rồi gửi lại {@code POST
 * /api/users/me/addresses} là chuyện thường ngày (mạng 3G, bấm hai lần, retry tự
 * động của thư viện HTTP). Không có bảng này thì lần gửi lại tạo thêm một địa chỉ
 * trùng, không lỗi gì báo ra, người dùng phải tự phát hiện và dọn.
 *
 * <p>Vòng đời một dòng:
 * <ol>
 *   <li><b>Đang xử lý</b> — {@code responseStatus == null}. Dòng được chèn TRƯỚC khi
 *       chạy handler; chính cú chèn này là chốt chặn: ràng buộc
 *       {@code uq_idempotency_user_key} làm request thứ hai không chèn được.</li>
 *   <li><b>Xong</b> — {@code responseStatus != null}, có sẵn body để phát lại.</li>
 * </ol>
 *
 * <p>Response KHÔNG thành công không được lưu: dòng bị xoá để client sửa dữ liệu
 * rồi gửi lại cùng key vẫn chạy được.
 *
 * <p>{@code requestFingerprint} (SHA-256 của method + đường dẫn + body) để phát
 * hiện client dùng lại một key cho request khác. Thiếu nó thì một client lập trình
 * sai (gắn cứng một key cho mọi request) sẽ bị nuốt hết các request sau mà vẫn nhận
 * {@code 201} của request đầu tiên.
 */
@Entity
// Rang buoc duy nhat khai ca o day chu khong chi o migration: test chay tren H2 voi
// schema do Hibernate sinh (ddl-auto: create-drop), thieu no thi co che chong trung
// im lang khong hoat dong trong toan bo bo test.
@Table(name = "idempotency_keys", uniqueConstraints = @UniqueConstraint(
		name = "uq_idempotency_user_key", columnNames = { "user_id", "idempotency_key" }))
@Getter
@Setter
@NoArgsConstructor
public class IdempotencyRecord extends BaseEntity {

	/** Giới hạn độ dài key nhận từ client — cũng là độ dài cột. */
	public static final int KEY_MAX_LENGTH = 200;

	/** Response dài hơn mức này không lưu để phát lại — xem javadoc của lớp. */
	public static final int BODY_MAX_LENGTH = 8000;

	/** Khoá theo từng user: key của người này không che được request của người kia. */
	@Column(name = "user_id", nullable = false, length = ID_LENGTH)
	private String userId;

	@Column(name = "idempotency_key", nullable = false, length = KEY_MAX_LENGTH)
	private String idempotencyKey;

	@Column(name = "request_method", nullable = false, length = 10)
	private String requestMethod;

	@Column(name = "request_path", nullable = false, length = 500)
	private String requestPath;

	/** SHA-256 dạng hex của method + đường dẫn + body. */
	@Column(name = "request_fingerprint", nullable = false, length = 64)
	private String requestFingerprint;

	/** {@code null} = request đầu tiên vẫn đang chạy. */
	@Column(name = "response_status")
	private Integer responseStatus;

	@Column(name = "response_body", length = BODY_MAX_LENGTH)
	private String responseBody;

	@Column(name = "response_content_type", length = 100)
	private String responseContentType;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "completed_at")
	private Instant completedAt;

	public boolean isCompleted() {
		return responseStatus != null;
	}
}
