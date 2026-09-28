package com.vmarket.product.controller;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.vmarket.product.dto.InventoryRequest;
import com.vmarket.product.dto.InventoryResponse;
import com.vmarket.product.security.InternalApiKeyGuard;
import com.vmarket.product.service.InventoryService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/products/inventory/reservations")
public class InventoryController {
	private final InventoryService service;
	private final InternalApiKeyGuard apiKeyGuard;

	public InventoryController(InventoryService service, InternalApiKeyGuard apiKeyGuard) {
		this.service = service;
		this.apiKeyGuard = apiKeyGuard;
	}

	@PostMapping
	public InventoryResponse reserve(
			@RequestHeader(value = "X-Internal-Api-Key", required = false) String apiKey,
			@Valid @RequestBody InventoryRequest request) {
		apiKeyGuard.requireValid(apiKey);
		return service.reserve(request);
	}

	@PostMapping("/{orderId}/confirm")
	public InventoryResponse confirm(@PathVariable String orderId,
			@RequestHeader(value = "X-Internal-Api-Key", required = false) String apiKey) {
		apiKeyGuard.requireValid(apiKey);
		return service.confirm(orderId);
	}

	@DeleteMapping("/{orderId}")
	public InventoryResponse release(@PathVariable String orderId,
			@RequestHeader(value = "X-Internal-Api-Key", required = false) String apiKey) {
		apiKeyGuard.requireValid(apiKey);
		return service.release(orderId);
	}
}
