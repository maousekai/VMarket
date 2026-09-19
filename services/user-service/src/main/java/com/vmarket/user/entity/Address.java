package com.vmarket.user.entity;

import java.time.Instant;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Một địa chỉ trong sổ địa chỉ giao hàng (FR-USER-02).
 *
 * <p>Người nhận lưu riêng ({@code recipientName}, {@code phone}) chứ không lấy từ
 * {@link UserProfile}: gửi hàng cho người khác là chuyện bình thường, và địa chỉ
 * đã dùng cho đơn hàng cũ không được đổi theo khi chủ tài khoản sửa hồ sơ.
 *
 * <p>Bất biến <b>tối đa một địa chỉ mặc định cho mỗi user</b> được cưỡng chế ở
 * CSDL bằng partial unique index {@code uq_addresses_one_default_per_user}, và ở
 * tầng service bằng {@code AddressService}.
 */
@Entity
@Table(name = "addresses")
@Getter
@Setter
@NoArgsConstructor
public class Address extends BaseEntity {

	@Column(name = "user_id", nullable = false, length = ID_LENGTH)
	private String userId;

	@Column(name = "recipient_name", nullable = false, length = 100)
	private String recipientName;

	@Column(nullable = false, length = 20)
	private String phone;

	@Column(nullable = false, length = 100)
	private String province;

	@Column(nullable = false, length = 100)
	private String district;

	@Column(nullable = false, length = 100)
	private String ward;

	/** Số nhà, tên đường, toà nhà — phần chi tiết dưới cấp phường/xã. */
	@Column(name = "street_address", nullable = false, length = 255)
	private String streetAddress;

	/** Ghi chú cho shipper: "gọi trước khi giao", "cổng sau"... */
	@Column(length = 255)
	private String note;

	@Column(name = "is_default", nullable = false)
	private boolean isDefault;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;
}
