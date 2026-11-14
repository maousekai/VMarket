package com.vmarket.product.repository;

import java.text.Normalizer;
import java.util.ArrayList;
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
import com.vmarket.product.model.ShopCatalogAccess;

@Repository
public class ProductCatalogQuery {
	private final MongoTemplate mongoTemplate;

	public ProductCatalogQuery(MongoTemplate mongoTemplate) {
		this.mongoTemplate = mongoTemplate;
	}

	public Page<Product> search(String keyword, String categoryId, Long minPrice, Long maxPrice,
			Double minRating, String shopId, String sort, int page, int size) {
		Criteria criteria = Criteria.where("status").is(ProductStatus.ACTIVE)
				.and("moderationRemoved").is(false).and("shopSuspended").is(false)
				.and("categoryVisible").is(true).and("deletedAt").is(null);
		if (categoryId != null && !categoryId.isBlank()) {
			criteria.and("categoryPath").is(categoryId);
		}
		if (keyword != null && !keyword.isBlank()) {
			// NFC: bo go tieng Viet co the gui "a" + dau to hop; ten shop luu dang dung san.
			String normalized = Normalizer.normalize(keyword.trim(), Normalizer.Form.NFC);
			Pattern pattern = Pattern.compile(Pattern.quote(normalized),
					Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
			// Khớp tên sản phẩm HOẶC tên gian hàng (gõ tên shop ra toàn bộ sản phẩm của shop đó).
			List<String> matchingShops = mongoTemplate.find(
					new Query(Criteria.where("shopName").regex(pattern)), ShopCatalogAccess.class)
					.stream().map(ShopCatalogAccess::getShopId).toList();
			List<Criteria> anyOf = new ArrayList<>();
			anyOf.add(Criteria.where("name").regex(pattern));
			if (!matchingShops.isEmpty()) anyOf.add(Criteria.where("shopId").in(matchingShops));
			criteria.orOperator(anyOf.toArray(Criteria[]::new));
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
			case "PRICE_ASC" -> Sort.by(Sort.Direction.ASC, "minPrice");
			case "PRICE_DESC" -> Sort.by(Sort.Direction.DESC, "minPrice");
			default -> Sort.by(Sort.Direction.DESC, "createdAt");
		};
		PageRequest pageable = PageRequest.of(page, size, sorting);
		Query dataQuery = new Query(criteria).with(pageable);
		return new PageImpl<>(mongoTemplate.find(dataQuery, Product.class), pageable, total);
	}
}
