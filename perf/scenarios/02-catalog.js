// Phase 2 (PBL6-75): anonymous catalog browsing — categories, listings, brands, details.
// Needs product-service and the seed data (scripts/seed-demo-data).
//
//   k6 run [-e PROFILE=load] perf/scenarios/02-catalog.js
import { READ_P95_MS, SUMMARY_TREND_STATS, scenarioFor, thresholdsFor } from '../lib/config.js';
import { loadCatalog } from '../lib/catalog.js';
import { browseCatalog } from '../flows/catalog.js';

export const options = {
  scenarios: { shoppers: scenarioFor('shopper') },
  thresholds: thresholdsFor({
    'GET /api/products/categories': READ_P95_MS,
    'GET /api/products': READ_P95_MS,
    'GET /api/products/{id}': READ_P95_MS,
    'GET /api/products/brands': READ_P95_MS,
  }),
  summaryTrendStats: SUMMARY_TREND_STATS,
};

export function setup() {
  return loadCatalog();
}

export function shopper(catalog) {
  browseCatalog(catalog);
}
