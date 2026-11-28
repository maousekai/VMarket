package com.vmarket.notification.domain;

/** Loại thông báo — client dùng để chọn icon / điều hướng. */
public enum NotificationType {
	/** Buyer: đặt hàng thành công. */
	ORDER_PLACED,
	/** Seller: gian hàng có đơn mới cần xử lý. */
	NEW_ORDER,
	/** Buyer: thanh toán online thành công. */
	PAYMENT_SUCCEEDED,
	/** Buyer: thanh toán thất bại / hết hạn. */
	PAYMENT_FAILED,
	/** Buyer: đơn chuyển trạng thái. */
	ORDER_STATUS_CHANGED,
	/** Seller: đơn của gian hàng bị huỷ. */
	ORDER_CANCELLED,
	/** Seller: đơn của gian hàng đã giao thành công. */
	ORDER_DELIVERED,
	/** Shipper: được phân công chuyến; Seller: đơn đã có shipper. */
	DELIVERY_ASSIGNED,
	/** Buyer: một lần giao hàng thất bại. */
	DELIVERY_FAILED,
	SHOP_APPROVED,
	SHOP_REINSTATED,
	SHOP_SUSPENDED,
	PRODUCT_REMOVED,
	PRODUCT_RESTORED,
	/** Buyer: yêu cầu trả hàng đã có kết quả. */
	RETURN_RESOLVED,
	/** Seller: người mua gửi yêu cầu trả hàng, cần phản hồi trong 48 giờ (FR-ORD-07). */
	RETURN_REQUESTED,
	/** Buyer: khoản hoàn tiền đã được chuyển (PaymentRefunded). */
	PAYMENT_REFUNDED,
	/** Seller: shipper đã trả hàng hoàn về shop, cần xác nhận đã nhận lại. */
	RETURN_DROPPED_OFF,
	/** Admin: tự động phân công giao hàng thất bại, cần phân công tay. */
	DELIVERY_NEEDS_DISPATCH
}
