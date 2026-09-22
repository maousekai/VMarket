package com.vmarket.shop.entity;

import java.text.Normalizer;
import java.time.Instant;
import java.util.Locale;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Gian hàng (SRS 7.1 — Shop). Mỗi tài khoản mở tối đa một gian hàng.
 *
 * <p>Hai ràng buộc duy nhất được khai cả ở đây lẫn trong migration, <b>cùng tên</b>:
 * test chạy trên schema do Hibernate sinh ({@code create-drop}) nên phải có tên giống
 * hệt migration thì {@code GlobalExceptionHandler} mới nhận ra đúng ràng buộc nào
 * bị vi phạm ở cả hai môi trường.
 */
@Entity
@Table(name = "shops", uniqueConstraints = {
		@UniqueConstraint(name = Shop.UQ_OWNER_ID, columnNames = "owner_id"),
		@UniqueConstraint(name = Shop.UQ_NAME_KEY, columnNames = "name_key"),
})
@Getter
@Setter
@NoArgsConstructor
public class Shop extends BaseEntity {

	/** Mỗi tài khoản một gian hàng. */
	public static final String UQ_OWNER_ID = "uq_shops_owner_id";

	/** Tên gian hàng không trùng (không phân biệt hoa thường / khoảng trắng thừa). */
	public static final String UQ_NAME_KEY = "uq_shops_name_key";

	public static final int NAME_MAX_LENGTH = 100;

	/** userId của chủ gian hàng — lấy từ claim {@code sub}, không bao giờ từ body. */
	@Column(name = "owner_id", nullable = false, length = ID_LENGTH, updatable = false)
	private String ownerId;

	@Column(nullable = false, length = NAME_MAX_LENGTH)
	@Setter(AccessLevel.NONE)
	private String name;

	/**
	 * Khoá so khớp tên, luôn suy ra từ {@link #name} trong {@link #setName} — không có
	 * setter riêng để hai cột không thể lệch nhau.
	 */
	@Column(name = "name_key", nullable = false, length = NAME_MAX_LENGTH)
	@Setter(AccessLevel.NONE)
	private String nameKey;

	@Column(length = 2000)
	private String description;

	@Column(name = "logo_url", length = 500)
	private String logoUrl;

	@Column(name = "cover_url", length = 500)
	private String coverUrl;

	/** Chính sách đổi trả / vận chuyển (FR-SHOP-02). */
	@Column(length = 5000)
	private String policies;

	@Column(name = "contact_email", nullable = false, length = 255)
	private String contactEmail;

	@Column(name = "contact_phone", nullable = false, length = 20)
	private String contactPhone;

	@Column(nullable = false, length = 100)
	private String province;

	@Column(nullable = false, length = 100)
	private String district;

	@Column(nullable = false, length = 100)
	private String ward;

	/** Số nhà, tên đường của kho / điểm lấy hàng. */
	@Column(name = "street_address", nullable = false, length = 255)
	private String streetAddress;

	/**
	 * Chỉ đổi qua {@code ShopStatusTransitioner} — nơi kiểm tra bước chuyển
	 * ({@link ShopAction}), ghi lịch sử và phát sự kiện.
	 */
	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private ShopStatus status;

	/** Lý do của lần từ chối / đình chỉ gần nhất; {@code null} khi đã duyệt hoặc vừa gửi lại. */
	@Column(name = "status_reason", length = 500)
	private String statusReason;

	/** Lần đầu được duyệt — giữ nguyên qua các lần đình chỉ / gỡ đình chỉ. */
	@Column(name = "approved_at")
	private Instant approvedAt;

	/**
	 * Optimistic locking: Admin duyệt đúng lúc người bán đang sửa thì request tới sau
	 * nhận 409 thay vì ghi đè lặng lẽ (vd Admin duyệt một bản hồ sơ đã bị sửa khác đi).
	 */
	@Version
	@Column(nullable = false)
	private long version;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	/** Gán tên (đã chuẩn hoá) và cập nhật luôn {@link #nameKey}. */
	public void setName(String rawName) {
		this.name = normalizeName(rawName);
		this.nameKey = nameKeyOf(rawName);
	}

	/**
	 * Chuẩn hoá tên hiển thị: Unicode NFC, bỏ khoảng trắng đầu/cuối, gộp khoảng trắng
	 * liên tiếp. NFC quan trọng với tiếng Việt: "ấ" gõ dựng sẵn và "ấ" tổ hợp (a + dấu)
	 * trông giống hệt nhau nhưng khác byte — không chuẩn hoá thì lách được ràng buộc
	 * trùng tên.
	 */
	public static String normalizeName(String rawName) {
		return Normalizer.normalize(rawName, Normalizer.Form.NFC).strip().replaceAll("\\s+", " ");
	}

	/** Khoá so khớp tên: tên đã chuẩn hoá, viết thường. */
	public static String nameKeyOf(String rawName) {
		return normalizeName(rawName).toLowerCase(Locale.ROOT);
	}
}
