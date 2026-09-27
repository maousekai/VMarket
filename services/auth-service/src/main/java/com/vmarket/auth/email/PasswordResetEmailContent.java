package com.vmarket.auth.email;

import java.time.Duration;

/**
 * Dựng nội dung email chứa mã đặt lại mật khẩu (FR-AUTH-04). Tách riêng khỏi
 * {@link OtpEmailContent} vì đây là hành động nhạy cảm hơn (chiếm quyền tài
 * khoản nếu lộ mã) — chủ đề/nội dung khác, và nhắc rõ nếu không phải người dùng
 * yêu cầu.
 */
public final class PasswordResetEmailContent {

	private PasswordResetEmailContent() {
	}

	public static EmailMessage build(String toEmail, String code, Duration ttl) {
		long minutes = Math.max(1, ttl.toMinutes());
		// Mã CHỈ nằm trong body — subject hiện ở màn hình khoá / log của mail relay.
		String subject = "Yêu cầu đặt lại mật khẩu VMarket";
		String text = """
				Mã đặt lại mật khẩu của bạn là: %s

				Mã có hiệu lực trong %d phút. Không chia sẻ mã này cho bất kỳ ai.
				Nếu bạn không yêu cầu đổi mật khẩu, hãy bỏ qua email này và cân nhắc
				kiểm tra lại bảo mật tài khoản.

				— VMarket""".formatted(code, minutes);
		String html = """
				<div style="font-family:sans-serif;font-size:15px;color:#1a1a1a">
				  <p>Mã đặt lại mật khẩu của bạn là:</p>
				  <p style="font-size:28px;font-weight:700;letter-spacing:4px">%s</p>
				  <p>Mã có hiệu lực trong <b>%d phút</b>. Không chia sẻ mã này cho bất kỳ ai.</p>
				  <p style="color:#666">Nếu bạn không yêu cầu đổi mật khẩu, hãy bỏ qua email này và
				  cân nhắc kiểm tra lại bảo mật tài khoản.</p>
				  <p style="color:#666">— VMarket</p>
				</div>""".formatted(code, minutes);
		return new EmailMessage(toEmail, subject, html, text);
	}
}
