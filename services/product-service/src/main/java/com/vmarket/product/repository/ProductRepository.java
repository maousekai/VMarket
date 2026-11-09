package com.vmarket.product.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.mongodb.repository.MongoRepository;

import com.vmarket.product.model.Product;

public interface ProductRepository extends MongoRepository<Product, String> {
	Optional<Product> findByCreationKey(String creationKey);
	List<Product> findAllBySellerIdAndDeletedAtIsNullOrderByUpdatedAtDesc(String sellerId);
	List<Product> findTop100ByShopIdAndDeletedAtIsNullAndIdGreaterThanOrderByIdAsc(String shopId, String afterId);
	List<Product> findTop100ByCategoryIdAndDeletedAtIsNullAndIdGreaterThanOrderByIdAsc(
			String categoryId, String afterId);
	List<Product> findTop100ByDeletedAtIsNullAndIdGreaterThanOrderByIdAsc(String afterId);
	List<Product> findAllByDeletedAtIsNullOrderByUpdatedAtDesc();
	boolean existsByCategoryIdAndDeletedAtIsNull(String categoryId);
	boolean existsByBrandIdAndDeletedAtIsNull(String brandId);
	/** Ảnh còn được sản phẩm (chưa xoá) của seller tham chiếu — không được xoá khỏi kho. */
	boolean existsBySellerIdAndImageUrlsAndDeletedAtIsNull(String sellerId, String imageUrl);
}
