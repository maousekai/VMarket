package com.vmarket.product;

import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.vmarket.events.EventType;
import com.vmarket.events.ProductCreated;
import com.vmarket.events.ProductDeleted;
import com.vmarket.events.ProductModerated;
import com.vmarket.events.ProductUpdated;
import com.vmarket.events.StockItem;
import com.vmarket.events.StockReleased;
import com.vmarket.events.StockReserved;
import com.vmarket.events.StockReservationFailed;
import com.vmarket.product.event.ProductEventPublisher;
import com.vmarket.product.event.CatalogOutbox;

@ExtendWith(MockitoExtension.class)
class ProductEventPublisherTest {
	@Mock CatalogOutbox outbox;

	@Test
	void publishesEveryCatalogEventWithCorrectRoutingKey() {
		ProductEventPublisher publisher = new ProductEventPublisher(outbox);
		ProductCreated created = new ProductCreated("p1", "s1", "Tên", BigDecimal.TEN, "ACTIVE", List.of());
		ProductUpdated updated = new ProductUpdated("p1", "s1", "Tên mới", BigDecimal.ONE, "HIDDEN", List.of());
		ProductDeleted deleted = new ProductDeleted("p1", "s1");
		StockReserved reserved = new StockReserved("o1", List.of(new StockItem("p1", "v1", 2)));
		StockReleased released = new StockReleased("o1", reserved.items());
		StockReservationFailed failed = new StockReservationFailed("o2", "INSUFFICIENT_STOCK", "Không đủ kho");
		ProductModerated moderated = new ProductModerated("p1", "s1", "seller1", true, "Vi phạm");

		publisher.publishCreated(created);
		publisher.publishUpdated(updated);
		publisher.publishDeleted(deleted);
		publisher.publishStockReserved(reserved);
		publisher.publishStockReleased(released);
		publisher.publishStockReservationFailed(failed);
		publisher.publishModerated(moderated);

		verify(outbox).enqueue(EventType.PRODUCT_CREATED, created);
		verify(outbox).enqueue(EventType.PRODUCT_UPDATED, updated);
		verify(outbox).enqueue(EventType.PRODUCT_DELETED, deleted);
		verify(outbox).enqueue(EventType.STOCK_RESERVED, reserved);
		verify(outbox).enqueue(EventType.STOCK_RELEASED, released);
		verify(outbox).enqueue(EventType.STOCK_RESERVATION_FAILED, failed);
		verify(outbox).enqueue(EventType.PRODUCT_MODERATED, moderated);
	}
}
