package com.vmarket.product;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.dao.DuplicateKeyException;

import com.vmarket.events.EventEnvelope;
import com.vmarket.events.OrderPlaced;
import com.vmarket.events.OrderStatusChanged;
import com.vmarket.events.PaymentSucceeded;
import com.vmarket.events.ReturnResolved;
import com.vmarket.events.StockItem;
import com.vmarket.events.StockReservationFailed;
import com.vmarket.product.dto.InventoryRequest;
import com.vmarket.product.event.OrderPlacedInventoryConsumer;
import com.vmarket.product.event.OrderStatusChangedInventoryConsumer;
import com.vmarket.product.event.PaymentSucceededInventoryConsumer;
import com.vmarket.product.event.ReturnResolvedInventoryConsumer;
import com.vmarket.product.event.ProductEventPublisher;
import com.vmarket.product.exception.ApiException;
import com.vmarket.product.service.InventoryService;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class InventoryEventConsumerTest {
	@Mock InventoryService inventoryService;
	@Mock ProductEventPublisher publisher;

	@Test
	void orderPlacedMapsSharedStockItemsToReservationRequest() {
		OrderPlacedInventoryConsumer consumer = new OrderPlacedInventoryConsumer(inventoryService, publisher);

		consumer.handle(new OrderPlaced("order-1", List.of(new StockItem("product-1", "variant-1", 2))),
				new EventEnvelope("event-1", "OrderPlaced", 0, null));

		ArgumentCaptor<InventoryRequest> request = ArgumentCaptor.forClass(InventoryRequest.class);
		verify(inventoryService).reserve(request.capture());
		assertThat(request.getValue().orderId()).isEqualTo("order-1");
		assertThat(request.getValue().items()).containsExactly(
				new InventoryRequest.InventoryItem("product-1", "variant-1", 2));
	}

	@Test
	void businessReservationFailureIsReportedBackToOrderFlow() {
		doThrow(new ApiException(HttpStatus.CONFLICT, "INSUFFICIENT_STOCK", "Không đủ tồn kho"))
				.when(inventoryService).reserve(any());
		OrderPlacedInventoryConsumer consumer = new OrderPlacedInventoryConsumer(inventoryService, publisher);

		consumer.handle(new OrderPlaced("order-1", List.of(new StockItem("product-1", "variant-1", 20))),
				new EventEnvelope("event-1", "OrderPlaced", 0, null));

		ArgumentCaptor<StockReservationFailed> failed = ArgumentCaptor.forClass(StockReservationFailed.class);
		verify(publisher).publishStockReservationFailed(failed.capture());
		assertThat(failed.getValue().code()).isEqualTo("INSUFFICIENT_STOCK");
	}

	@Test
	void orderStatusUsesSrsContractForCodConfirmationAndCancellation() {
		OrderStatusChangedInventoryConsumer consumer = new OrderStatusChangedInventoryConsumer(inventoryService);

		consumer.handle(new OrderStatusChanged("order-1", "PENDING_CONFIRMATION", "PREPARING", "COD"),
				new EventEnvelope("event-1", "OrderStatusChanged", 0, null));
		consumer.handle(new OrderStatusChanged("order-1", "PREPARING", "CANCELLED", "COD"),
				new EventEnvelope("event-2", "OrderStatusChanged", 0, null));

		verify(inventoryService).confirmOrDefer("order-1");
		verify(inventoryService).releaseOrDefer("order-1");
	}

	@Test
	void acceptedReturnRestocksReturnedItems() {
		ReturnResolvedInventoryConsumer consumer = new ReturnResolvedInventoryConsumer(inventoryService);
		List<StockItem> items = List.of(new StockItem("product-1", "variant-1", 1));

		consumer.handle(new ReturnResolved("return-1", "order-1", true, items),
				new EventEnvelope("event-1", "ReturnResolved", 0, null));

		verify(inventoryService).restockReturn("return-1", "order-1", items);
	}

	@Test
	void concurrentDuplicateOrderDeliveryIsAcknowledgedAfterWinnerCommits() {
		doThrow(new DuplicateKeyException("same order")).when(inventoryService).reserve(any());
		org.mockito.Mockito.when(inventoryService.hasReservation(any())).thenReturn(true);
		new OrderPlacedInventoryConsumer(inventoryService, publisher).handle(
				new OrderPlaced("order-1", List.of(new StockItem("product-1", "variant-1", 2))),
				new EventEnvelope("event-1", "OrderPlaced", 0, null));
		verify(publisher, org.mockito.Mockito.never()).publishStockReservationFailed(any());
	}

	@Test
	void concurrentDuplicateReturnDeliveryIsAcknowledgedAfterWinnerCommits() {
		List<StockItem> items = List.of(new StockItem("product-1", "variant-1", 1));
		doThrow(new DuplicateKeyException("same return")).when(inventoryService)
				.restockReturn("return-1", "order-1", items);
		org.mockito.Mockito.when(inventoryService.isReturnRestocked("return-1")).thenReturn(true);
		new ReturnResolvedInventoryConsumer(inventoryService).handle(
				new ReturnResolved("return-1", "order-1", true, items),
				new EventEnvelope("event-1", "ReturnResolved", 0, null));
	}

	@Test
	void concurrentEarlyPaymentDeliveryKeepsOnePendingConfirmation() {
		doThrow(new DuplicateKeyException("same order")).when(inventoryService).confirmOrDefer("order-1");
		org.mockito.Mockito.when(inventoryService.isConfirmedOrPending("order-1")).thenReturn(true);
		new PaymentSucceededInventoryConsumer(inventoryService).handle(new PaymentSucceeded("order-1"),
				new EventEnvelope("event-1", "PaymentSucceeded", 0, null));
	}
}
