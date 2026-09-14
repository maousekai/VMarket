package com.vmarket.product.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.vmarket.events.ProductDeleted;
import com.vmarket.events.ProductModerated;
import com.vmarket.events.ProductModerationRequested;
import com.vmarket.product.dto.PageResponse;
import com.vmarket.product.dto.ProductDetailResponse;
import com.vmarket.product.dto.ProductRequest;
import com.vmarket.product.dto.ProductResponse;
import com.vmarket.product.exception.ApiException;
import com.vmarket.product.event.ProductEventPublisher;
import com.vmarket.product.event.ProductEventFactory;
import com.vmarket.product.model.Brand;
import com.vmarket.product.model.Category;
import com.vmarket.product.model.Product;
import com.vmarket.product.model.ProductStatus;
import com.vmarket.product.model.ProductVariant;
import com.vmarket.product.repository.ProductCatalogQuery;
import com.vmarket.product.repository.ProductRepository;

@Service
public class ProductCatalogService {
	private final ProductRepository repository;
	private final ProductCatalogQuery query;
	private final CategoryService categoryService;
	private final BrandService brandService;
	private final ProductMapper mapper;
	private final ProductEventPublisher publisher;
	private final ProductEventFactory eventFactory;
	private final ProductDerivedFields derivedFields;
	private final ShopAccessService shopAccessService;
	private final CatalogProjectionService projections;

	public ProductCatalogService(ProductRepository repository, ProductCatalogQuery query,
			CategoryService categoryService, BrandService brandService, ProductMapper mapper,
			ProductEventPublisher publisher, ProductEventFactory eventFactory,
			ProductDerivedFields derivedFields, ShopAccessService shopAccessService,
			CatalogProjectionService projections) {
		this.repository = repository;
		this.query = query;
		this.categoryService = categoryService;
		this.brandService = brandService;
		this.mapper = mapper;
		this.publisher = publisher;
		this.eventFactory = eventFactory;
		this.derivedFields = derivedFields;
		this.shopAccessService = shopAccessService;
		this.projections = projections;
	}

	@Transactional
	public ProductResponse create(String sellerId, ProductRequest request) {
		shopAccessService.requireActiveOwner(request.shopId().trim(), sellerId);
		validateReferences(request.categoryId(), request.brandId(), request.status());
		validateVariants(request.variants());
		Instant now = Instant.now();
		Product product = new Product();
		product.setShopId(request.shopId().trim());
		product.setSellerId(sellerId);
		product.setRatingAverage(0);
		product.setRatingCount(0);
		product.setSoldCount(0);
		product.setCreatedAt(now);
		applyRequest(product, request, Map.of());
		product.setUpdatedAt(now);
		Product saved = repository.save(product);
		publisher.publishCreated(eventFactory.created(saved));
		return mapper.toResponse(saved);
	}

	@Transactional
	public ProductResponse update(String id, String sellerId, ProductRequest request) {
		Product product = getOwned(id, sellerId);
		shopAccessService.requireActiveOwner(product.getShopId(), sellerId);
		if (!product.getShopId().equals(request.shopId().trim())) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "SHOP_IMMUTABLE", "Không thể chuyển sản phẩm sang gian hàng khác");
		}
		validateReferences(request.categoryId(), request.brandId(), request.status());
		validateVariants(request.variants());
		Map<String, ProductVariant> existing = new HashMap<>();
		product.getVariants().forEach(variant -> existing.put(variant.getId(), variant));
		for (ProductRequest.VariantRequest variant : request.variants()) {
			if (variant.id() != null && !variant.id().isBlank() && !existing.containsKey(variant.id())) {
				throw new ApiException(HttpStatus.BAD_REQUEST, "UNKNOWN_VARIANT",
						"Biến thể không thuộc sản phẩm: " + variant.id());
			}
		}
		ensureReservedVariantsAreKept(request, existing);
		applyRequest(product, request, existing);
		if (product.isModerationRemoved()) {
			product.setStatusBeforeModeration(request.status());
			product.setStatus(ProductStatus.HIDDEN);
			product.setModerationResubmittedAt(null);
		}
		product.setUpdatedAt(Instant.now());
		Product saved = repository.save(product);
		publisher.publishUpdated(eventFactory.updated(saved));
		return mapper.toResponse(saved);
	}

	@Transactional
	public void delete(String id, String sellerId) {
		Product product = getOwned(id, sellerId);
		if (product.getDeletedAt() != null) return;
		product.setStatus(ProductStatus.HIDDEN);
		product.setDeletedAt(Instant.now());
		product.setUpdatedAt(product.getDeletedAt());
		Product saved = repository.save(product);
		publisher.publishDeleted(new ProductDeleted(saved.getId(), saved.getShopId()));
	}

	@Transactional
	public ProductResponse moderate(String id, boolean removed, String reason) {
		Product product = getRequired(id);
		if (product.getDeletedAt() != null) {
			throw new ApiException(HttpStatus.CONFLICT, "PRODUCT_DELETED", "Sản phẩm đã bị người bán xóa");
		}
		if (removed) {
			if (reason == null || reason.isBlank()) {
				throw new ApiException(HttpStatus.BAD_REQUEST, "MODERATION_REASON_REQUIRED", "Phải nhập lý do gỡ sản phẩm");
			}
			if (!product.isModerationRemoved()) {
				ProductStatus desired = product.isShopSuspended() && product.getStatusBeforeShopSuspension() != null
						? product.getStatusBeforeShopSuspension() : product.getStatus();
				product.setStatusBeforeModeration(desired);
			}
			product.setModerationRemoved(true);
			product.setModerationReason(reason.trim());
			product.setModerationResubmittedAt(null);
			product.setStatus(ProductStatus.HIDDEN);
		} else {
			product.setModerationRemoved(false);
			product.setModerationReason(null);
			ProductStatus restoredStatus = product.getStatusBeforeModeration() == null
					? ProductStatus.DRAFT : product.getStatusBeforeModeration();
			if (product.isShopSuspended()) {
				product.setStatus(ProductStatus.HIDDEN);
				if (product.getStatusBeforeShopSuspension() == null) {
					product.setStatusBeforeShopSuspension(restoredStatus);
				}
			} else {
				product.setStatus(restoredStatus);
			}
			product.setStatusBeforeModeration(null);
			product.setModerationResubmittedAt(null);
		}
		product.setUpdatedAt(Instant.now());
		Product saved = repository.save(product);
		publisher.publishUpdated(eventFactory.updated(saved));
		publisher.publishModerated(new ProductModerated(saved.getId(), saved.getShopId(), saved.getSellerId(),
				removed, saved.getModerationReason()));
		return mapper.toResponse(saved);
	}

	@Transactional
	public ProductResponse resubmitModeration(String id, String sellerId) {
		Product product = getOwned(id, sellerId);
		shopAccessService.requireActiveOwner(product.getShopId(), sellerId);
		if (!product.isModerationRemoved()) {
			throw new ApiException(HttpStatus.CONFLICT, "PRODUCT_NOT_MODERATED",
					"Sản phẩm không ở trạng thái chờ chỉnh sửa sau kiểm duyệt");
		}
		product.setModerationResubmittedAt(Instant.now());
		product.setUpdatedAt(product.getModerationResubmittedAt());
		Product saved = repository.save(product);
		publisher.publishModerationRequested(new ProductModerationRequested(saved.getId(), saved.getShopId(),
				saved.getSellerId()));
		return mapper.toResponse(saved);
	}

	public PageResponse<ProductResponse> browse(String keyword, String categoryId, BigDecimal minPrice, BigDecimal maxPrice,
			Double minRating, String shopId, String sort, int page, int size) {
		if (minPrice != null && minPrice.signum() < 0 || maxPrice != null && maxPrice.signum() < 0) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PRICE_RANGE", "Khoảng giá không hợp lệ");
		}
		if (minPrice != null && maxPrice != null && minPrice.compareTo(maxPrice) > 0) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PRICE_RANGE", "Giá tối thiểu không được lớn hơn giá tối đa");
		}
		if (minRating != null && (minRating < 0 || minRating > 5)) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_RATING", "Điểm đánh giá phải nằm trong khoảng 0 đến 5");
		}
		String categoryFilter = categoryId == null || categoryId.isBlank() ? null : categoryId.trim();
		if (categoryFilter != null) categoryService.getPublicRequired(categoryFilter);
		Page<ProductResponse> result = query.search(keyword, categoryFilter, minPrice, maxPrice, minRating, shopId, sort,
				Math.max(page, 0), Math.min(Math.max(size, 1), 100)).map(mapper::toResponse);
		return PageResponse.from(result);
	}

	public ProductDetailResponse detail(String id) {
		Product product = getRequired(id);
		if (product.getDeletedAt() != null || product.getStatus() != ProductStatus.ACTIVE || product.isModerationRemoved()
				|| product.isShopSuspended() || !product.isCategoryVisible()) {
			throw new ApiException(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", "Không tìm thấy sản phẩm");
		}
		List<ProductResponse> similar = query.search(null, product.getCategoryId(), null, null, null, null,
				"BEST_SELLING", 0, 7).getContent().stream()
				.filter(item -> !item.getId().equals(product.getId())).limit(6).map(mapper::toResponse).toList();
		return new ProductDetailResponse(mapper.toResponse(product), similar);
	}

	public List<ProductResponse> sellerProducts(String sellerId) {
		return repository.findAllBySellerIdAndDeletedAtIsNullOrderByUpdatedAtDesc(sellerId).stream()
				.map(mapper::toResponse).toList();
	}

	public List<ProductResponse> adminProducts() {
		return repository.findAllByDeletedAtIsNullOrderByUpdatedAtDesc().stream().map(mapper::toResponse).toList();
	}

	public Product getRequired(String id) {
		return repository.findById(id)
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", "Không tìm thấy sản phẩm"));
	}

	private Product getOwned(String id, String sellerId) {
		Product product = getRequired(id);
		if (!sellerId.equals(product.getSellerId())) {
			throw new ApiException(HttpStatus.FORBIDDEN, "PRODUCT_NOT_OWNED", "Bạn không sở hữu sản phẩm này");
		}
		return product;
	}

	private void applyRequest(Product product, ProductRequest request, Map<String, ProductVariant> existing) {
		product.setName(request.name().trim());
		product.setDescription(request.description().trim());
		product.setImageUrls(new ArrayList<>(request.imageUrls()));
		product.setCategoryId(request.categoryId());
		projections.applyCategory(product);
		product.setBrandId(blankToNull(request.brandId()));
		product.setStatus(request.status());
		List<ProductVariant> variants = request.variants().stream().map(item -> {
			ProductVariant old = item.id() == null ? null : existing.get(item.id());
			long reserved = old == null ? 0 : old.getReservedStock();
			if (item.stock() < reserved) {
				throw new ApiException(HttpStatus.CONFLICT, "STOCK_BELOW_RESERVED",
						"Tồn kho không được nhỏ hơn số lượng đang tạm giữ");
			}
			return new ProductVariant(old == null ? UUID.randomUUID().toString() : old.getId(), item.sku().trim(),
					new HashMap<>(item.attributes()), item.price(), item.stock(), reserved,
					old == null ? 0 : old.getSoldCount());
		}).toList();
		product.setVariants(variants);
		derivedFields.refresh(product);
	}

	private void validateReferences(String categoryId, String brandId, ProductStatus status) {
		Category category = status == ProductStatus.ACTIVE
				? categoryService.getPublicRequired(categoryId) : categoryService.getRequired(categoryId);
		if (status == ProductStatus.ACTIVE && !category.isActive()) {
			throw new ApiException(HttpStatus.CONFLICT, "CATEGORY_INACTIVE", "Không thể bán sản phẩm trong danh mục đang ẩn");
		}
		if (brandId != null && !brandId.isBlank()) {
			Brand brand = brandService.getRequired(brandId);
			if (status == ProductStatus.ACTIVE && !brand.isActive()) {
				throw new ApiException(HttpStatus.CONFLICT, "BRAND_INACTIVE", "Không thể bán sản phẩm thuộc thương hiệu đang ẩn");
			}
		}
	}

	private void validateVariants(List<ProductRequest.VariantRequest> variants) {
		Set<String> skus = new HashSet<>();
		Set<String> ids = new HashSet<>();
		for (ProductRequest.VariantRequest variant : variants) {
			if (!skus.add(variant.sku().trim().toLowerCase())) {
				throw new ApiException(HttpStatus.BAD_REQUEST, "DUPLICATE_SKU", "SKU biến thể không được trùng trong cùng sản phẩm");
			}
			if (variant.id() != null && !variant.id().isBlank() && !ids.add(variant.id())) {
				throw new ApiException(HttpStatus.BAD_REQUEST, "DUPLICATE_VARIANT", "Mã biến thể bị trùng");
			}
		}
	}

	private void ensureReservedVariantsAreKept(ProductRequest request, Map<String, ProductVariant> existing) {
		Set<String> requestedIds = request.variants().stream().map(ProductRequest.VariantRequest::id)
				.filter(id -> id != null && !id.isBlank()).collect(java.util.stream.Collectors.toSet());
		for (ProductVariant variant : existing.values()) {
			if (variant.getReservedStock() > 0 && !requestedIds.contains(variant.getId())) {
				throw new ApiException(HttpStatus.CONFLICT, "VARIANT_RESERVED",
						"Không thể xóa biến thể đang có tồn kho tạm giữ");
			}
		}
	}

	private String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value;
	}
}
