package com.vmarket.auth.email;

import java.time.Duration;

/**
 * Dựng nội dung email chứa mã OTP xác thực. Tách riêng để tái dùng / đổi mẫu.
 */
public final class OtpEmailContent {

	private OtpEmailContent() {
	}

	public static EmailMessage build(String toEmail, String code, Duration ttl) {
		long minutes = Math.max(1, ttl.toMinutes());
		// Mã CHỈ nằm trong body — subject hiện ở màn hình khoá / log của mail relay.
		String subject = "Mã xác thực email VMarket";
		String text = """
				Mã xác thực email của bạn là: %s

				Mã có hiệu lực trong %d phút. Không chia sẻ mã này cho bất kỳ ai.
				Nếu bạn không yêu cầu, hãy bỏ qua email này.

				— VMarket""".formatted(code, minutes);
		String html = """
				<div style="font-family:sans-serif;font-size:15px;color:#1a1a1a">
				  <p>Mã xác thực email của bạn là:</p>
				  <p style="font-size:28px;font-weight:700;letter-spacing:4px">%s</p>
				  <p>Mã có hiệu lực trong <b>%d phút</b>. Không chia sẻ mã này cho bất kỳ ai.</p>
				  <p style="color:#666">Nếu bạn không yêu cầu, hãy bỏ qua email này.</p>
				  <p style="color:#666">— VMarket</p>
				</div>""".formatted(code, minutes);
		return new EmailMessage(toEmail, subject, html, text);
	}
}
