package com.vmarket.notification.service;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import com.vmarket.notification.client.OrderSnapshot;
import com.vmarket.notification.config.NotificationProperties;
import com.vmarket.notification.domain.NotificationType;

/**
 * Nội dung tiếng Việt cho từng loại thông báo. Mọi dữ liệu động đưa vào HTML email đều
 * được escape (tên gian hàng, lý do do Admin nhập...).
 */
@Component
public class NotificationTemplates {

	private final String frontendUrl;

	public NotificationTemplates(NotificationProperties properties) {
		String url = properties.getFrontendUrl();
		this.frontendUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
	}

	// ---------------------------------------------------------------- Buyer: đơn hàng

	public NotificationMessage orderPlaced(OrderSnapshot order) {
		String link = orderLink(order.id());
		String amount = order.totalAmount() == null ? "" : " (" + money(order.totalAmount()) + ")";
		String body = order.isCod()
				? "Đơn hàng " + order.id() + amount + " đã được đặt thành công. Bạn sẽ thanh toán khi nhận hàng."
				: "Đơn hàng " + order.id() + amount + " đã được tạo. Vui lòng hoàn tất thanh toán để người bán xử lý đơn.";
		return new NotificationMessage(NotificationType.ORDER_PLACED, "Đặt hàng thành công", body, link,
				Map.of("orderId", order.id()), true,
				email("Xác nhận đặt hàng #" + order.id(), "Cảm ơn bạn đã đặt hàng tại VMarket", body, link,
						"Xem đơn hàng"));
	}

	public NotificationMessage paymentSucceeded(OrderSnapshot order) {
		String link = orderLink(order.id());
		String amount = order.totalAmount() == null ? "" : " " + money(order.totalAmount());
		String body = "Thanh toán" + amount + " cho đơn hàng " + order.id()
				+ " thành công. Người bán sẽ sớm xác nhận đơn.";
		return new NotificationMessage(NotificationType.PAYMENT_SUCCEEDED, "Thanh toán thành công", body, link,
				Map.of("orderId", order.id()), true,
				email("Thanh toán thành công cho đơn #" + order.id(), "Thanh toán thành công", body, link,
						"Xem đơn hàng"));
	}

	public NotificationMessage paymentFailed(String orderId, String reason) {
		String explanation = paymentFailureReason(reason);
		String body = "Thanh toán cho đơn hàng " + orderId + " không thành công"
				+ (explanation == null ? "." : ": " + explanation + ".");
		return new NotificationMessage(NotificationType.PAYMENT_FAILED, "Thanh toán không thành công", body,
				orderLink(orderId), Map.of("orderId", orderId), true, null);
	}

	/** {@code null} nếu trạng thái không cần báo cho người mua. */
	public NotificationMessage orderStatusChanged(String orderId, String status) {
		String text = switch (status) {
			case "PROCESSING" -> "Đơn hàng " + orderId + " đã được xác nhận và đang được chuẩn bị.";
			case "SHIPPED" -> "Đơn hàng " + orderId + " đang được giao tới bạn.";
			case "DELIVERED" -> "Đơn hàng " + orderId + " đã được giao thành công. Hãy đánh giá sản phẩm nhé!";
			case "CANCELLED" -> "Đơn hàng " + orderId + " đã bị huỷ.";
			default -> null;
		};
		if (text == null) {
			return null;
		}
		String title = "Đơn hàng " + statusLabel(status).toLowerCase(Locale.ROOT);
		String link = orderLink(orderId);
		return new NotificationMessage(NotificationType.ORDER_STATUS_CHANGED, capitalize(title), text, link,
				Map.of("orderId", orderId, "status", status), true,
				email("Cập nhật đơn hàng #" + orderId + ": " + statusLabel(status), capitalize(title), text, link,
						"Xem đơn hàng"));
	}

	public NotificationMessage deliveryFailed(String orderId) {
		return new NotificationMessage(NotificationType.DELIVERY_FAILED, "Giao hàng chưa thành công",
				"Shipper chưa giao được đơn hàng " + orderId + ". Đơn sẽ được giao lại, vui lòng chú ý điện thoại.",
				orderLink(orderId), Map.of("orderId", orderId), true, null);
	}

	public NotificationMessage returnResolved(String orderId, String returnId, boolean restock) {
		String body = "Yêu cầu trả hàng cho đơn " + orderId + " đã được xử lý"
				+ (restock ? " và hàng đã được nhập lại kho. Tiền hoàn (nếu có) sẽ được chuyển theo phương thức thanh toán ban đầu."
						: ".");
		String link = orderLink(orderId);
		return new NotificationMessage(NotificationType.RETURN_RESOLVED, "Yêu cầu trả hàng đã được xử lý", body, link,
				Map.of("orderId", orderId, "returnId", returnId == null ? "" : returnId), true,
				email("Kết quả yêu cầu trả hàng đơn #" + orderId, "Yêu cầu trả hàng đã được xử lý", body, link,
						"Xem đơn hàng"));
	}

	// ---------------------------------------------------------------- Seller

	public NotificationMessage newOrder(OrderSnapshot order, String shopId) {
		return new NotificationMessage(NotificationType.NEW_ORDER, "Bạn có đơn hàng mới",
				"Đơn hàng " + order.id() + " vừa được đặt tại gian hàng của bạn. Hãy chuẩn bị hàng để bàn giao.",
				sellerOrderLink(order.id()), Map.of("orderId", order.id(), "shopId", shopId), true, null);
	}

	public NotificationMessage sellerOrderCancelled(String orderId, String shopId) {
		return new NotificationMessage(NotificationType.ORDER_CANCELLED, "Đơn hàng đã bị huỷ",
				"Đơn hàng " + orderId + " của gian hàng đã bị huỷ.", sellerOrderLink(orderId),
				Map.of("orderId", orderId, "shopId", shopId), true, null);
	}

	public NotificationMessage sellerOrderDelivered(String orderId, String shopId) {
		return new NotificationMessage(NotificationType.ORDER_DELIVERED, "Đơn hàng đã giao thành công",
				"Đơn hàng " + orderId + " đã được giao tới người mua.", sellerOrderLink(orderId),
				Map.of("orderId", orderId, "shopId", shopId), false, null);
	}

	public NotificationMessage sellerDeliveryAssigned(String orderId, String shopId) {
		return new NotificationMessage(NotificationType.DELIVERY_ASSIGNED, "Đơn hàng đã có shipper",
				"Đơn hàng " + orderId + " đã được phân công shipper. Vui lòng chuẩn bị bàn giao hàng.",
				sellerOrderLink(orderId), Map.of("orderId", orderId, "shopId", shopId), true, null);
	}

	public NotificationMessage shopApproved(String shopId, String shopName, boolean reinstated) {
		String name = shopName == null ? "của bạn" : "\"" + shopName + "\"";
		NotificationType type = reinstated ? NotificationType.SHOP_REINSTATED : NotificationType.SHOP_APPROVED;
		String title = reinstated ? "Gian hàng đã được gỡ đình chỉ" : "Gian hàng đã được duyệt";
		String body = reinstated
				? "Gian hàng " + name + " đã hoạt động trở lại. Sản phẩm của bạn đã hiển thị lại với người mua."
				: "Chúc mừng! Gian hàng " + name + " đã được duyệt. Bạn có thể bắt đầu đăng bán sản phẩm.";
		String link = "/seller";
		return new NotificationMessage(type, title, body, link, Map.of("shopId", shopId), true,
				email(title, title, body, link, "Vào kênh người bán"));
	}

	public NotificationMessage shopSuspended(String shopId, String shopName, String reason) {
		String name = shopName == null ? "của bạn" : "\"" + shopName + "\"";
		String body = "Gian hàng " + name + " đã bị đình chỉ"
				+ (reason == null || reason.isBlank() ? "." : ". Lý do: " + reason + ".")
				+ " Sản phẩm tạm thời bị ẩn với người mua. Liên hệ quản trị viên để được hỗ trợ.";
		String link = "/seller";
		return new NotificationMessage(NotificationType.SHOP_SUSPENDED, "Gian hàng bị đình chỉ", body, link,
				Map.of("shopId", shopId), true,
				email("Gian hàng bị đình chỉ", "Gian hàng bị đình chỉ", body, link, "Vào kênh người bán"));
	}

	public NotificationMessage productModerated(String productId, String shopId, boolean removed, String reason) {
		String body = removed
				? "Một sản phẩm của bạn đã bị gỡ khỏi sàn"
						+ (reason == null || reason.isBlank() ? "." : ". Lý do: " + reason + ".")
						+ " Hãy chỉnh sửa và gửi lại để được xem xét."
				: "Sản phẩm của bạn đã được khôi phục và hiển thị lại với người mua.";
		return new NotificationMessage(removed ? NotificationType.PRODUCT_REMOVED : NotificationType.PRODUCT_RESTORED,
				removed ? "Sản phẩm bị gỡ" : "Sản phẩm được khôi phục", body, "/seller/products/" + productId,
				Map.of("productId", productId, "shopId", shopId == null ? "" : shopId), true, null);
	}

	// ---------------------------------------------------------------- Shipper

	public NotificationMessage shipperAssigned(String orderId, String assignmentId) {
		return new NotificationMessage(NotificationType.DELIVERY_ASSIGNED, "Bạn có chuyến giao hàng mới",
				"Bạn được phân công giao đơn hàng " + orderId + ". Mở ứng dụng để xem địa chỉ lấy và giao hàng.",
				"/shipper/assignments/" + assignmentId, Map.of("orderId", orderId, "assignmentId", assignmentId), true,
				null);
	}

	// ---------------------------------------------------------------- helpers

	static String statusLabel(String status) {
		return switch (status) {
			case "PENDING" -> "Chờ xác nhận";
			case "WAITING_PAYMENT" -> "Chờ thanh toán";
			case "PROCESSING" -> "Đang xử lý";
			case "SHIPPED" -> "Đang giao";
			case "DELIVERED" -> "Đã giao";
			case "CANCELLED" -> "Đã huỷ";
			default -> status;
		};
	}

	/**
	 * Mã lý do từ payment-service ({@code PaymentFailed.reason}) → câu cho người mua.
	 * Mã lạ trả {@code null} để không lộ mã kỹ thuật.
	 */
	static String paymentFailureReason(String reason) {
		if (reason == null) {
			return null;
		}
		return switch (reason) {
			case "EXPIRED" -> "phiên thanh toán đã hết hạn";
			case "CANCELLED" -> "giao dịch đã bị huỷ";
			case "RETRYABLE" -> "cổng thanh toán đang gặp sự cố, vui lòng thử lại sau";
			default -> null;
		};
	}

	/**
	 * Số tiền VND (order-service trả {@code BigDecimal} VND, chưa có trường currency nên ký
	 * hiệu ₫ đang cố định). Người gọi bỏ phần số tiền khỏi câu khi {@code amount == null}.
	 */
	static String money(BigDecimal amount) {
		DecimalFormat format = new DecimalFormat("#,##0", DecimalFormatSymbols.getInstance(Locale.forLanguageTag("vi-VN")));
		return format.format(amount) + " ₫";
	}

	private static String capitalize(String text) {
		return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
	}

	private String orderLink(String orderId) {
		return "/orders/" + orderId;
	}

	private String sellerOrderLink(String orderId) {
		return "/seller/orders/" + orderId;
	}

	private NotificationMessage.Email email(String subject, String heading, String body, String link,
			String actionLabel) {
		String url = frontendUrl + link;
		String html = """
				<!doctype html>
				<html lang="vi"><body style="margin:0;padding:24px;background:#f4f5f7;font-family:Arial,Helvetica,sans-serif;color:#1f2937">
				<table role="presentation" width="100%%" style="max-width:560px;margin:0 auto;background:#ffffff;border-radius:8px;padding:24px">
				<tr><td>
				<p style="margin:0 0 8px;font-size:13px;color:#6b7280">VMarket</p>
				<h1 style="margin:0 0 16px;font-size:20px">%s</h1>
				<p style="margin:0 0 24px;font-size:15px;line-height:1.6">%s</p>
				<a href="%s" style="display:inline-block;padding:10px 18px;background:#2563eb;color:#ffffff;text-decoration:none;border-radius:6px;font-size:14px">%s</a>
				<p style="margin:24px 0 0;font-size:12px;color:#9ca3af">Email tự động từ VMarket, vui lòng không trả lời.</p>
				</td></tr></table></body></html>
				""".formatted(HtmlUtils.htmlEscape(heading), HtmlUtils.htmlEscape(body), HtmlUtils.htmlEscape(url),
				HtmlUtils.htmlEscape(actionLabel));
		String text = heading + "\n\n" + body + "\n\n" + actionLabel + ": " + url
				+ "\n\n-- \nEmail tự động từ VMarket, vui lòng không trả lời.";
		return new NotificationMessage.Email(subject, html, text);
	}
}
