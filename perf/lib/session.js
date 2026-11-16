// Test accounts and sessions. One VU = one account with its own cookie jar.
//
// Buyer pools (fixtures/prepare.mjs creates them, the manifest records the sizes):
//   shopper   0 .. 399   read, cart and account journeys; each has a fixture cart
//   order   400 .. 799   checkout journeys (they build and empty their own carts)
//   delivery 800 .. 849  buyers of the orders being delivered (PERF-SHIP)
// Pools are as large as the highest VU count of any run (stress: 400), so
// (vu id - 1) % pool size never gives two VUs of one process the same account.
import exec from 'k6/execution';
import http from 'k6/http';
import encoding from 'k6/encoding';
import { BASE_URL, requireEnv } from './config.js';
import { parseJson, post } from './api.js';
import { MANIFEST_INFO } from './manifest.js';

const REFRESH_MARGIN_MS = 60 * 1000;
const BUYERS = MANIFEST_INFO.buyers || {};
export const POOLS = BUYERS.pools || { shopper: { start: 0, size: 400 }, order: { start: 400, size: 400 }, delivery: { start: 800, size: 50 } };
const PREFIX = __ENV.BUYER_PREFIX || BUYERS.prefix || 'perf.p73';
const DOMAIN = BUYERS.domain || 'perf.vmarket.test';

export const buyerUsername = (index) => `${PREFIX}.${String(index).padStart(4, '0')}`;
export const buyerEmail = (index) => `${buyerUsername(index)}@${DOMAIN}`;

export function poolIndex(pool, vuId = exec.vu.idInTest) {
  const p = POOLS[pool];
  return p.start + ((vuId - 1) % p.size);
}

// 401 / 423 mean the fixture or PERF_PASSWORD is wrong, not that the system is slow.
// Retrying would lock the test accounts (5 wrong passwords -> 15 min lock), so stop.
export function abortOnCredentialError(response, email) {
  if (response.status === 401 || response.status === 423) {
    exec.test.abort(`Login for ${email} returned HTTP ${response.status}: fixture or PERF_PASSWORD problem; stopping so test accounts are not locked.`);
  }
}

export function login(email, password = requireEnv('PERF_PASSWORD'), { endpoint = 'POST /api/auth/login' } = {}) {
  const { response, data } = post('/api/auth/login', { endpoint, body: { email, password } });
  abortOnCredentialError(response, email);
  return { response, data, session: data && data.accessToken ? toSession(data, email) : null };
}

function toSession(data, email) {
  return { token: data.accessToken, expiresAt: Date.now() + data.expiresIn * 1000 - REFRESH_MARGIN_MS, email, userId: userIdFromToken(data.accessToken) };
}

export function userIdFromToken(token) {
  return JSON.parse(encoding.b64decode(token.split('.')[1], 'rawurl', 's')).sub;
}

// setup(): log in the given account indices before the measured window
// (sessions are handed to VUs, so logins do not distort the measured phase).
export function prelogin(indices) {
  const out = {};
  for (const index of indices) {
    const email = buyerEmail(index);
    const { response, session } = login(email, requireEnv('PERF_PASSWORD'), { endpoint: 'setup:login' });
    if (!session) throw new Error(`setup login failed for ${email}: HTTP ${response.status}`);
    const cookie = (response.cookies.refresh_token || [])[0];
    out[index] = { ...session, refreshCookie: cookie ? cookie.value : null };
  }
  return out;
}

export const range = (start, count) => Array.from({ length: count }, (_, i) => start + i);

// Long-lived session for account `index`: adopt the prepared one, refresh with the
// cookie before the access token expires, fall back to a fresh login.
const sessions = {}; // per VU
export function sessionFor(index, prepared) {
  let s = sessions[index];
  if (!s && prepared && prepared[index]) {
    const p = prepared[index];
    if (p.refreshCookie) http.cookieJar().set(BASE_URL, 'refresh_token', p.refreshCookie, { path: '/api/auth' });
    s = sessions[index] = { token: p.token, expiresAt: p.expiresAt, email: p.email, userId: p.userId };
  }
  if (s && Date.now() < s.expiresAt) return s;
  if (s) {
    const { response, data } = post('/api/auth/refresh', { endpoint: 'POST /api/auth/refresh' });
    const body = data || parseJson(response);
    if (body && body.accessToken) return (sessions[index] = { ...s, ...toSession(body, s.email) });
  }
  const email = buyerEmail(index);
  const result = login(email);
  if (!result.session) throw new Error(`login failed for ${email}: HTTP ${result.response.status}`);
  return (sessions[index] = result.session);
}
