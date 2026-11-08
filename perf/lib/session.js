// Logins for test buyers. One VU = one buyer account with its own cookie jar
// (k6 gives each VU its own jar; the refresh_token cookie is scoped to /api/auth).
import exec from 'k6/execution';
import encoding from 'k6/encoding';
import http from 'k6/http';
import { BASE_URL, BUYER_COUNT, BUYER_DOMAIN, BUYER_PREFIX, requireEnv } from './config.js';
import { parseJson, post } from './api.js';
import { MANIFEST_INFO } from './manifest.js';

const REFRESH_MARGIN_MS = 60 * 1000;

// Buyer prefix/count come from the fixture manifest; env vars only override them.
const BUYERS = (MANIFEST_INFO && MANIFEST_INFO.buyers) || {};
export const BUYER_TOTAL = __ENV.BUYER_COUNT ? BUYER_COUNT : BUYERS.count || BUYER_COUNT;
const PREFIX = __ENV.BUYER_PREFIX ? BUYER_PREFIX : BUYERS.prefix || BUYER_PREFIX;

export function buyerIndexForVu() {
  return (exec.vu.idInTest - 1) % BUYER_TOTAL;
}

export function buyerUsername(index) {
  return `${PREFIX}.${String(index).padStart(4, '0')}`;
}

export function buyerEmail(index) {
  return `${buyerUsername(index)}@${BUYER_DOMAIN}`;
}

// 401 INVALID_CREDENTIALS / 423 ACCOUNT_LOCKED mean the fixture or PERF_PASSWORD is
// wrong, not that the system is slow. Retrying would lock every test account
// (5 wrong passwords -> 15 min lock), so the whole test stops (rule 4).
export function abortOnCredentialError(response, email) {
  if (response.status === 401 || response.status === 423) {
    exec.test.abort(
      `Login for ${email} returned HTTP ${response.status}: fixture or PERF_PASSWORD problem, ` +
        'stopping so the test accounts are not locked. Re-run fixtures/prepare.mjs or fix PERF_PASSWORD.',
    );
  }
}

// POST /api/auth/login. Returns the raw response plus the parsed token (or null).
export function login(email, password, { endpoint = 'POST /api/auth/login' } = {}) {
  const { response, data } = post('/api/auth/login', { endpoint, body: { email, password } });
  abortOnCredentialError(response, email);
  return { response, data, session: data && data.accessToken ? toSession(data) : null };
}

function toSession(data) {
  return { token: data.accessToken, expiresAt: Date.now() + data.expiresIn * 1000 - REFRESH_MARGIN_MS };
}

// User id = JWT "sub" (auth-service JwtService). Used for the cart ownership check.
export function userIdFromToken(token) {
  const payload = token.split('.')[1];
  return JSON.parse(encoding.b64decode(payload, 'rawurl', 's')).sub;
}

// Long-lived session for the cart flow: log in once (before the measured window),
// refresh with the cookie before the 15-minute access token expires (rule 7).
let session = null; // module state is per VU

// setup(): log in buyers 0..count-1 before the measured window and hand each VU
// its access token plus refresh cookie (rule 7: Cart logs in before measuring).
export function preloginBuyers(count) {
  const password = requireEnv('PERF_PASSWORD');
  const sessions = [];
  for (let i = 0; i < count; i++) {
    const email = buyerEmail(i);
    const { response, session: s } = login(email, password, { endpoint: 'setup:login' });
    if (!s) throw new Error(`setup login failed for ${email}: HTTP ${response.status}`);
    const cookie = (response.cookies.refresh_token || [])[0];
    sessions.push({ ...s, email, userId: userIdFromToken(s.token), refreshCookie: cookie ? cookie.value : null });
  }
  return sessions;
}

// First call in a VU adopts the session prepared in setup() (if any).
export function adoptSession(prepared) {
  if (session || !prepared) return;
  const mine = prepared[buyerIndexForVu()];
  if (!mine) return;
  if (mine.refreshCookie) {
    http.cookieJar().set(BASE_URL, 'refresh_token', mine.refreshCookie, { path: '/api/auth' });
  }
  session = { token: mine.token, expiresAt: mine.expiresAt, email: mine.email, userId: mine.userId };
}

export function buyerSession() {
  if (session && Date.now() < session.expiresAt) return session;
  if (session) {
    const { response, data } = post('/api/auth/refresh', { endpoint: 'POST /api/auth/refresh' });
    const body = data || parseJson(response);
    if (body && body.accessToken) {
      session = { ...session, ...toSession(body) };
      return session;
    }
    // Refresh rejected (cookie lost/rotated): fall back to a fresh login.
  }
  const email = buyerEmail(buyerIndexForVu());
  const result = login(email, requireEnv('PERF_PASSWORD'));
  if (!result.session) throw new Error(`login failed for ${email}: HTTP ${result.response.status}`);
  session = { ...result.session, email, userId: userIdFromToken(result.session.token) };
  return session;
}
