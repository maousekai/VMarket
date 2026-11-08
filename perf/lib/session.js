// Per-VU login. Each VU logs in once with its own buyer account and reuses the
// access token until shortly before it expires. Logging in on every iteration
// would load-test BCrypt instead of the API and can trip the failed-login lock.
import exec from 'k6/execution';
import { BUYER_COUNT, BUYER_DOMAIN, BUYER_PREFIX, requireEnv } from './config.js';
import { post } from './api.js';

const REFRESH_MARGIN_SECONDS = 60;

let session = null; // module state is per VU

export function buyerIndexForVu() {
  return (exec.vu.idInTest - 1) % BUYER_COUNT;
}

export function buyerEmail(index) {
  return `${buyerUsername(index)}@${BUYER_DOMAIN}`;
}

export function buyerUsername(index) {
  return `${BUYER_PREFIX}.${String(index).padStart(4, '0')}`;
}

export function login(email, password) {
  const { data, response } = post('/api/auth/login', {
    endpoint: 'POST /api/auth/login',
    body: { email, password },
  });
  if (!data || !data.accessToken) {
    throw new Error(`login failed for ${email}: HTTP ${response.status} ${response.body}`);
  }
  return {
    email,
    token: data.accessToken,
    roles: data.roles || [],
    expiresAt: Date.now() + (data.expiresIn - REFRESH_MARGIN_SECONDS) * 1000,
  };
}

// Login for a VU's long-lived session. A failure aborts the whole test: retrying
// would loop with no think time (the failure comes before any sleep) and trip
// auth-service's lock after 5 wrong passwords, locking every test account.
export function loginOrAbort(email, password) {
  try {
    return login(email, password);
  } catch (e) {
    exec.test.abort(`Login failed, stopping the test so accounts are not locked: ${e.message}`);
    throw e; // abort() ends the test; this only stops the current iteration
  }
}

// Token for this VU's buyer, logging in only when there is no valid token yet.
export function buyerToken() {
  if (!session || Date.now() >= session.expiresAt) {
    session = loginOrAbort(buyerEmail(buyerIndexForVu()), requireEnv('PERF_PASSWORD'));
  }
  return session.token;
}
