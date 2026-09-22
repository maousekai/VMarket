package com.vmarket.product;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.bson.Document;
import org.bson.types.Decimal128;
import org.springframework.data.mongodb.core.MongoTemplate;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCursor;
import org.bson.conversions.Bson;
import static org.mockito.ArgumentMatchers.any;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.vmarket.product.service.CatalogDataMigration;
import com.vmarket.product.service.CatalogProjectionService;

@ExtendWith(MockitoExtension.class)
class CatalogDataMigrationTest {
	@Mock CatalogProjectionService projections;
	@Mock MongoTemplate mongoTemplate;
	@Mock MongoCollection<Document> products;
	@Mock FindIterable<Document> iterable;
	@Mock MongoCursor<Document> cursor;

	@Test
	void runsAtStartupAndPeriodically() {
		when(mongoTemplate.getCollection("products")).thenReturn(products);
		when(products.find()).thenReturn(iterable);
		when(iterable.batchSize(100)).thenReturn(iterable);
		when(iterable.iterator()).thenReturn(cursor);
		CatalogDataMigration migration = new CatalogDataMigration(projections, mongoTemplate);
		migration.run(null);
		migration.reconcile();
		verify(projections, times(2)).synchronizeAllProducts();
	}

	@Test
	void convertsLegacyDecimalPriceExactlyBeforeProjection() {
		when(mongoTemplate.getCollection("products")).thenReturn(products);
		when(products.find()).thenReturn(iterable);
		when(iterable.batchSize(100)).thenReturn(iterable);
		when(iterable.iterator()).thenReturn(cursor);
		when(cursor.hasNext()).thenReturn(true, false);
		when(cursor.next()).thenReturn(new Document("_id", "p1")
				.append("variants", java.util.List.of(new Document("price", Decimal128.parse("100000")))));
		new CatalogDataMigration(projections, mongoTemplate).run(null);
		verify(products).updateOne(any(Bson.class), any(Bson.class));
	}

	@Test
	void rejectsFractionalLegacyPriceInsteadOfTruncating() {
		when(mongoTemplate.getCollection("products")).thenReturn(products);
		when(products.find()).thenReturn(iterable);
		when(iterable.batchSize(100)).thenReturn(iterable);
		when(iterable.iterator()).thenReturn(cursor);
		when(cursor.hasNext()).thenReturn(true);
		when(cursor.next()).thenReturn(new Document("_id", "p1")
				.append("variants", java.util.List.of(new Document("price", Decimal128.parse("100000.5")))));
		assertThatThrownBy(() -> new CatalogDataMigration(projections, mongoTemplate).run(null))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("Legacy catalog price");
	}
}
