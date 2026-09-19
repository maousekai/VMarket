package com.vmarket.product.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.vmarket.product.dto.ShopAccessSyncRequest;
import com.vmarket.product.security.InternalApiKeyGuard;
import com.vmarket.product.service.ShopAccessService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/products/internal/shop-access")
public class ShopAccessSyncController {
	private final ShopAccessService service;
	private final InternalApiKeyGuard apiKeyGuard;

	public ShopAccessSyncController(ShopAccessService service, InternalApiKeyGuard apiKeyGuard) {
		this.service = service;
		this.apiKeyGuard = apiKeyGuard;
	}

	@PostMapping("/reconcile")
	public ResponseEntity<Void> reconcile(
			@RequestHeader(value = "X-Internal-Api-Key", required = false) String apiKey,
			@Valid @RequestBody ShopAccessSyncRequest request) {
		apiKeyGuard.requireValid(apiKey);
		request.shops().forEach(shop -> service.reconcile(shop.shopId(), shop.sellerId(),
				shop.active(), shop.updatedAt()));
		return ResponseEntity.noContent().build();
	}
}
