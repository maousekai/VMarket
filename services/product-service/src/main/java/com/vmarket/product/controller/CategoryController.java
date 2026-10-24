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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.vmarket.product.config.OpenApiConfig;
import com.vmarket.product.dto.CategoryRequest;
import com.vmarket.product.dto.CategoryResponse;
import com.vmarket.product.security.RequestIdentity;
import com.vmarket.product.service.CategoryService;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
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

	@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
	@PostMapping("/admin/query")
	public List<CategoryResponse> adminTree() {
		identity.requireAdmin();
		return service.tree(true);
	}

	@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
	@PostMapping
	public ResponseEntity<CategoryResponse> create(@Valid @RequestBody CategoryRequest request) {
		identity.requireAdmin();
		return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request));
	}

	@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
	@PutMapping("/{id}")
	public CategoryResponse update(@PathVariable String id, @Valid @RequestBody CategoryRequest request) {
		identity.requireAdmin();
		return service.update(id, request);
	}

	@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
	@PatchMapping("/{id}/visibility")
	public CategoryResponse visibility(@PathVariable String id, @RequestBody VisibilityRequest request) {
		identity.requireAdmin();
		return service.setVisibility(id, request.active());
	}

	@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
	@DeleteMapping("/{id}")
	public ResponseEntity<Void> delete(@PathVariable String id) {
		identity.requireAdmin();
		service.delete(id);
		return ResponseEntity.noContent().build();
	}

	public record VisibilityRequest(boolean active) {
	}
}
