package com.vmarket.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import com.vmarket.product.model.Product;
import com.vmarket.product.repository.ProductCatalogQuery;

@ExtendWith(MockitoExtension.class)
class ProductCatalogQueryTest {
	@Mock MongoTemplate mongoTemplate;

	@Test
	void priceDescendingSortUsesDisplayedMinimumPrice() {
		when(mongoTemplate.count(any(Query.class), eq(Product.class))).thenReturn(0L);
		when(mongoTemplate.find(any(Query.class), eq(Product.class))).thenReturn(List.of());
		ProductCatalogQuery query = new ProductCatalogQuery(mongoTemplate);

		query.search(null, "cat-1", null, null, null, null, "PRICE_DESC", 0, 20);

		ArgumentCaptor<Query> dataQuery = ArgumentCaptor.forClass(Query.class);
		org.mockito.Mockito.verify(mongoTemplate).find(dataQuery.capture(), eq(Product.class));
		assertThat(dataQuery.getValue().getSortObject().getInteger("minPrice")).isEqualTo(-1);
	}

	@Test
	void publicQueryAlwaysRequiresMaterializedCategoryVisibility() {
		when(mongoTemplate.count(any(Query.class), eq(Product.class))).thenReturn(0L);
		when(mongoTemplate.find(any(Query.class), eq(Product.class))).thenReturn(List.of());
		ProductCatalogQuery query = new ProductCatalogQuery(mongoTemplate);

		query.search(null, null, null, null, null, null, "NEWEST", 0, 20);

		ArgumentCaptor<Query> countQuery = ArgumentCaptor.forClass(Query.class);
		org.mockito.Mockito.verify(mongoTemplate).count(countQuery.capture(), eq(Product.class));
		assertThat(countQuery.getValue().getQueryObject().getBoolean("categoryVisible")).isTrue();
	}
}
