package com.vmarket.product.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.vmarket.product.dto.CategoryRequest;
import com.vmarket.product.dto.CategoryResponse;
import com.vmarket.product.security.RequestIdentity;
import com.vmarket.product.service.CategoryService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/products/categories")
public class CategoryController {
	private final CategoryService service;
	private final RequestIdentity identity;

	public CategoryController(CategoryService service, RequestIdentity identity) {
		this.service = service;
		this.identity = identity;
	}

	@GetMapping
	public List<CategoryResponse> tree() {
		return service.tree(false);
	}

	@PostMapping("/admin/query")
	public List<CategoryResponse> adminTree(
			@RequestHeader(value = "X-User-Roles", required = false) String roles) {
		identity.requireAdmin(roles);
		return service.tree(true);
	}

	@PostMapping
	public ResponseEntity<CategoryResponse> create(
			@RequestHeader(value = "X-User-Roles", required = false) String roles,
			@Valid @RequestBody CategoryRequest request) {
		identity.requireAdmin(roles);
		return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request));
	}

	@PutMapping("/{id}")
	public CategoryResponse update(@PathVariable String id,
			@RequestHeader(value = "X-User-Roles", required = false) String roles,
			@Valid @RequestBody CategoryRequest request) {
		identity.requireAdmin(roles);
		return service.update(id, request);
	}

	@PatchMapping("/{id}/visibility")
	public CategoryResponse visibility(@PathVariable String id,
			@RequestHeader(value = "X-User-Roles", required = false) String roles,
			@RequestBody VisibilityRequest request) {
		identity.requireAdmin(roles);
		return service.setVisibility(id, request.active());
	}

	@DeleteMapping("/{id}")
	public ResponseEntity<Void> delete(@PathVariable String id,
			@RequestHeader(value = "X-User-Roles", required = false) String roles) {
		identity.requireAdmin(roles);
		service.delete(id);
		return ResponseEntity.noContent().build();
	}

	public record VisibilityRequest(boolean active) {
	}
}
