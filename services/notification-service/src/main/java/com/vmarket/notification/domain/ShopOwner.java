package com.vmarket.notification.domain;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Bản chiếu {@code shopId → ownerId} dựng từ sự kiện {@code ShopApproved} /
 * {@code ShopSuspended} — collection {@code shop_owners}.
 *
 * <p>Đơn hàng chỉ mang {@code shopId}; Notification cần biết chủ gian hàng để báo
 * Seller mà không phải gọi đồng bộ sang shop-service. Gian hàng được duyệt TRƯỚC khi
 * queue của Notification tồn tại sẽ chưa có trong bản chiếu (xem README).
 */
@Document("shop_owners")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ShopOwner {

	@Id
	private String shopId;

	private String ownerId;

	private String shopName;

	/** timestamp của sự kiện mới nhất đã áp dụng — bỏ qua sự kiện cũ đến muộn. */
	private long eventTimestamp;

	private Instant updatedAt;
}
