// PERF-CART-04: mixed soak, 200 VUs in total = 120 Catalog + 60 Cart + 20 Auth
// (rule 5). Continuous login is a synthetic Auth workload, not real user behaviour.
//   k6 run -e TC=PERF-CART-04 -e PROFILE=soak -e PERF_PASSWORD=... perf/tc/mixed.js
import {
  LOGIN_P95_MS,
  MUTATION_P95_MS,
  READ_P95_MS,
  SCENARIO_PEAKS,
  SUMMARY_TREND_STATS,
  assertBuyersCover,
  buildThresholds,
  guardWritableTarget,
  requireEnv,
  scenarioFor,
} from '../lib/config.js';
import { CARTS, MANIFEST_INFO } from '../lib/manifest.js';
import { BUYER_TOTAL, preloginBuyers } from '../lib/session.js';
import { browseOnce, checkHiddenNotReadable } from '../flows/catalog.js';
import { cartIteration } from '../flows/cart.js';
import { loginLogout } from '../flows/auth.js';
import { writeSummary } from '../lib/summary.js';

requireEnv('PERF_PASSWORD');
guardWritableTarget('tc/mixed.js');

Object.assign(SCENARIO_PEAKS, { catalog: 120, cart: 60, auth: 20 });
const scenarios = {
  catalog: scenarioFor('catalog', 120),
  cart: scenarioFor('cart', 60),
  auth: scenarioFor('auth', 20),
};
// VU ids are global across scenarios, so every id up to the total peak needs a
// buyer, and any of them may be a cart VU, so each needs a fixture cart.
assertBuyersCover(scenarios, Math.min(BUYER_TOTAL, CARTS.length));
const TOTAL_PEAK = Object.values(scenarios).reduce((n, s) => n + Math.max(s.startVUs, ...s.stages.map((x) => x.target)), 0);

export const options = {
  scenarios,
  thresholds: buildThresholds({
    endpoints: {
      'GET /api/products': READ_P95_MS,
      'GET /api/products?categoryId': READ_P95_MS,
      'GET /api/products/{id}': READ_P95_MS,
      'GET /api/cart': READ_P95_MS,
      'PATCH /api/cart/items/{productId}': MUTATION_P95_MS,
      'POST /api/auth/login': LOGIN_P95_MS,
    },
    info: ['POST /api/auth/logout', 'POST /api/auth/refresh'],
    business: { cart_invariant_violations: 'count==0', catalog_unsellable_leaks: 'count==0' },
  }),
  summaryTrendStats: SUMMARY_TREND_STATS,
  setupTimeout: '5m',
};

export function setup() {
  checkHiddenNotReadable();
  return preloginBuyers(TOTAL_PEAK);
}

export function catalog() {
  browseOnce();
}

export function cart(prepared) {
  cartIteration(prepared);
}

export function auth() {
  loginLogout();
}

export function teardown() {
  checkHiddenNotReadable();
}

export function handleSummary(data) {
  return writeSummary(data, { manifest: { runId: MANIFEST_INFO.runId, carts: CARTS.length } });
}
