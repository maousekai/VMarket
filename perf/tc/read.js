// PERF-READ-01..03 (NFR-PERF-01): every buyer read API p95 <= 500 ms.
//   READ-01 smoke | READ-02 baseline 50 VUs | READ-03 load 200 VUs x 10 min
// Also the background load of PERF-WEB-02 / PERF-SHIP-02 (run.mjs "companion").
import { MIN_SAMPLES, SUMMARY_TREND_STATS, buildThresholds, guardWritableTarget, requireEnv, scenarioFor, peakVus } from '../lib/config.js';
import { READ } from '../lib/endpoints.js';
import { CARTS, MANIFEST_INFO, ORDER_HISTORY } from '../lib/manifest.js';
import { POOLS, prelogin, range } from '../lib/session.js';
import { requireFixture, requireServices } from '../lib/probe.js';
import { readOnce } from '../flows/read.js';
import { writeSummary } from '../lib/summary.js';

requireEnv('PERF_PASSWORD');
guardWritableTarget('tc/read.js');

const scenarios = { read: scenarioFor('read', 200) };
const PEAK = peakVus(scenarios.read);

export const options = {
  scenarios,
  // Rare endpoints get fewer samples; the 200-sample rule applies to the busy ones
  // and the summary reports the count of every endpoint.
  thresholds: buildThresholds({ endpoints: READ, data: { cart_invariant_violations: 'count==0', catalog_unsellable_leaks: 'count==0' }, minSamples: MIN_SAMPLES / 4 }),
  summaryTrendStats: SUMMARY_TREND_STATS,
  setupTimeout: '10m',
};

export function setup() {
  requireFixture(MANIFEST_INFO.activeProducts >= 1000, '>= 1000 ACTIVE products');
  requireFixture(CARTS.length >= Math.min(PEAK, POOLS.shopper.size), 'one fixture cart per VU');
  requireFixture(ORDER_HISTORY.length >= Math.min(PEAK, 200), 'order history for the read buyers');
  const sessions = prelogin(range(POOLS.shopper.start, Math.min(PEAK, POOLS.shopper.size)));
  requireServices(['product', 'shop', 'cart', 'order', 'user'], Object.values(sessions)[0].token);
  return sessions;
}

export function read(prepared) {
  readOnce(prepared);
}

export function handleSummary(data) {
  return writeSummary(data);
}
