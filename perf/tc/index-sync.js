// PERF-SRCH-04 (FR-SRCH-04, UC-11 step 5): a product put on sale must be findable
// by search within 1 minute, while 50 users are searching.
// A seller creates a product with a unique name every 30 s (ProductCreated); a
// watcher polls the search API every 2 s until it appears and records the delay.
import { check, sleep } from 'k6';
import exec from 'k6/execution';
import { Trend, Counter } from 'k6/metrics';
import { NFR, SUMMARY_TREND_STATS, buildThresholds, guardWritableTarget, requireEnv, scenarioFor } from '../lib/config.js';
import { SEARCH } from '../lib/endpoints.js';
import { get, post, query } from '../lib/api.js';
import { MANIFEST_INFO } from '../lib/manifest.js';
import { login } from '../lib/session.js';
import { blocked } from '../lib/config.js';
import { requireServices } from '../lib/probe.js';
import { searchJourney } from '../flows/search.js';
import { writeSummary } from '../lib/summary.js';

requireEnv('PERF_PASSWORD');
requireEnv('SEED_PASSWORD');
guardWritableTarget('tc/index-sync.js');

const syncSeconds = new Trend('index_sync_seconds');
const notIndexed = new Counter('index_sync_missed');
const syncSamples = new Counter('index_sync_samples');
const PRODUCTS_TO_CREATE = __ENV.PROFILE === 'smoke' ? 2 : 20;

export const options = {
  scenarios: {
    search: scenarioFor('search', 50, 'load'), // 50 searching users for the whole window
    seller: { executor: 'per-vu-iterations', exec: 'seller', vus: 1, iterations: PRODUCTS_TO_CREATE, startTime: '90s', maxDuration: '20m' },
  },
  thresholds: buildThresholds({
    endpoints: SEARCH,
    profile: 'load',
    custom: [
      ['index_sync_seconds', `max<=${NFR.INDEX_SYNC_S}`, 'acceptance'],
      ['index_sync_samples', `count>=${PRODUCTS_TO_CREATE}`, 'samples'],
      ['index_sync_missed', 'count==0', 'acceptance'],
    ],
  }),
  summaryTrendStats: SUMMARY_TREND_STATS,
};

export function setup() {
  requireServices(['product', 'search']);
  const seller = MANIFEST_INFO.seller;
  if (!seller) blocked('fixture missing: seed seller (manifest.seller)');
  const { session } = login(seller.email, requireEnv('SEED_PASSWORD'), { endpoint: 'setup:login' });
  if (!session) blocked(`seed seller ${seller.email} cannot log in (SEED_PASSWORD)`);
  return { token: session.token, seller };
}

export function search() {
  searchJourney();
}

export function seller(data) {
  const token = `perfsync${Date.now().toString(36)}${exec.vu.iterationInScenario}`;
  const { response } = post('/api/products', {
    endpoint: 'POST /api/products',
    token: data.token,
    expect: [201],
    headers: { 'Idempotency-Key': `perf-sync-${token}` },
    body: {
      shopId: data.seller.shopId,
      name: `PERF đồng bộ ${token}`,
      description: 'Sản phẩm kiểm thử đồng bộ chỉ mục (FR-SRCH-04).',
      imageUrls: [`https://picsum.photos/seed/${token}/600/600`],
      categoryId: data.seller.categoryId,
      brandId: null,
      status: 'ACTIVE',
      currency: 'VND',
      variants: [{ sku: token.toUpperCase(), attributes: { 'Phân loại': 'Tiêu chuẩn' }, price: 123000, stock: 5 }],
    },
  });
  if (response.status !== 201) return sleep(30);
  const created = Date.now();
  let found = false;
  while (Date.now() - created < 90000 && !found) {
    sleep(2);
    const { data: result } = get(`/api/ai/search${query({ keyword: token, size: 5 })}`, { endpoint: 'GET /api/ai/search (sync poll)', expect: [200, 503] });
    const list = result ? result.content || result.items || result.results || [] : [];
    found = list.some((p) => (p.name || p.title || '').includes(token));
  }
  if (found) {
    syncSeconds.add((Date.now() - created) / 1000);
    syncSamples.add(1);
  }
  else notIndexed.add(1);
  check(found, { 'new product findable by search within 90 s': (f) => f });
  sleep(Math.max(0, 30 - (Date.now() - created) / 1000));
}

export function handleSummary(data) {
  return writeSummary(data);
}
