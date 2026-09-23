package com.vmarket.order.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Một đơn hàng (FR-ORDER-01).
 *
 * <p><b>Địa chỉ giao hàng là snapshot</b> — các cột {@code recipientName}...
 * {@code streetAddress} được chép từ sổ địa chỉ của user-service tại thời điểm đặt
 * hàng, KHÔNG tham chiếu lại bảng {@code addresses}: người dùng sửa/xoá địa chỉ
 * sau đó không được làm đổi thông tin của đơn đã đặt.
 *
 * <p>Tham chiếu liên hệ với service khác chỉ là id trần (không FK): {@code userId}
 * thuộc CSDL của auth-service, {@code productId}/{@code shopId} thuộc CSDL của
 * product-service — SRS mục 5 quy định database-per-service.
 */
@Entity
@Table(name = "orders")
@Getter
@Setter
@NoArgsConstructor
public class Order extends BaseEntity {

	@Column(name = "user_id", nullable = false, length = ID_LENGTH)
	private String userId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private OrderStatus status;

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

	@Column(name = "street_address", nullable = false, length = 255)
	private String streetAddress;

	/** Ghi chú cho người bán / shipper. */
	@Column(length = 255)
	private String note;

	/**
	 * Tổng tiền = tổng {@code lineTotal} của các item.
	 *
	 * <p>Được tính một lần lúc tạo đơn và không bao giờ đổi theo giá hiện tại của
	 * sản phẩm: đơn hàng là một giao dịch đã chốt, giá đổi sau chỉ ảnh hưởng đơn mới.
	 */
	@Column(name = "total_amount", nullable = false, precision = 12, scale = 2)
	private BigDecimal totalAmount;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	/**
	 * Item của đơn. {@code mappedBy} + {@code cascade ALL} để lưu đơn là lưu trọn
	 * bộ item trong cùng transaction (một đơn không item là đơn hỏng). items được
	 * gán từ bên trong service nên khởi tạo danh sách rỗng thay vì null.
	 *
	 * <p>{@code @OrderBy("id")} — ULID sắp theo thời gian nên item hiện theo đúng
	 * thứ tự thêm vào, ổn định giữa các lần đọc.
	 */
	@OneToMany(mappedBy = "order", fetch = FetchType.EAGER, cascade = CascadeType.ALL, orphanRemoval = true)
	@OrderBy("id ASC")
	private List<OrderItem> items = new ArrayList<>();
}