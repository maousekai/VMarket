package com.vmarket.notification.email;

/**
 * Cổng gửi email giao dịch (FR-NOTI-02). Chọn implementation bằng
 * {@code app.notification.email.provider}: {@code log} (mặc định) | {@code smtp} | {@code brevo}.
 */
public interface EmailSender {

	/** Tên provider ghi vào {@code EmailLog}. */
	String provider();

	/** Gửi email; ném {@link EmailDeliveryException} nếu provider từ chối hoặc không kết nối được. */
	void send(EmailMessage message);
}
