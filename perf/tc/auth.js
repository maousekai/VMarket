// PERF-AUTH-01..05: login -> logout workload (FR-AUTH-02/06, NFR-PERF-03; login p95 is Q).
//   k6 run -e TC=PERF-AUTH-03 -e PROFILE=load -e PERF_PASSWORD=... perf/tc/auth.js
// Normally started by run.mjs, which sets TC/PROFILE and the output files.
import { check } from 'k6';
import { Gauge } from 'k6/metrics';
import { LOGIN_P95_MS, PROFILE, SUMMARY_TREND_STATS, THRESHOLD_KINDS, assertBuyersCover, buildThresholds, guardWritableTarget, requireEnv, scenarioFor } from '../lib/config.js';
import { get, parseJson } from '../lib/api.js';
import { BUYER_TOTAL, buyerEmail, login } from '../lib/session.js';
import { loginLogout } from '../flows/auth.js';
import { writeSummary } from '../lib/summary.js';

requireEnv('PERF_PASSWORD');
guardWritableTarget('tc/auth.js');

const scenarios = { auth: scenarioFor('auth', 200) };
assertBuyersCover(scenarios, BUYER_TOTAL); // spike reaches 300 VUs -> needs 300 buyers

// "Active sessions must not grow without bound" (PERF-AUTH-05): sample buyers'
// active sessions before and after. Acceptance in soak, reported otherwise.
const sessionGrowth = new Gauge('auth_active_sessions_growth');
const SAMPLE = 10;

const thresholds = buildThresholds({
  endpoints: { 'POST /api/auth/login': LOGIN_P95_MS },
  info: ['POST /api/auth/logout'],
  business: PROFILE === 'soak' ? { auth_active_sessions_growth: 'value<=1' } : {},
});
if (PROFILE !== 'soak') {
  thresholds.auth_active_sessions_growth = ['value>=-1000000']; // reported only
  THRESHOLD_KINDS['auth_active_sessions_growth|value>=-1000000'] = 'info';
}

export const options = { scenarios, thresholds, summaryTrendStats: SUMMARY_TREND_STATS };

function activeSessions() {
  const password = requireEnv('PERF_PASSWORD');
  const counts = [];
  for (let i = 0; i < Math.min(SAMPLE, BUYER_TOTAL); i++) {
    const { session } = login(buyerEmail(i), password, { endpoint: 'setup:login' });
    if (!session) {
      counts.push(null);
      continue;
    }
    const { response } = get('/api/auth/sessions', { endpoint: 'setup:sessions' });
    const list = parseJson(response);
    counts.push(Array.isArray(list) ? list.length : null);
    // Leave the counting session in place: it is counted both before and after.
  }
  return counts;
}

export function setup() {
  return { sessionsBefore: activeSessions() };
}

export function auth() {
  loginLogout();
}

export function teardown(data) {
  const after = activeSessions();
  let growth = 0;
  after.forEach((n, i) => {
    const before = data.sessionsBefore[i];
    // +1: the "after" count includes the session opened by the before-count login.
    if (n !== null && before !== null) growth = Math.max(growth, n - before - 1);
  });
  sessionGrowth.add(growth);
  check(growth, { 'active sessions did not grow (sampled buyers)': (g) => g <= 1 });
  console.log(`active sessions per sampled buyer, before=${JSON.stringify(data.sessionsBefore)} after=${JSON.stringify(after)} growth=${growth}`);
}

export function handleSummary(data) {
  return writeSummary(data);
}
