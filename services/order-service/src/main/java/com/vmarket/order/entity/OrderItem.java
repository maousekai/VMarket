package com.vmarket.order.entity;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Một dòng hàng trong đơn — snapshot tại thời điểm đặt (FR-ORDER-01).
 *
 * <p>{@code productId}/{@code variantId}/{@code shopId} chỉ là id trần: dữ liệu
 * sản phẩm hiện tại nằm ở product-service và LUÔN có thể đổi (giá, xóa sản phẩm),
 * còn đơn hàng phải ghi lại đúng thứ người mua đã chọn với đúng giá lúc chốt.
 *
 * <p>Id của item được sinh theo ULID ở {@link BaseEntity#assignId()} — sắp tăng
 * theo id là sắp theo thứ tự thêm vào (xem {@code Order.items}).
 */
@Entity
@Table(name = "order_items")
@Getter
@Setter
@NoArgsConstructor
public class OrderItem extends BaseEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "order_id", nullable = false)
	private Order order;

	@Column(name = "product_id", nullable = false, length = ID_LENGTH)
	private String productId;

	/** Biến thể (màu/size...). Sản phẩm không có biến thể thì null. */
	@Column(name = "variant_id", length = ID_LENGTH)
	private String variantId;

	/** Gian hàng bán item này — client dùng để nhóm hiển thị (FR-CART-02). */
	@Column(name = "shop_id", nullable = false, length = ID_LENGTH)
	private String shopId;

	@Column(nullable = false)
	private int quantity;

	/** Đơn giá tại thời điểm đặt hàng. */
	@Column(name = "unit_price", nullable = false, precision = 12, scale = 2)
	private BigDecimal unitPrice;

	/** Tổng tiền dòng = unitPrice * quantity, tính lúc tạo để truy vấn không phải nhân lại. */
	@Column(name = "line_total", nullable = false, precision = 12, scale = 2)
	private BigDecimal lineTotal;
}