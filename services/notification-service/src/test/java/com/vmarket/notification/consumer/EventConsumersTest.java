package com.vmarket.notification.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.vmarket.events.DeliveryAssigned;
import com.vmarket.events.DeliveryStatusChanged;
import com.vmarket.events.EventEnvelope;
import com.vmarket.events.OrderPlaced;
import com.vmarket.events.OrderStatusChanged;
import com.vmarket.events.PaymentSucceeded;
import com.vmarket.events.ProductModerated;
import com.vmarket.events.ShopApproved;
import com.vmarket.notification.client.InternalClients;
import com.vmarket.notification.client.OrderSnapshot;
import com.vmarket.notification.config.NotificationProperties;
import com.vmarket.notification.domain.NotificationType;
import com.vmarket.notification.domain.ShopOwner;
import com.vmarket.notification.repository.ShopOwnerRepository;
import com.vmarket.notification.service.NotificationDispatcher;
import com.vmarket.notification.service.NotificationMessage;
import com.vmarket.notification.service.NotificationTemplates;
import com.vmarket.notification.service.RecipientResolver;

/** Định tuyến sự kiện → đúng người nhận, đúng loại thông báo. */
class EventConsumersTest {

	private final NotificationDispatcher dispatcher = dispatcherMock();

	/** deliverAll chạy thật (gọi deliver trên mock) để verify từng người nhận. */
	private static NotificationDispatcher dispatcherMock() {
		NotificationDispatcher mock = mock(NotificationDispatcher.class);
		org.mockito.Mockito.doCallRealMethod().when(mock).deliverAll(any(), any());
		org.mockito.Mockito.doCallRealMethod().when(mock).deliverAll(any(), any(), any());
		return mock;
	}
	private final InternalClients clients = mock(InternalClients.class);
	private final ShopOwnerRepository shopOwners = mock(ShopOwnerRepository.class);
	private final RecipientResolver recipients = new RecipientResolver(clients, shopOwners, Clock.systemUTC());
	private final NotificationTemplates templates = new NotificationTemplates(new NotificationProperties());
	private final EventEnvelope envelope = new EventEnvelope("evt-1", "x", 1000L, Map.of());

	private OrderSnapshot order(String paymentMethod) {
		OrderSnapshot order = new OrderSnapshot("o1", "buyer-1", "PENDING", new BigDecimal("100000"), paymentMethod,
				List.of("shop-1", "shop-1", "shop-unknown"));
		when(clients.order("o1")).thenReturn(Optional.of(order));
		when(shopOwners.findById("shop-1"))
				.thenReturn(Optional.of(new ShopOwner("shop-1", "seller-1", "Gốm", 1L, null)));
		when(shopOwners.findById("shop-unknown")).thenReturn(Optional.empty());
		return order;
	}

	private static org.mockito.ArgumentMatcher<NotificationMessage> type(NotificationType type) {
		return m -> m != null && m.type() == type;
	}

	@Test
	void codOrderNotifiesBuyerAndEachKnownSellerOnce() {
		order("COD");

		new OrderPlacedConsumer(dispatcher, templates, recipients).handle(new OrderPlaced("o1", List.of()), envelope);

		verify(dispatcher).deliver(eq("buyer-1"), argThat(type(NotificationType.ORDER_PLACED)), eq(envelope));
		verify(dispatcher).deliver(eq("seller-1"), argThat(type(NotificationType.NEW_ORDER)), eq(envelope));
		verify(dispatcher, org.mockito.Mockito.times(2)).deliver(any(), any(), any());
	}

	@Test
	void onlinePaymentOrderWaitsForPaymentBeforeNotifyingSeller() {
		order("PAYOS");

		new OrderPlacedConsumer(dispatcher, templates, recipients).handle(new OrderPlaced("o1", List.of()), envelope);
		verify(dispatcher, never()).deliver(eq("seller-1"), any(), any());

		new PaymentSucceededConsumer(dispatcher, templates, recipients).handle(new PaymentSucceeded("o1"), envelope);
		verify(dispatcher).deliver(eq("buyer-1"), argThat(type(NotificationType.PAYMENT_SUCCEEDED)), eq(envelope));
		verify(dispatcher).deliver(eq("seller-1"), argThat(type(NotificationType.NEW_ORDER)), eq(envelope));
	}

	@Test
	void buyerPaymentEmailIsSent() {
		order("PAYOS");
		new PaymentSucceededConsumer(dispatcher, templates, recipients).handle(new PaymentSucceeded("o1"), envelope);

		ArgumentCaptor<NotificationMessage> captor = ArgumentCaptor.forClass(NotificationMessage.class);
		verify(dispatcher).deliver(eq("buyer-1"), captor.capture(), eq(envelope));
		assertThat(captor.getValue().email()).isNotNull();
	}

	@Test
	void cancelledOrderNotifiesBuyerAndSeller() {
		order("COD");

		new OrderStatusChangedConsumer(dispatcher, templates, recipients)
				.handle(new OrderStatusChanged("o1", "PROCESSING", "CANCELLED", "COD"), envelope);

		verify(dispatcher).deliver(eq("buyer-1"), argThat(type(NotificationType.ORDER_STATUS_CHANGED)), eq(envelope));
		verify(dispatcher).deliver(eq("seller-1"), argThat(type(NotificationType.ORDER_CANCELLED)), eq(envelope));
	}

	@Test
	void internalStatusesDoNotCallOrderService() {
		new OrderStatusChangedConsumer(dispatcher, templates, recipients)
				.handle(new OrderStatusChanged("o1", "PENDING", "WAITING_PAYMENT", "PAYOS"), envelope);
		new OrderStatusChangedConsumer(dispatcher, templates, recipients)
				.handle(new OrderStatusChanged("o1", "SHIPPED", "SHIPPED", "COD"), envelope);

		verifyNoInteractions(clients, dispatcher);
	}

	@Test
	void missingOrderIsSkippedWithoutError() {
		when(clients.order("o1")).thenReturn(Optional.empty());

		new OrderPlacedConsumer(dispatcher, templates, recipients).handle(new OrderPlaced("o1", List.of()), envelope);

		verifyNoInteractions(dispatcher);
	}

	@Test
	void onlyFailedDeliveryStatusNotifiesBuyer() {
		order("COD");
		DeliveryStatusChangedConsumer consumer = new DeliveryStatusChangedConsumer(dispatcher, templates, recipients);

		consumer.handle(new DeliveryStatusChanged("o1", "IN_TRANSIT", 3), envelope);
		verifyNoInteractions(dispatcher);

		consumer.handle(new DeliveryStatusChanged("o1", "FAILED", 4), envelope);
		verify(dispatcher).deliver(eq("buyer-1"), argThat(type(NotificationType.DELIVERY_FAILED)), eq(envelope));
	}

	@Test
	void deliveryAssignedNotifiesShipperAndSeller() {
		order("COD");

		new DeliveryAssignedConsumer(dispatcher, templates, recipients)
				.handle(new DeliveryAssigned("a1", "o1", "buyer-1", "shipper-1"), envelope);

		verify(dispatcher).deliver(eq("shipper-1"), argThat(type(NotificationType.DELIVERY_ASSIGNED)), eq(envelope));
		verify(dispatcher).deliver(eq("seller-1"), argThat(type(NotificationType.DELIVERY_ASSIGNED)), eq(envelope));
	}

	@Test
	void shopApprovedRemembersOwnerAndNotifiesWithEmail() {
		when(shopOwners.findById("shop-9")).thenReturn(Optional.empty());

		new ShopApprovedConsumer(dispatcher, templates, recipients)
				.handle(new ShopApproved("shop-9", "owner-9", "Tiệm Gốm", "admin-1", false), envelope);

		ArgumentCaptor<ShopOwner> saved = ArgumentCaptor.forClass(ShopOwner.class);
		verify(shopOwners).save(saved.capture());
		assertThat(saved.getValue().getOwnerId()).isEqualTo("owner-9");
		assertThat(saved.getValue().getEventTimestamp()).isEqualTo(1000L);

		ArgumentCaptor<NotificationMessage> message = ArgumentCaptor.forClass(NotificationMessage.class);
		verify(dispatcher).deliver(eq("owner-9"), message.capture(), eq(envelope));
		assertThat(message.getValue().type()).isEqualTo(NotificationType.SHOP_APPROVED);
		assertThat(message.getValue().email()).isNotNull();
	}

	@Test
	void olderShopEventDoesNotOverwriteProjection() {
		when(shopOwners.findById("shop-9"))
				.thenReturn(Optional.of(new ShopOwner("shop-9", "owner-new", "Mới", 5000L, null)));

		recipients.rememberShopOwner("shop-9", "owner-old", "Cũ", envelope);

		verify(shopOwners, never()).save(any());
	}

	@Test
	void unknownShopOwnerIsFetchedFromShopServiceAndCached() {
		order("COD");
		when(clients.shopOwner("shop-unknown"))
				.thenReturn(Optional.of(new com.vmarket.notification.client.ShopOwnerInfo("shop-unknown", "seller-2", "Mới")));

		new OrderPlacedConsumer(dispatcher, templates, recipients).handle(new OrderPlaced("o1", List.of()), envelope);

		verify(dispatcher).deliver(eq("seller-2"), argThat(type(NotificationType.NEW_ORDER)), eq(envelope));
		ArgumentCaptor<ShopOwner> cached = ArgumentCaptor.forClass(ShopOwner.class);
		verify(shopOwners).save(cached.capture());
		assertThat(cached.getValue().getShopId()).isEqualTo("shop-unknown");
		assertThat(cached.getValue().getOwnerId()).isEqualTo("seller-2");
		// eventTimestamp 0: sự kiện ShopApproved/Suspended sau này luôn ghi đè cache.
		assertThat(cached.getValue().getEventTimestamp()).isZero();
		// shop-1 đã có trong bản chiếu nên không gọi shop-service.
		verify(clients, never()).shopOwner("shop-1");
	}

	@Test
	void buyerEmailFailureDoesNotBlockSeller() {
		order("PAYOS");
		org.mockito.Mockito.doThrow(new IllegalStateException("Brevo down")).when(dispatcher)
				.deliver(eq("buyer-1"), any(), any());

		org.assertj.core.api.Assertions.assertThatThrownBy(() -> new PaymentSucceededConsumer(dispatcher, templates,
				recipients).handle(new PaymentSucceeded("o1"), envelope)).hasMessage("Brevo down");

		// Seller vẫn được xử lý; lỗi buyer ném ra SAU cùng để event bus retry.
		verify(dispatcher).deliver(eq("seller-1"), argThat(type(NotificationType.NEW_ORDER)), eq(envelope));
	}

	@Test
	void shopServiceOutageStillNotifiesBuyerAndKnownSellerThenRetries() {
		order("PAYOS");
		when(clients.shopOwner("shop-unknown"))
				.thenThrow(new org.springframework.web.client.ResourceAccessException("shop-service down"));

		org.assertj.core.api.Assertions.assertThatThrownBy(() -> new PaymentSucceededConsumer(dispatcher, templates,
				recipients).handle(new PaymentSucceeded("o1"), envelope)).hasMessage("shop-service down");

		// Buyer không phụ thuộc shop-service; seller đã biết chủ vẫn nhận. Lỗi ném ra sau để retry.
		verify(dispatcher).deliver(eq("buyer-1"), argThat(type(NotificationType.PAYMENT_SUCCEEDED)), eq(envelope));
		verify(dispatcher).deliver(eq("seller-1"), argThat(type(NotificationType.NEW_ORDER)), eq(envelope));
	}

	@Test
	void productModeratedGoesStraightToSeller() {
		new ProductModeratedConsumer(dispatcher, templates)
				.handle(new ProductModerated("p1", "shop-1", "seller-1", true, "Hàng giả"), envelope);

		verify(dispatcher).deliver(eq("seller-1"), argThat(type(NotificationType.PRODUCT_REMOVED)), eq(envelope));
		verifyNoInteractions(clients);
	}
}
