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
 * Một trường trong hồ sơ gian hàng vừa bị người bán sửa (FR-SHOP-02).
 *
 * <p>Người bán được sửa hồ sơ kể cả khi gian hàng đang "Hoạt động" và việc đó
 * <b>không</b> đưa gian hàng về "Chờ duyệt" — nếu không, một lần sửa mô tả sẽ làm
 * gian hàng biến mất khỏi người mua và đơn hàng đứng lại. Đổi lại, nội dung đã
 * được duyệt có thể bị thay bằng nội dung vi phạm mà Admin không hay. Bảng này là
 * dấu vết để Admin đối chiếu và đình chỉ khi cần (xem {@code ShopProfileAuditor}).
 *
 * <p>Mỗi trường đổi là một dòng — thay vì một dòng chứa JSON tất cả thay đổi — để
 * Admin lọc thẳng theo {@code field_name} (ví dụ chỉ xem các lần đổi
 * {@code policies}) mà không phải bóc JSON.
 *
 * <p>Chỉ ghi thêm, không sửa / xoá.
 */
@Entity
@Table(name = "shop_profile_changes")
@Getter
@Setter
@NoArgsConstructor
public class ShopProfileChange extends BaseEntity {

	/** Giới hạn của cột dài nhất trong {@code shops} ({@code policies}). */
	public static final int VALUE_MAX_LENGTH = 5000;

	@Column(name = "shop_id", nullable = false, length = ID_LENGTH, updatable = false)
	private String shopId;

	/** Tên trường trong hồ sơ: {@code name}, {@code description}, {@code policies}... */
	@Column(name = "field_name", nullable = false, length = 40, updatable = false)
	private String fieldName;

	@Column(name = "old_value", length = VALUE_MAX_LENGTH, updatable = false)
	private String oldValue;

	@Column(name = "new_value", length = VALUE_MAX_LENGTH, updatable = false)
	private String newValue;

	/** Trạng thái gian hàng ngay lúc sửa — sửa khi đang ACTIVE là trường hợp Admin cần soi. */
	@Enumerated(EnumType.STRING)
	@Column(name = "status_at_change", nullable = false, length = 20, updatable = false)
	private ShopStatus statusAtChange;

	@Column(name = "changed_by", nullable = false, length = ID_LENGTH, updatable = false)
	private String changedBy;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;
}
