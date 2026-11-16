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
 * Một mặt hàng trong yêu cầu trả hàng (FR-ORD-06).
 */
@Entity
@Table(name = "order_return_items")
@Getter
@Setter
@NoArgsConstructor
public class OrderReturnItem extends BaseEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "return_id", nullable = false)
	private OrderReturn orderReturn;

	@Column(name = "order_item_id", nullable = false, length = ID_LENGTH)
	private String orderItemId;

	@Column(name = "product_id", nullable = false, length = CATALOG_ID_LENGTH)
	private String productId;

	@Column(name = "variant_id", length = CATALOG_ID_LENGTH)
	private String variantId;

	@Column(nullable = false)
	private int quantity;

	@Column(name = "unit_price", nullable = false, precision = 12, scale = 2)
	private BigDecimal unitPrice;

	@Column(name = "line_total", nullable = false, precision = 12, scale = 2)
	private BigDecimal lineTotal;
}

