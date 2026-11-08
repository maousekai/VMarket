// Phase 5 (PBL6-78): the official 200-concurrent-user run (NFR-PERF-03).
// Runs the phase 1-4 flows side by side in realistic proportions.
//
// Run k6 from a separate machine, against VPS/staging, with a test database:
//   k6 run -e BASE_URL=https://staging... -e ALLOW_REMOTE=true -e PERF_PASSWORD=... \
//          -e SEED_PASSWORD=... -e PROFILE=load -e HOLD=15m perf/scenarios/05-mixed.js
//
// MIX="browse:60,account:20,search:10,checkout:10" changes the split.
// INCLUDE_SEARCH=false drops search (and gives its share to browse) while
// ai-search-service is not deployed.
import {
  READ_P95_MS,
  SEARCH_P95_MS,
  SUGGEST_P95_MS,
  SUMMARY_TREND_STATS,
  VUS,
  assertBuyersCover,
  guardWritableTarget,
  requireEnv,
  scenarioFor,
  thresholdsFor,
} from '../lib/config.js';
import { loadCatalog } from '../lib/catalog.js';
import { resolveShopIds } from '../lib/shops.js';
import { buyerProfileAndShops } from '../flows/account.js';
import { browseCatalog } from '../flows/catalog.js';
import { searchSession } from '../flows/search.js';
import { cartAndCheckout, orderThresholds } from '../flows/checkout.js';

requireEnv('PERF_PASSWORD');
guardWritableTarget('05-mixed.js');

const INCLUDE_SEARCH = __ENV.INCLUDE_SEARCH !== 'false';
const mix = parseMix(__ENV.MIX || 'browse:60,account:20,search:10,checkout:10');
if (!INCLUDE_SEARCH) {
  mix.browse = (mix.browse || 0) + (mix.search || 0);
  delete mix.search;
}

const total = Object.values(mix).reduce((a, b) => a + b, 0);
const vusFor = (name) => Math.max(1, Math.round((VUS * mix[name]) / total));

// Only account + checkout VUs log in, but VU ids are unique across all scenarios,
// so a logged-in VU can get any id up to the total peak (see assertBuyersCover).
const loggedInVus = (mix.account ? vusFor('account') : 0) + (mix.checkout ? vusFor('checkout') : 0);

const scenarios = {};
const endpoints = { 'POST /api/auth/login': 1500 };
if (mix.browse) {
  scenarios.browse = scenarioFor('browse', { vus: vusFor('browse') });
  Object.assign(endpoints, {
    'GET /api/products/categories': READ_P95_MS,
    'GET /api/products': READ_P95_MS,
    'GET /api/products/{id}': READ_P95_MS,
  });
}
if (mix.account) {
  scenarios.account = scenarioFor('account', { vus: vusFor('account') });
  Object.assign(endpoints, { 'GET /api/users/me': READ_P95_MS, 'GET /api/shops/{shopId}': READ_P95_MS });
}
if (mix.search) {
  scenarios.search = scenarioFor('search', { vus: vusFor('search') });
  Object.assign(endpoints, {
    'GET /api/ai/search': SEARCH_P95_MS,
    'GET /api/ai/search/suggestions': SUGGEST_P95_MS,
  });
}
if (mix.checkout) {
  scenarios.checkout = scenarioFor('checkout', { vus: vusFor('checkout') });
  Object.assign(endpoints, {
    'GET /api/cart': READ_P95_MS,
    'GET /api/orders': READ_P95_MS,
    'POST /api/orders': 2000,
  });
}

assertBuyersCover(scenarios);

export const options = {
  scenarios,
  thresholds: thresholdsFor(endpoints, mix.checkout ? orderThresholds() : {}),
  summaryTrendStats: SUMMARY_TREND_STATS,
};

export function setup() {
  console.log(`mix ${JSON.stringify(mix)} over ${VUS} VUs (${loggedInVus} logged in)`);
  const catalog = loadCatalog({ requireStock: !!mix.checkout });
  return { catalog, shopIds: resolveShopIds({ sellerPassword: __ENV.SEED_PASSWORD, catalog }) };
}

export function browse(data) {
  browseCatalog(data.catalog);
}

export function account(data) {
  buyerProfileAndShops(data.shopIds);
}

export function search() {
  searchSession();
}

export function checkout(data) {
  cartAndCheckout(data.catalog);
}

function parseMix(raw) {
  const result = {};
  for (const part of raw.split(',')) {
    const [name, weight] = part.split(':').map((s) => s.trim());
    if (!['browse', 'account', 'search', 'checkout'].includes(name)) throw new Error(`MIX: unknown flow "${name}"`);
    const value = parseFloat(weight);
    if (!(value >= 0)) throw new Error(`MIX: bad weight for ${name}`);
    if (value > 0) result[name] = value;
  }
  return result;
}
