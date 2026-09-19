package com.vmarket.product.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.vmarket.product.event.ProductEventFactory;
import com.vmarket.product.event.ProductEventPublisher;
import com.vmarket.product.model.Product;
import com.vmarket.product.repository.ProductRepository;

/** Ghi một batch nhỏ Product và outbox trong cùng Mongo transaction. */
@Service
public class CatalogProjectionBatchWriter {
	private final ProductRepository repository;
	private final ProductEventPublisher publisher;
	private final ProductEventFactory eventFactory;

	public CatalogProjectionBatchWriter(ProductRepository repository, ProductEventPublisher publisher,
			ProductEventFactory eventFactory) {
		this.repository = repository;
		this.publisher = publisher;
		this.eventFactory = eventFactory;
	}

	@Transactional
	public void saveAndPublish(List<Product> products) {
		if (products.isEmpty()) return;
		repository.saveAll(products).forEach(product -> publisher.publishUpdated(eventFactory.updated(product)));
	}
}
