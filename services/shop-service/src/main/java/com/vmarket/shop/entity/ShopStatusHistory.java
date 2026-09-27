package com.vmarket.shop.entity;

import java.time.Instant;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Một bước chuyển trạng thái của gian hàng (SRS 7.1 — ShopStatusHistory). Chỉ ghi
 * thêm, không sửa / xoá: đây là nhật ký kiểm duyệt — ai duyệt, ai từ chối, lý do gì.
 *
 * <p>{@code shopId} là cột thường chứ không phải {@code @ManyToOne}: lịch sử luôn được
 * đọc theo một gian hàng đã biết, không cần nạp ngược {@link Shop}, và tránh được bẫy
 * lazy loading khi serialize. Khoá ngoại thật vẫn nằm trong migration (cùng CSDL).
 */
@Entity
@Table(name = "shop_status_history")
@Getter
@Setter
@NoArgsConstructor
public class ShopStatusHistory extends BaseEntity {

	@Column(name = "shop_id", nullable = false, length = ID_LENGTH, updatable = false)
	private String shopId;

	/** {@code null} ở bước đầu tiên — hồ sơ vừa được nộp. */
	@Enumerated(EnumType.STRING)
	@Column(name = "from_status", length = 20, updatable = false)
	private ShopStatus fromStatus;

	@Enumerated(EnumType.STRING)
	@Column(name = "to_status", nullable = false, length = 20, updatable = false)
	private ShopStatus toStatus;

	@Column(length = 500, updatable = false)
	private String reason;

	/** userId người thao tác: chủ gian hàng (nộp / gửi lại) hoặc Admin. */
	@Column(name = "changed_by", nullable = false, length = ID_LENGTH, updatable = false)
	private String changedBy;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;
}
