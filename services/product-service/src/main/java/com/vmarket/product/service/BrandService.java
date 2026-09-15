package com.vmarket.product.service;

import java.time.Instant;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.vmarket.product.dto.BrandRequest;
import com.vmarket.product.dto.BrandResponse;
import com.vmarket.product.exception.ApiException;
import com.vmarket.product.model.Brand;
import com.vmarket.product.repository.BrandRepository;
import com.vmarket.product.repository.ProductRepository;

@Service
public class BrandService {
	private final BrandRepository repository;
	private final ProductRepository productRepository;
	private final SlugService slugService;

	public BrandService(BrandRepository repository, ProductRepository productRepository, SlugService slugService) {
		this.repository = repository;
		this.productRepository = productRepository;
		this.slugService = slugService;
	}

	public BrandResponse create(BrandRequest request) {
		String slug = slugService.slugify(request.name());
		if (repository.existsBySlug(slug)) throw conflict();
		Instant now = Instant.now();
		return toResponse(repository.save(new Brand(null, request.name().trim(), slug, request.active(), now, now)));
	}

	public BrandResponse update(String id, BrandRequest request) {
		Brand brand = getRequired(id);
		String slug = slugService.slugify(request.name());
		if (repository.existsBySlugAndIdNot(slug, id)) throw conflict();
		brand.setName(request.name().trim());
		brand.setSlug(slug);
		brand.setActive(request.active());
		brand.setUpdatedAt(Instant.now());
		return toResponse(repository.save(brand));
	}

	public void delete(String id) {
		Brand brand = getRequired(id);
		if (productRepository.existsByBrandIdAndDeletedAtIsNull(id)) {
			throw new ApiException(HttpStatus.CONFLICT, "BRAND_IN_USE", "Không thể xóa thương hiệu đang được sử dụng");
		}
		repository.delete(brand);
	}

	public List<BrandResponse> list(boolean includeInactive) {
		return repository.findAllByOrderByNameAsc().stream().filter(brand -> includeInactive || brand.isActive())
				.map(this::toResponse).toList();
	}

	public Brand getRequired(String id) {
		return repository.findById(id)
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "BRAND_NOT_FOUND", "Không tìm thấy thương hiệu"));
	}

	private BrandResponse toResponse(Brand brand) {
		return new BrandResponse(brand.getId(), brand.getName(), brand.getSlug(), brand.isActive());
	}

	private ApiException conflict() {
		return new ApiException(HttpStatus.CONFLICT, "BRAND_CONFLICT", "Thương hiệu có tên tương ứng đã tồn tại");
	}
}
