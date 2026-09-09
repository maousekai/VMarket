package com.vmarket.product.event;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.vmarket.events.ProductCreated;

/**
 * PBL6-39 — endpoint DEMO chứng minh luồng publish product 'ProductCreated' từ
 * Product Catalog. KHÔNG phải API sản phẩm chính thức (sẽ được xây ở ticket
 * Product Catalog riêng); endpoint này chỉ để kiểm thử end-to-end luồng
 * Product(Java) → AI Search(Python) qua RabbitMQ.
 *
 * <p>Gọi: {@code POST /api/products/_demo/product-created}
 */
@RestController
@RequestMapping("/api/products")
public class ProductCreatedEventDemoController {

	private final ProductEventPublisher publisher;

	public ProductCreatedEventDemoController(ProductEventPublisher publisher) {
		this.publisher = publisher;
	}

	@PostMapping("/_demo/product-created")
	public ResponseEntity<Map<String, String>> demoPublish() {
		String productId = "demo-" + UUID.randomUUID();
		ProductCreated payload = new ProductCreated(
				productId,
				"demo-shop",
				"Sản phẩm demo PBL6-39",
				new BigDecimal("150000"),
				"ACTIVE",
				List.of("https://cdn.vmarket/demo-product.jpg"));

		publisher.publishCreated(payload);

		return ResponseEntity.status(HttpStatus.ACCEPTED)
				.body(Map.of("event", "ProductCreated", "productId", productId));
	}
}