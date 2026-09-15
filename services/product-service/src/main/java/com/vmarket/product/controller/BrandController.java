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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.vmarket.product.dto.BrandRequest;
import com.vmarket.product.dto.BrandResponse;
import com.vmarket.product.security.RequestIdentity;
import com.vmarket.product.service.BrandService;

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

	@PostMapping("/admin/query")
	public List<BrandResponse> adminList(
			@RequestHeader(value = "X-User-Roles", required = false) String roles) {
		identity.requireAdmin(roles);
		return service.list(true);
	}

	@PostMapping
	public ResponseEntity<BrandResponse> create(
			@RequestHeader(value = "X-User-Roles", required = false) String roles,
			@Valid @RequestBody BrandRequest request) {
		identity.requireAdmin(roles);
		return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request));
	}

	@PutMapping("/{id}")
	public BrandResponse update(@PathVariable String id,
			@RequestHeader(value = "X-User-Roles", required = false) String roles,
			@Valid @RequestBody BrandRequest request) {
		identity.requireAdmin(roles);
		return service.update(id, request);
	}

	@DeleteMapping("/{id}")
	public ResponseEntity<Void> delete(@PathVariable String id,
			@RequestHeader(value = "X-User-Roles", required = false) String roles) {
		identity.requireAdmin(roles);
		service.delete(id);
		return ResponseEntity.noContent().build();
	}
}
