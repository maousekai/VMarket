package com.vmarket.product.event;

import org.springframework.stereotype.Service;

import com.vmarket.events.EventType;
import com.vmarket.events.ProductCreated;
import com.vmarket.events.ProductDeleted;
import com.vmarket.events.ProductModerated;
import com.vmarket.events.ProductModerationRequested;
import com.vmarket.events.ProductUpdated;
import com.vmarket.events.StockReservationFailed;
import com.vmarket.events.StockReleased;
import com.vmarket.events.StockReserved;

/**
 * Cổng phát sự kiện sản phẩm của Product Catalog.
 *
 * <p>Các phương thức ghi event vào transactional outbox; worker tách biệt sẽ
 * chuyển chúng lên RabbitMQ để không tạo khoảng trống giữa MongoDB và Event Bus.
 */
@Service
public class ProductEventPublisher {

	private final CatalogOutbox outbox;

	public ProductEventPublisher(CatalogOutbox outbox) {
		this.outbox = outbox;
	}

	/** Phát sự kiện {@code ProductCreated} lên Event Bus. */
	public void publishCreated(ProductCreated payload) {
		outbox.enqueue(EventType.PRODUCT_CREATED, payload);
	}

	/** Phát sự kiện {@code ProductUpdated}. */
	public void publishUpdated(ProductUpdated payload) {
		outbox.enqueue(EventType.PRODUCT_UPDATED, payload);
	}

	public void publishDeleted(ProductDeleted payload) {
		outbox.enqueue(EventType.PRODUCT_DELETED, payload);
	}

	public void publishStockReserved(StockReserved payload) {
		outbox.enqueue(EventType.STOCK_RESERVED, payload);
	}

	public void publishStockReleased(StockReleased payload) {
		outbox.enqueue(EventType.STOCK_RELEASED, payload);
	}

	public void publishStockReservationFailed(StockReservationFailed payload) {
		outbox.enqueue(EventType.STOCK_RESERVATION_FAILED, payload);
	}

	public void publishModerated(ProductModerated payload) {
		outbox.enqueue(EventType.PRODUCT_MODERATED, payload);
	}

	public void publishModerationRequested(ProductModerationRequested payload) {
		outbox.enqueue(EventType.PRODUCT_MODERATION_REQUESTED, payload);
	}
}
