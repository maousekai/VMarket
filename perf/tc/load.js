// PERF-LOAD-01..05 and 07 (NFR-PERF-03): at least 200 concurrent users, served
// stably. Realistic mix of SRS journeys, per 200 VUs:
//   70 guest browsing (UC-04)     30 search (UC-03)      50 cart (UC-05)
//   20 COD checkout (UC-06 A1)    30 account / order tracking (UC-02, UC-07)
// PROFILE: smoke (LOAD-01) | load15 (LOAD-02) | soak (LOAD-03) | spike (LOAD-04)
//          | stress (LOAD-05) | load + DEGRADE=true (LOAD-07, NFR-REL-02)
//
// LOAD-07: the operator stops ai-search-service (and the recommendation service)
// during the hold phase; the core buying journeys must keep their thresholds and
// search must still answer through the UC-03 fallback.
import { MIN_SAMPLES, PROFILE, SUMMARY_TREND_STATS, THRESHOLD_KINDS, blocked, buildThresholds, guardWritableTarget, requireEnv, scenarioFor, totalPeak } from '../lib/config.js';
import { READ, SEARCH, SEARCH_FALLBACK, WRITE, INFO, pick } from '../lib/endpoints.js';
import { CARTS, MANIFEST_INFO } from '../lib/manifest.js';
import { POOLS, prelogin, range } from '../lib/session.js';
import { available, requireFixture, requireServices } from '../lib/probe.js';
import { browseJourney } from '../flows/browse.js';
import { searchJourney } from '../flows/search.js';
import { cartIteration } from '../flows/cart.js';
import { checkoutJourney } from '../flows/checkout.js';
import { accountJourney } from '../flows/account.js';
import { breakingPoint, writeSummary } from '../lib/summary.js';

requireEnv('PERF_PASSWORD');
guardWritableTarget('tc/load.js');
const DEGRADE = __ENV.DEGRADE === 'true';

const scenarios = {
  browse: scenarioFor('browse', 70),
  search: scenarioFor('search', 30),
  cart: scenarioFor('cart', 50),
  checkout: scenarioFor('checkout', 20),
  account: scenarioFor('account', 30),
};
const PEAK = totalPeak(scenarios);
if (PEAK > POOLS.shopper.size || PEAK > POOLS.order.size) {
  throw new Error(`Peak ${PEAK} VUs exceeds the buyer pools (${POOLS.shopper.size}/${POOLS.order.size}); enlarge the fixture.`);
}

const ENDPOINTS = {
  ...pick(READ, ['GET /api/products', 'GET /api/products?categoryId', 'GET /api/products?price', 'GET /api/products?shopId', 'GET /api/products/{id}', 'GET /api/products/categories', 'GET /api/shops/{id}', 'GET /api/cart', 'GET /api/orders', 'GET /api/users/me']),
  ...WRITE,
  ...(DEGRADE ? {} : SEARCH),
};
const custom = [['search_served', 'rate>=0.99', 'acceptance']];
if (DEGRADE) custom.push(['search_fallback_used', 'rate>0', 'invalid']); // the AI service must really have been stopped
THRESHOLD_KINDS['search_fallback_used|rate>0'] = 'invalid';

export const options = {
  scenarios,
  thresholds: buildThresholds({
    endpoints: ENDPOINTS,
    info: [...INFO, ...Object.keys(SEARCH_FALLBACK), ...(DEGRADE ? Object.keys(SEARCH) : [])],
    data: { cart_invariant_violations: 'count==0', order_invariant_violations: 'count==0', catalog_unsellable_leaks: 'count==0' },
    custom,
    minSamples: MIN_SAMPLES / 4,
  }),
  summaryTrendStats: SUMMARY_TREND_STATS,
  setupTimeout: '15m',
};

export function setup() {
  requireFixture(MANIFEST_INFO.activeProducts >= 1000, '>= 1000 ACTIVE products');
  requireFixture(CARTS.length >= PEAK, `${PEAK} fixture carts`);
  const shoppers = prelogin(range(POOLS.shopper.start, PEAK));
  const orderers = prelogin(range(POOLS.order.start, PEAK));
  requireServices(['product', 'shop', 'cart', 'order', 'user'], Object.values(shoppers)[0].token);
  const searchUp = available('search');
  if (DEGRADE && !searchUp) blocked('LOAD-07 starts with ai-search-service running and stops it during the run; it is not running now');
  if (DEGRADE) console.warn('LOAD-07: stop ai-search-service (and the recommendation service) once the hold phase starts (~90 s), e.g. docker compose stop ai-search-service.');
  return { sessions: { ...shoppers, ...orderers }, degraded: searchUp ? [] : ['ai-search-service not running: search answered by the UC-03 fallback'] };
}

export function browse() {
  browseJourney();
}
export function search(data) {
  // ai-search not deployed: basic search (UC-03). LOAD-07: the service is stopped mid-run.
  searchJourney({ aiAvailable: DEGRADE || data.degraded.length === 0, tolerate: DEGRADE });
}
export function cart(data) {
  cartIteration(data.sessions);
}
export function checkout(data) {
  checkoutJourney(data.sessions);
}
export function account() {
  accountJourney();
}

export function handleSummary(data) {
  const extra = { degraded: (data.setup_data && data.setup_data.degraded) || [] };
  if (PROFILE === 'stress') extra.breakingPoint = breakingPoint(data, ENDPOINTS);
  return writeSummary(data, extra);
}
