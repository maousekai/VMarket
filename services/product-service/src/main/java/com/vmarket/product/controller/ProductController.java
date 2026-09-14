package com.vmarket.product.controller;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.vmarket.product.dto.ModerationRequest;
import com.vmarket.product.dto.PageResponse;
import com.vmarket.product.dto.ProductDetailResponse;
import com.vmarket.product.dto.ProductRequest;
import com.vmarket.product.dto.ProductResponse;
import com.vmarket.product.security.RequestIdentity;
import com.vmarket.product.service.ProductCatalogService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

@Validated
@RestController
@RequestMapping("/api/products")
public class ProductController {
	private final ProductCatalogService service;
	private final RequestIdentity identity;

	public ProductController(ProductCatalogService service, RequestIdentity identity) {
		this.service = service;
		this.identity = identity;
	}

	@GetMapping
	public PageResponse<ProductResponse> browse(
			@RequestParam(required = false, name = "q") String keyword,
			@RequestParam(required = false) String categoryId,
			@RequestParam(required = false) BigDecimal minPrice,
			@RequestParam(required = false) BigDecimal maxPrice,
			@RequestParam(required = false) Double minRating,
			@RequestParam(required = false) String shopId,
			@RequestParam(defaultValue = "NEWEST") String sort,
			@RequestParam(defaultValue = "0") @Min(0) int page,
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
		return service.browse(keyword, categoryId, minPrice, maxPrice, minRating, shopId, sort, page, size);
	}

	@GetMapping("/{id}")
	public ProductDetailResponse detail(@PathVariable String id) {
		return service.detail(id);
	}

	@PostMapping
	public ResponseEntity<ProductResponse> create(
			@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-User-Roles", required = false) String roles,
			@Valid @RequestBody ProductRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED).body(service.create(identity.requireSeller(userId, roles), request));
	}

	@PutMapping("/{id}")
	public ProductResponse update(@PathVariable String id,
			@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-User-Roles", required = false) String roles,
			@Valid @RequestBody ProductRequest request) {
		return service.update(id, identity.requireSeller(userId, roles), request);
	}

	@DeleteMapping("/{id}")
	public ResponseEntity<Void> delete(@PathVariable String id,
			@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-User-Roles", required = false) String roles) {
		service.delete(id, identity.requireSeller(userId, roles));
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/seller/query")
	public List<ProductResponse> sellerProducts(
			@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-User-Roles", required = false) String roles) {
		return service.sellerProducts(identity.requireSeller(userId, roles));
	}

	@PostMapping("/admin/query")
	public List<ProductResponse> adminProducts(
			@RequestHeader(value = "X-User-Roles", required = false) String roles) {
		identity.requireAdmin(roles);
		return service.adminProducts();
	}

	@PatchMapping("/{id}/moderation")
	public ProductResponse moderate(@PathVariable String id,
			@RequestHeader(value = "X-User-Roles", required = false) String roles,
			@Valid @RequestBody ModerationRequest request) {
		identity.requireAdmin(roles);
		return service.moderate(id, request.removed(), request.reason());
	}

	@PostMapping("/{id}/moderation/resubmit")
	public ProductResponse resubmitModeration(@PathVariable String id,
			@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-User-Roles", required = false) String roles) {
		return service.resubmitModeration(id, identity.requireSeller(userId, roles));
	}
}
