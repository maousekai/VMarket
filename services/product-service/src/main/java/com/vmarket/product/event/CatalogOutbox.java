package com.vmarket.product.event;

import org.springframework.stereotype.Service;

import com.vmarket.product.model.OutboxEvent;
import com.vmarket.product.repository.OutboxEventRepository;

@Service
public class CatalogOutbox {
	private final OutboxEventRepository repository;

	public CatalogOutbox(OutboxEventRepository repository) {
		this.repository = repository;
	}

	public void enqueue(String eventType, Object payload) {
		repository.save(new OutboxEvent(eventType, payload));
	}
}
