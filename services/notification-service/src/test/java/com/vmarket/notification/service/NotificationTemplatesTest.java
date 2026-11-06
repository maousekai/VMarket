package com.vmarket.notification.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.vmarket.notification.client.OrderSnapshot;
import com.vmarket.notification.config.NotificationProperties;
import com.vmarket.notification.domain.NotificationType;

class NotificationTemplatesTest {

	private final NotificationTemplates templates = templates("http://localhost:5173/");

	private static NotificationTemplates templates(String frontendUrl) {
		NotificationProperties properties = new NotificationProperties();
		properties.setFrontendUrl(frontendUrl);
		return new NotificationTemplates(properties);
	}

	private static OrderSnapshot order(String paymentMethod) {
		return new OrderSnapshot("o1", "buyer-1", "PENDING", new BigDecimal("1250000.00"), paymentMethod,
				List.of(new OrderSnapshot.Item("p1", "shop-1", 1)));
	}

	@Test
	void orderPlacedHasEmailWithAbsoluteLinkAndVndAmount() {
		NotificationMessage message = templates.orderPlaced(order("COD"));

		assertThat(message.type()).isEqualTo(NotificationType.ORDER_PLACED);
		assertThat(message.body()).contains("1.250.000 ₫").contains("khi nhận hàng");
		assertThat(message.email()).isNotNull();
		assertThat(message.email().subject()).isEqualTo("Xác nhận đặt hàng #o1");
		assertThat(message.email().html()).contains("href=\"http://localhost:5173/orders/o1\"");
		assertThat(message.email().text()).contains("http://localhost:5173/orders/o1");
	}

	@Test
	void onlinePaymentOrderAsksBuyerToPay() {
		assertThat(templates.orderPlaced(order("PAYOS")).body()).contains("hoàn tất thanh toán");
	}

	@Test
	void dynamicTextIsHtmlEscapedInEmail() {
		NotificationMessage message = templates.shopSuspended("s1", "<b>Shop</b>", "Bán <script>x</script>");

		assertThat(message.email().html()).doesNotContain("<script>").doesNotContain("<b>Shop</b>")
				.contains("&lt;script&gt;");
		// In-app hiển thị bằng text node ở client nên giữ nguyên.
		assertThat(message.body()).contains("Bán <script>x</script>");
	}

	@Test
	void statusesWithoutBuyerMessageReturnNull() {
		assertThat(templates.orderStatusChanged("o1", "WAITING_PAYMENT")).isNull();
		assertThat(templates.orderStatusChanged("o1", "PENDING")).isNull();
		NotificationMessage shipped = templates.orderStatusChanged("o1", "SHIPPED");
		assertThat(shipped.title()).isEqualTo("Đơn hàng đang giao");
		assertThat(shipped.email().subject()).isEqualTo("Cập nhật đơn hàng #o1: Đang giao");
	}

	@Test
	void reinstatedShopUsesDifferentType() {
		assertThat(templates.shopApproved("s1", "Gốm", false).type()).isEqualTo(NotificationType.SHOP_APPROVED);
		assertThat(templates.shopApproved("s1", "Gốm", true).type()).isEqualTo(NotificationType.SHOP_REINSTATED);
	}

	@Test
	void moneyFormatsVnd() {
		assertThat(NotificationTemplates.money(new BigDecimal("0"))).isEqualTo("0 ₫");
		assertThat(NotificationTemplates.money(new BigDecimal("25000"))).isEqualTo("25.000 ₫");
	}

	@Test
	void missingAmountIsOmittedInsteadOfZero() {
		OrderSnapshot noAmount = new OrderSnapshot("o1", "buyer-1", "PENDING", null, "COD", List.of());

		assertThat(templates.orderPlaced(noAmount).body()).doesNotContain("₫").startsWith("Đơn hàng o1 đã được đặt");
		assertThat(templates.paymentSucceeded(noAmount).body()).startsWith("Thanh toán cho đơn hàng o1");
	}

	@Test
	void paymentFailureCodesAreTranslatedAndUnknownCodesHidden() {
		assertThat(templates.paymentFailed("o1", "EXPIRED").body()).endsWith(": phiên thanh toán đã hết hạn.");
		assertThat(templates.paymentFailed("o1", "CANCELLED").body()).contains("giao dịch đã bị huỷ");
		assertThat(templates.paymentFailed("o1", "RETRYABLE").body()).contains("thử lại sau");
		assertThat(templates.paymentFailed("o1", "PAYOS_WEIRD_STATE").body())
				.isEqualTo("Thanh toán cho đơn hàng o1 không thành công.");
		assertThat(templates.paymentFailed("o1", null).body()).doesNotContain("null");
	}
}
