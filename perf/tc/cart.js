// PERF-CART-01..03: cart GET -> PATCH 1/2 -> GET (FR-CART-01/02, NFR-PERF-01/03).
//   k6 run -e TC=PERF-CART-03 -e PROFILE=load -e PERF_PASSWORD=... perf/tc/cart.js
// One Cart instance is assumed (the per-user lock only protects inside one process).
import {
  MUTATION_P95_MS,
  READ_P95_MS,
  SUMMARY_TREND_STATS,
  assertBuyersCover,
  buildThresholds,
  guardWritableTarget,
  peakVus,
  requireEnv,
  scenarioFor,
} from '../lib/config.js';
import { CARTS, MANIFEST_INFO } from '../lib/manifest.js';
import { preloginBuyers } from '../lib/session.js';
import { cartIteration } from '../flows/cart.js';
import { writeSummary } from '../lib/summary.js';

requireEnv('PERF_PASSWORD');
guardWritableTarget('tc/cart.js');

const scenarios = { cart: scenarioFor('cart', 200) };
assertBuyersCover(scenarios, CARTS.length); // one fixture cart per VU

export const options = {
  scenarios,
  thresholds: buildThresholds({
    endpoints: {
      'GET /api/cart': READ_P95_MS, // R
      'PATCH /api/cart/items/{productId}': MUTATION_P95_MS, // Q
    },
    info: ['POST /api/auth/refresh'],
    business: { cart_invariant_violations: 'count==0' },
  }),
  summaryTrendStats: SUMMARY_TREND_STATS,
  setupTimeout: '5m',
};

export function setup() {
  return preloginBuyers(peakVus(scenarios.cart));
}

export function cart(prepared) {
  cartIteration(prepared);
}

export function handleSummary(data) {
  return writeSummary(data, { manifest: { runId: MANIFEST_INFO.runId, carts: CARTS.length } });
}
