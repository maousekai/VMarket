package com.vmarket.product;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.vmarket.product.service.CatalogDataMigration;
import com.vmarket.product.service.CatalogProjectionService;

@ExtendWith(MockitoExtension.class)
class CatalogDataMigrationTest {
	@Mock CatalogProjectionService projections;

	@Test
	void runsAtStartupAndPeriodically() {
		CatalogDataMigration migration = new CatalogDataMigration(projections);
		migration.run(null);
		migration.reconcile();
		verify(projections, times(2)).synchronizeAllProducts();
	}
}
