// PERF-CART-05: gateway rate-limit guard (BVA/CT). Run ALONE, with the limiter ON
// at its default (200 requests / 60 s / client address), from an address with no
// other traffic. Its expected 429s are not part of any capacity run.
//
// Procedure (sheet "Performance Test", PERF-CART-05):
//   1. take a token first;
//   2. restart the gateway (the script waits until /actuator/health goes down and
//      comes back UP; pass -e WAIT_FOR_RESTART=false to skip that wait);
//   3. align to the start of a fixed window floor(epochSeconds/60);
//   4. send 200 GET /api/cart with concurrency 10 and wait for all responses;
//   5. send request 201: it must be 429 from the gateway;
//   6. if the window changed between request 1 and request 201 the run is invalid;
//   7. wait past the window (> 60 s) and send again: it must pass.
//
//   k6 run -e TC=PERF-CART-05 -e PERF_PASSWORD=... perf/tc/rate-limit-guard.js
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Gauge } from 'k6/metrics';
import { BASE_URL, THRESHOLD_KINDS, requireEnv } from '../lib/config.js';
import { parseJson } from '../lib/api.js';
import { buyerEmail, login } from '../lib/session.js';
import { writeSummary } from '../lib/summary.js';

requireEnv('PERF_PASSWORD');

const LIMIT = 200;
const CONCURRENCY = 10;
const WINDOW = 60;

const allowed = new Counter('guard_first200_allowed'); // of the first 200, not limited
const limited = new Counter('guard_first200_limited'); // of the first 200, got 429
const status201 = new Gauge('guard_request201_status');
const gatewayBody201 = new Gauge('guard_request201_gateway_body'); // 1 = gateway error envelope
const statusAfter = new Gauge('guard_after_window_status');
const valid = new Gauge('guard_window_valid'); // 1 = request 1 and 201 in one window

// A window change between request 1 and 201 makes the run invalid (Pending), not Failed.
THRESHOLD_KINDS['guard_window_valid|value==1'] = 'invalid';

export const options = {
  scenarios: { guard: { executor: 'per-vu-iterations', vus: 1, iterations: 1, maxDuration: '10m', exec: 'guard' } },
  thresholds: {
    guard_window_valid: ['value==1'],
    guard_first200_allowed: [`count==${LIMIT}`],
    guard_request201_status: ['value==429'],
    guard_request201_gateway_body: ['value==1'],
    guard_after_window_status: ['value==200'],
  },
};

// Behind the frontend proxy / Cloudflare only /api is forwarded; point HEALTH_URL at the
// gateway itself (e.g. http://localhost:8080/actuator/health on the stack host).
const HEALTH_URL = __ENV.HEALTH_URL || `${BASE_URL}/actuator/health`;
const health = () => http.get(HEALTH_URL, { tags: { endpoint: 'GET /actuator/health' }, timeout: '5s', responseCallback: http.expectedStatuses({ min: 200, max: 599 }) });
const isUp = (r) => r.status === 200 && (parseJson(r) || {}).status === 'UP';
// Server clock from the Date header (1 s resolution) -> offset to the local clock.
const serverEpoch = (r) => Date.parse(r.headers.Date || r.headers.date) / 1000;
const windowOf = (epochSeconds) => Math.floor(epochSeconds / WINDOW);

export function guard() {
  // 1. Token first (the login request belongs to an earlier window).
  const { session, response } = login(buyerEmail(0), requireEnv('PERF_PASSWORD'), { endpoint: 'setup:login' });
  if (!session) throw new Error(`login failed: HTTP ${response.status}`);
  const auth = { headers: { Authorization: `Bearer ${session.token}`, Accept: 'application/json' }, tags: { endpoint: 'GET /api/cart (guard)' }, responseCallback: http.expectedStatuses(200, 429) };

  // 2. Wait for the operator's gateway restart, then for health UP.
  if (__ENV.WAIT_FOR_RESTART !== 'false') {
    console.log('Token ready. Restart the gateway now (e.g. docker compose restart api-gateway). Waiting up to 5 min...');
    let wentDown = false;
    for (let i = 0; i < 300 && !wentDown; i++) {
      wentDown = !isUp(health());
      if (!wentDown) sleep(1);
    }
    if (!wentDown) throw new Error('The gateway was not restarted within 5 minutes (health never went down).');
  }
  let h;
  for (let i = 0; i < 300; i++) {
    h = health();
    if (isUp(h)) break;
    sleep(1);
  }
  if (!isUp(h)) throw new Error('Gateway health did not return UP within 5 minutes.');
  const offset = serverEpoch(h) - Date.now() / 1000; // server - local, about +-1 s

  // 3. Align: start 1.5 s after the next server window boundary.
  const nowServer = Date.now() / 1000 + offset;
  const startAt = (windowOf(nowServer) + 1) * WINDOW + 1.5;
  sleep(Math.max(0, startAt - nowServer));

  // 4. 200 requests, 10 at a time; wait for each batch.
  const statuses = {};
  let firstDate;
  let firstLocal;
  for (let sent = 0; sent < LIMIT; sent += CONCURRENCY) {
    const batch = http.batch(Array.from({ length: CONCURRENCY }, () => ['GET', `${BASE_URL}/api/cart`, null, auth]));
    for (const r of batch) {
      if (firstDate === undefined) {
        firstDate = serverEpoch(r);
        firstLocal = Date.now() / 1000;
      }
      statuses[r.status] = (statuses[r.status] || 0) + 1;
      if (r.status === 429) limited.add(1);
      else if (r.status !== 0) allowed.add(1);
    }
  }

  // 5. Request 201.
  const r201 = http.get(`${BASE_URL}/api/cart`, auth);
  const lastLocal = Date.now() / 1000;
  const body = parseJson(r201);
  status201.add(r201.status);
  gatewayBody201.add(body && body.error && body.error.code === 'RATE_LIMITED' ? 1 : 0);

  // 6. Same window for request 1 and request 201 (server Date header and local clock).
  const sameWindow = windowOf(firstDate) === windowOf(serverEpoch(r201)) && windowOf(firstLocal + offset) === windowOf(lastLocal + offset);
  valid.add(sameWindow ? 1 : 0);

  // 7. Past the window (> 60 s after request 1), the next request passes again.
  sleep(Math.max(WINDOW + 2 - (Date.now() / 1000 - firstLocal), 0));
  const after = http.get(`${BASE_URL}/api/cart`, auth);
  statusAfter.add(after.status);

  check(null, {
    'first 200 requests all allowed': () => (statuses[200] || 0) === LIMIT,
    'request 201 is 429': () => r201.status === 429,
    'request 201 has the gateway RATE_LIMITED body (not Cloudflare)': () => !!(body && body.error && body.error.code === 'RATE_LIMITED'),
    'request 1 and 201 in the same window (run valid)': () => sameWindow,
    'allowed again after the window': () => after.status === 200,
  });
  // Evidence: the actual headers, no assumption that Retry-After exists.
  const headers = Object.fromEntries(Object.entries(r201.headers).filter(([k]) => /retry|ratelimit|date|server|cf-ray|content-type/i.test(k)));
  console.log(`guard statuses(first ${LIMIT})=${JSON.stringify(statuses)} r201=${r201.status} body=${r201.body && r201.body.slice(0, 200)} headers=${JSON.stringify(headers)} window(req1)=${windowOf(firstDate)} window(req201)=${windowOf(serverEpoch(r201))} valid=${sameWindow} after=${after.status}`);
}

export function handleSummary(data) {
  return writeSummary(data);
}
