package com.vmarket.product.service;

import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/** Opt-in only. Pause every catalog writer and rebuild Search after this migration. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(name = "app.search-revision-migration.enabled", havingValue = "true")
public class SearchRevisionMigration implements ApplicationRunner {
	private final MongoTemplate mongo;
	public SearchRevisionMigration(MongoTemplate mongo) { this.mongo = mongo; }
	@Override public void run(ApplicationArguments args) {
		mongo.getCollection("products").updateMany(Filters.eq("version", null), Updates.set("version", 0L));
	}
}
