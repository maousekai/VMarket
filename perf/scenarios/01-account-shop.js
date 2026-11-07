// Phase 1 (PBL6-74): login, my profile, public shop page, seller "my shop".
// Needs only the docker-compose stack: auth, user, shop services + api-gateway.
//
//   k6 run -e PERF_PASSWORD=... -e SEED_PASSWORD=... [-e PROFILE=load] perf/scenarios/01-account-shop.js
import { BUYER_COUNT, READ_P95_MS, SUMMARY_TREND_STATS, VUS, requireEnv, scenarioFor, thresholdsFor } from '../lib/config.js';
import { loadCatalog } from '../lib/catalog.js';
import { resolveShopIds, SEED_SELLER_EMAILS } from '../lib/shops.js';
import { buyerProfileAndShops, sellerMyShop } from '../flows/account.js';

requireEnv('PERF_PASSWORD');
const SELLER_PASSWORD = __ENV.SEED_PASSWORD;
const SELLER_VUS = Math.min(SEED_SELLER_EMAILS.length, Math.max(1, Math.round(VUS * 0.05)));

if (VUS > BUYER_COUNT) console.warn(`VUS (${VUS}) > BUYER_COUNT (${BUYER_COUNT}): some VUs share a buyer account`);

const scenarios = { buyers: scenarioFor('buyer') };
const endpoints = {
  'GET /api/users/me': READ_P95_MS,
  'GET /api/shops/{shopId}': READ_P95_MS,
  'POST /api/auth/login': 1500, // reported, not an NFR: BCrypt is deliberately slow
};
if (SELLER_PASSWORD) {
  scenarios.sellers = scenarioFor('seller', { vus: SELLER_VUS });
  endpoints['GET /api/shops/me'] = READ_P95_MS;
}

export const options = {
  scenarios,
  thresholds: thresholdsFor(endpoints),
  summaryTrendStats: SUMMARY_TREND_STATS,
};

export function setup() {
  // The catalog (product-service) is only a fallback source of shop ids.
  const catalog = SELLER_PASSWORD || __ENV.SHOP_IDS ? undefined : loadCatalog();
  return { shopIds: resolveShopIds({ sellerPassword: SELLER_PASSWORD, catalog }) };
}

export function buyer(data) {
  buyerProfileAndShops(data.shopIds);
}

export function seller() {
  sellerMyShop(SEED_SELLER_EMAILS, SELLER_PASSWORD);
}
