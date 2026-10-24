package com.vmarket.product.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.vmarket.product.config.OpenApiConfig;
import com.vmarket.product.dto.BrandRequest;
import com.vmarket.product.dto.BrandResponse;
import com.vmarket.product.security.RequestIdentity;
import com.vmarket.product.service.BrandService;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/products/brands")
public class BrandController {
	private final BrandService service;
	private final RequestIdentity identity;

	public BrandController(BrandService service, RequestIdentity identity) {
		this.service = service;
		this.identity = identity;
	}

	@GetMapping
	public List<BrandResponse> list() {
		return service.list(false);
	}

	@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
	@PostMapping("/admin/query")
	public List<BrandResponse> adminList() {
		identity.requireAdmin();
		return service.list(true);
	}

	@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
	@PostMapping
	public ResponseEntity<BrandResponse> create(@Valid @RequestBody BrandRequest request) {
		identity.requireAdmin();
		return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request));
	}

	@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
	@PutMapping("/{id}")
	public BrandResponse update(@PathVariable String id, @Valid @RequestBody BrandRequest request) {
		identity.requireAdmin();
		return service.update(id, request);
	}

	@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
	@DeleteMapping("/{id}")
	public ResponseEntity<Void> delete(@PathVariable String id) {
		identity.requireAdmin();
		service.delete(id);
		return ResponseEntity.noContent().build();
	}
}
