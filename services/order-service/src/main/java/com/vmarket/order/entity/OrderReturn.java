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
 * Một yêu cầu trả hàng / hoàn tiền (FR-ORD-06, FR-ORD-07).
 */
@Entity
@Table(name = "order_returns")
@Getter
@Setter
@NoArgsConstructor
public class OrderReturn extends BaseEntity {

	@Column(name = "order_id", nullable = false, length = ID_LENGTH)
	private String orderId;

	@Column(name = "user_id", nullable = false, length = ID_LENGTH)
	private String userId;

	@Column(name = "shop_id", nullable = false, length = ID_LENGTH)
	private String shopId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private ReturnStatus status;

	@Column(nullable = false, length = 500)
	private String reason;

	/** Danh sách URL ảnh / video minh chứng dạng chuỗi JSON (tối đa 5 URL). */
	@Column(name = "evidence_urls", columnDefinition = "TEXT")
	private String evidenceUrls;

	@Column(name = "seller_note", length = 500)
	private String sellerNote;

	@Column(name = "admin_note", length = 500)
	private String adminNote;

	@Column(name = "refund_amount", nullable = false, precision = 12, scale = 2)
	private BigDecimal refundAmount = BigDecimal.ZERO;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Column(name = "resolved_at")
	private Instant resolvedAt;

	@OneToMany(mappedBy = "orderReturn", fetch = FetchType.EAGER, cascade = CascadeType.ALL, orphanRemoval = true)
	@OrderBy("id ASC")
	private List<OrderReturnItem> items = new ArrayList<>();
}

