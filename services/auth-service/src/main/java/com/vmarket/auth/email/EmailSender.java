package com.vmarket.auth.email;

/**
 * Cổng gửi email giao dịch. Chọn implementation qua {@code auth.email.provider}
 * ({@code brevo} | {@code log}). Thêm provider mới = thêm 1 lớp implements, không
 * đụng chỗ gọi (OTP, quên mật khẩu...).
 */
public interface EmailSender {

	/**
	 * Gửi email. Nếu provider từ chối, ném {@code ApiException("EMAIL_SEND_FAILED", 502)}.
	 */
	void send(EmailMessage message);
}
