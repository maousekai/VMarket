package com.vmarket.product;

import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.vmarket.events.EventPublisher;
import com.vmarket.events.EventType;
import com.vmarket.events.ProductCreated;
import com.vmarket.events.ProductDeleted;
import com.vmarket.events.ProductUpdated;
import com.vmarket.events.StockItem;
import com.vmarket.events.StockReleased;
import com.vmarket.events.StockReserved;
import com.vmarket.product.event.ProductEventPublisher;

@ExtendWith(MockitoExtension.class)
class ProductEventPublisherTest {
	@Mock EventPublisher eventPublisher;

	@Test
	void publishesEveryCatalogEventWithCorrectRoutingKey() {
		ProductEventPublisher publisher = new ProductEventPublisher(eventPublisher);
		ProductCreated created = new ProductCreated("p1", "s1", "Tên", BigDecimal.TEN, "ACTIVE", List.of());
		ProductUpdated updated = new ProductUpdated("p1", "s1", "Tên mới", BigDecimal.ONE, "HIDDEN", List.of());
		ProductDeleted deleted = new ProductDeleted("p1", "s1");
		StockReserved reserved = new StockReserved("o1", List.of(new StockItem("p1", "v1", 2)));
		StockReleased released = new StockReleased("o1", reserved.items());

		publisher.publishCreated(created);
		publisher.publishUpdated(updated);
		publisher.publishDeleted(deleted);
		publisher.publishStockReserved(reserved);
		publisher.publishStockReleased(released);

		verify(eventPublisher).publish(EventType.PRODUCT_CREATED, created);
		verify(eventPublisher).publish(EventType.PRODUCT_UPDATED, updated);
		verify(eventPublisher).publish(EventType.PRODUCT_DELETED, deleted);
		verify(eventPublisher).publish(EventType.STOCK_RESERVED, reserved);
		verify(eventPublisher).publish(EventType.STOCK_RELEASED, released);
	}
}
