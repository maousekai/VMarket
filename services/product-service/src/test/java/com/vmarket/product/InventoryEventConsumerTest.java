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

import com.vmarket.events.EventEnvelope;
import com.vmarket.events.OrderPlaced;
import com.vmarket.events.StockItem;
import com.vmarket.events.StockReservationFailed;
import com.vmarket.product.dto.InventoryRequest;
import com.vmarket.product.event.OrderPlacedInventoryConsumer;
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
}
