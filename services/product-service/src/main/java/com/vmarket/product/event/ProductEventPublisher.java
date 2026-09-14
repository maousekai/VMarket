package com.vmarket.product.event;

import org.springframework.stereotype.Service;

import com.vmarket.events.EventPublisher;
import com.vmarket.events.EventType;
import com.vmarket.events.ProductCreated;
import com.vmarket.events.ProductDeleted;
import com.vmarket.events.ProductUpdated;
import com.vmarket.events.StockReleased;
import com.vmarket.events.StockReserved;

/**
 * Cổng phát sự kiện sản phẩm của Product Catalog.
 *
 * <p>PBL6-39 — điểm tích hợp của Product Catalog với Event Bus. Khi triển khai
 * CRUD sản phẩm (FR-PROD-01/02) gọi các phương thức này ngay sau khi thay đổi
 * dữ liệu MongoDB để AI Search / Recommendation đồng bộ chỉ mục (FR-SRCH-04).
 */
@Service
public class ProductEventPublisher {

	private final EventPublisher eventPublisher;

	public ProductEventPublisher(EventPublisher eventPublisher) {
		this.eventPublisher = eventPublisher;
	}

	/** Phát sự kiện {@code ProductCreated} lên Event Bus. */
	public void publishCreated(ProductCreated payload) {
		eventPublisher.publish(EventType.PRODUCT_CREATED, payload);
	}

	/** Phát sự kiện {@code ProductUpdated}. */
	public void publishUpdated(ProductUpdated payload) {
		eventPublisher.publish(EventType.PRODUCT_UPDATED, payload);
	}

	public void publishDeleted(ProductDeleted payload) {
		eventPublisher.publish(EventType.PRODUCT_DELETED, payload);
	}

	public void publishStockReserved(StockReserved payload) {
		eventPublisher.publish(EventType.STOCK_RESERVED, payload);
	}

	public void publishStockReleased(StockReleased payload) {
		eventPublisher.publish(EventType.STOCK_RELEASED, payload);
	}
}
