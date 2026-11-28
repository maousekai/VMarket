package com.vmarket.auth.email;

import java.time.Duration;

/**
 * Email kich hoat tai khoan shipper do admin tao (review PR 68, muc 4.9): ma dung de xac thuc
 * email va dat mat khau rieng o trang "Dat lai mat khau". Dat mat khau bang ma nay danh dau email
 * da xac thuc (xem UserRepository.resetPasswordAndClearLock), tai khoan chuyen sang Hoat dong.
 */
public final class ShipperActivationEmailContent {

	private ShipperActivationEmailContent() {
	}

	public static EmailMessage build(String toEmail, String code, Duration ttl) {
		long minutes = Math.max(1, ttl.toMinutes());
		String subject = "Kích hoạt tài khoản shipper VMarket";
		String text = """
				Quản trị viên VMarket đã tạo tài khoản shipper cho email này.

				Mã kích hoạt của bạn là: %s

				Để kích hoạt: mở VMarket, chọn "Quên mật khẩu" (trang Đặt lại mật khẩu), nhập email
				này, mã kích hoạt và mật khẩu mới. Sau đó đăng nhập bằng mật khẩu vừa đặt.
				Mã có hiệu lực trong %d phút. Hết hạn thì nhờ quản trị viên gửi lại email kích hoạt.

				Nếu bạn không đăng ký làm shipper VMarket, hãy bỏ qua email này.

				— VMarket""".formatted(code, minutes);
		String html = """
				<div style="font-family:sans-serif;font-size:15px;color:#1a1a1a">
				  <p>Quản trị viên VMarket đã tạo tài khoản <b>shipper</b> cho email này.</p>
				  <p>Mã kích hoạt của bạn là:</p>
				  <p style="font-size:28px;font-weight:700;letter-spacing:4px">%s</p>
				  <p>Để kích hoạt: mở VMarket, chọn <b>Quên mật khẩu</b> (trang Đặt lại mật khẩu),
				  nhập email này, mã kích hoạt và mật khẩu mới, rồi đăng nhập bằng mật khẩu vừa đặt.</p>
				  <p>Mã có hiệu lực trong <b>%d phút</b>. Hết hạn thì nhờ quản trị viên gửi lại email.</p>
				  <p style="color:#666">Nếu bạn không đăng ký làm shipper VMarket, hãy bỏ qua email này.</p>
				  <p style="color:#666">— VMarket</p>
				</div>""".formatted(code, minutes);
		return new EmailMessage(toEmail, subject, html, text);
	}
}
