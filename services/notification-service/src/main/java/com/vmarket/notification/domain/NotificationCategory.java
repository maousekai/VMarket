package com.vmarket.notification.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Nhóm thông báo người dùng bật/tắt email và push. Thông báo trong ứng dụng luôn được lưu.
 */
public enum NotificationCategory {
	ORDER("Đơn hàng & giao hàng", EnumSet.of(NotificationType.ORDER_PLACED, NotificationType.NEW_ORDER,
			NotificationType.ORDER_STATUS_CHANGED, NotificationType.ORDER_CANCELLED, NotificationType.ORDER_DELIVERED,
			NotificationType.DELIVERY_ASSIGNED, NotificationType.DELIVERY_FAILED)),
	PAYMENT("Thanh toán & hoàn tiền", EnumSet.of(NotificationType.PAYMENT_SUCCEEDED,
			NotificationType.PAYMENT_FAILED, NotificationType.RETURN_RESOLVED)),
	SHOP("Gian hàng & sản phẩm", EnumSet.of(NotificationType.SHOP_APPROVED, NotificationType.SHOP_REINSTATED,
			NotificationType.SHOP_SUSPENDED, NotificationType.PRODUCT_REMOVED, NotificationType.PRODUCT_RESTORED));

	private final String label;
	private final Set<NotificationType> types;

	NotificationCategory(String label, Set<NotificationType> types) {
		this.label = label;
		this.types = types;
	}

	public String label() {
		return label;
	}

	public static NotificationCategory of(NotificationType type) {
		for (NotificationCategory category : values()) {
			if (category.types.contains(type)) {
				return category;
			}
		}
		throw new IllegalArgumentException("Loại thông báo chưa thuộc nhóm nào: " + type);
	}
}
