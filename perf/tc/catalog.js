// PERF-CATALOG-01..05: catalog reads (FR-PROD-05/06, NFR-PERF-01/03).
//   k6 run -e TC=PERF-CATALOG-03 -e PROFILE=load perf/tc/catalog.js
// Read-only: no login, no seeding during the run ("không seed khi run").
import { READ_P95_MS, SUMMARY_TREND_STATS, buildThresholds, scenarioFor } from '../lib/config.js';
import { get, query } from '../lib/api.js';
import { MANIFEST_INFO } from '../lib/manifest.js';
import { browseOnce, checkHiddenNotReadable } from '../flows/catalog.js';
import { writeSummary } from '../lib/summary.js';

const MIN_ACTIVE = 1000; // PERF-CATALOG fixture: at least 1000 ACTIVE products

export const options = {
  scenarios: { catalog: scenarioFor('catalog', 200) },
  thresholds: buildThresholds({
    endpoints: {
      'GET /api/products': READ_P95_MS,
      'GET /api/products?categoryId': READ_P95_MS,
      'GET /api/products/{id}': READ_P95_MS,
    },
    business: { catalog_unsellable_leaks: 'count==0' },
  }),
  summaryTrendStats: SUMMARY_TREND_STATS,
};

export function setup() {
  const { data } = get(`/api/products${query({ page: 0, size: 1 })}`, { endpoint: 'setup:count' });
  const live = data ? data.totalElements : -1;
  if (live < MIN_ACTIVE) {
    throw new Error(`Only ${live} ACTIVE products on ${MANIFEST_INFO.baseUrl}; the PERF-CATALOG fixture needs >= ${MIN_ACTIVE}. Run fixtures/prepare.mjs.`);
  }
  if (live !== MANIFEST_INFO.activeProducts) {
    console.warn(`ACTIVE products now ${live}, manifest recorded ${MANIFEST_INFO.activeProducts}: the dataset changed since the fixture run; note it in the report.`);
  }
  checkHiddenNotReadable();
  return { liveActiveProducts: live };
}

export function catalog() {
  browseOnce();
}

export function teardown() {
  checkHiddenNotReadable();
}

export function handleSummary(data) {
  return writeSummary(data, { manifest: { runId: MANIFEST_INFO.runId, activeProducts: MANIFEST_INFO.activeProducts } });
}
