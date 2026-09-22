package com.vmarket.product.service;

import java.util.ArrayList;
import java.util.List;

import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.Decimal128;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Converts legacy decimal prices, then reconciles materialized catalog fields. */
@Component
@ConditionalOnProperty(prefix = "app.catalog-migration", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CatalogDataMigration implements ApplicationRunner {
	private final CatalogProjectionService projections;
	private final MongoTemplate mongoTemplate;

	public CatalogDataMigration(CatalogProjectionService projections, MongoTemplate mongoTemplate) {
		this.projections = projections;
		this.mongoTemplate = mongoTemplate;
	}

	@Override
	public void run(ApplicationArguments args) {
		migrateLegacyPrices();
		projections.synchronizeAllProducts();
	}

	private void migrateLegacyPrices() {
		var products = mongoTemplate.getCollection("products");
		for (Document product : products.find().batchSize(100)) {
			List<Bson> changes = new ArrayList<>();
			convertPrice(product, "minPrice", "minPrice", changes);
			convertPrice(product, "maxPrice", "maxPrice", changes);
			List<Document> variants = product.getList("variants", Document.class);
			if (variants != null) {
				for (int i = 0; i < variants.size(); i++) {
					convertPrice(variants.get(i), "price", "variants." + i + ".price", changes);
					if (variants.get(i).getString("currency") == null) {
						changes.add(Updates.set("variants." + i + ".currency", "VND"));
					}
				}
			}
			if (!changes.isEmpty()) products.updateOne(Filters.eq("_id", product.get("_id")), Updates.combine(changes));
		}
	}

	private void convertPrice(Document source, String field, String path, List<Bson> changes) {
		if (source.get(field) instanceof Decimal128 decimal) {
			try {
				changes.add(Updates.set(path, decimal.bigDecimalValue().longValueExact()));
			} catch (ArithmeticException ex) {
				throw new IllegalStateException("Legacy catalog price cannot fit integer VND units: " + path, ex);
			}
		}
	}

	@Scheduled(fixedDelayString = "${app.catalog-migration.reconcile-interval-ms:3600000}")
	public void reconcile() {
		projections.synchronizeAllProducts();
	}
}
