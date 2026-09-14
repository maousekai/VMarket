package com.vmarket.product.repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

import com.vmarket.product.model.Product;
import com.vmarket.product.model.ProductStatus;

@Repository
public class ProductCatalogQuery {
	private final MongoTemplate mongoTemplate;

	public ProductCatalogQuery(MongoTemplate mongoTemplate) {
		this.mongoTemplate = mongoTemplate;
	}

	public Page<Product> search(String keyword, List<String> categoryIds, BigDecimal minPrice, BigDecimal maxPrice,
			Double minRating, String shopId, String sort, int page, int size) {
		Criteria criteria = Criteria.where("status").is(ProductStatus.ACTIVE)
				.and("moderationRemoved").is(false).and("deletedAt").is(null);
		if (categoryIds != null && !categoryIds.isEmpty()) {
			criteria.and("categoryId").in(categoryIds);
		}
		if (keyword != null && !keyword.isBlank()) {
			criteria.and("name").regex(Pattern.compile(Pattern.quote(keyword.trim()), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
		}
		if (shopId != null && !shopId.isBlank()) {
			criteria.and("shopId").is(shopId);
		}
		if (minRating != null) {
			criteria.and("ratingAverage").gte(minRating);
		}
		if (minPrice != null || maxPrice != null) {
			Criteria price = Criteria.where("price");
			if (minPrice != null) price.gte(minPrice);
			if (maxPrice != null) price.lte(maxPrice);
			criteria.and("variants").elemMatch(price);
		}

		Query countQuery = new Query(criteria);
		long total = mongoTemplate.count(countQuery, Product.class);
		Sort sorting = switch (sort == null ? "NEWEST" : sort.toUpperCase()) {
			case "BEST_SELLING" -> Sort.by(Sort.Direction.DESC, "soldCount");
			case "PRICE_ASC" -> Sort.by(Sort.Direction.ASC, "variants.price");
			case "PRICE_DESC" -> Sort.by(Sort.Direction.DESC, "variants.price");
			default -> Sort.by(Sort.Direction.DESC, "createdAt");
		};
		PageRequest pageable = PageRequest.of(page, size, sorting);
		Query dataQuery = new Query(criteria).with(pageable);
		return new PageImpl<>(mongoTemplate.find(dataQuery, Product.class), pageable, total);
	}
}
