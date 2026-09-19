package com.vmarket.product.service;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Backfill/reconciliation for documents created before materialized catalog fields existed. */
@Component
@ConditionalOnProperty(prefix = "app.catalog-migration", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CatalogDataMigration implements ApplicationRunner {
	private final CatalogProjectionService projections;

	public CatalogDataMigration(CatalogProjectionService projections) {
		this.projections = projections;
	}

	@Override
	public void run(ApplicationArguments args) {
		projections.synchronizeAllProducts();
	}

	@Scheduled(fixedDelayString = "${app.catalog-migration.reconcile-interval-ms:3600000}")
	public void reconcile() {
		projections.synchronizeAllProducts();
	}
}
