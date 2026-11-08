// Phase 4 (PBL6-77): cart and COD checkout.
// Needs cart-service, order-service, product-service, user-service and the seed data.
//
// TEST ENVIRONMENT ONLY: creates carts and orders. COD only; each order is
// cancelled right after it is placed so reserved stock is released.
//
//   k6 run -e PERF_PASSWORD=... [-e PROFILE=load] perf/scenarios/04-cart-order.js
import { READ_P95_MS, SUMMARY_TREND_STATS, assertBuyersCover, guardWritableTarget, requireEnv, scenarioFor, thresholdsFor } from '../lib/config.js';
import { loadCatalog } from '../lib/catalog.js';
import { cartAndCheckout, orderThresholds } from '../flows/checkout.js';

requireEnv('PERF_PASSWORD');
guardWritableTarget('04-cart-order.js');

// Two VUs on one account would fight over the same cart (checked at the profile's peak).
const scenarios = { buyers: scenarioFor('buyer') };
assertBuyersCover(scenarios);

export const options = {
  scenarios,
  thresholds: thresholdsFor(
    {
      'GET /api/cart': READ_P95_MS,
      'GET /api/orders': READ_P95_MS,
      'GET /api/orders/{orderId}': READ_P95_MS,
      'GET /api/users/me/addresses': READ_P95_MS,
      // Writes have no NFR; these budgets only make them appear per endpoint in the summary.
      'POST /api/cart/items': 1000,
      'PATCH /api/cart/items/{productId}': 1000,
      'POST /api/cart/validate': 1000,
      'POST /api/orders': 2000,
      'PUT /api/orders/{orderId}/cancel': 1000,
    },
    orderThresholds(),
  ),
  summaryTrendStats: SUMMARY_TREND_STATS,
};

export function setup() {
  return loadCatalog({ requireStock: true });
}

export function buyer(catalog) {
  cartAndCheckout(catalog);
}
