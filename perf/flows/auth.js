// Auth workload (rule 4): login -> check status/body/cookie -> logout to clean the
// session -> 3 s think time. login and logout are tagged separately; the login
// threshold only covers the login request.
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';
import { requireEnv } from '../lib/config.js';
import { parseJson, post, tagPhase } from '../lib/api.js';
import { buyerEmail, buyerIndexForVu, login } from '../lib/session.js';

export const sessionsCreated = new Counter('sessions_created');
export const sessionsClosed = new Counter('sessions_closed');

const COOKIE = 'refresh_token';

export function loginLogout() {
  const email = buyerEmail(buyerIndexForVu());
  const { response, data } = login(email, requireEnv('PERF_PASSWORD'));
  const cookie = (response.cookies[COOKIE] || [])[0];
  const loggedIn = check(response, {
    'login body has accessToken': () => !!(data && data.accessToken),
    'login tokenType Bearer': () => !!data && data.tokenType === 'Bearer',
    'login expiresIn > 0': () => !!data && data.expiresIn > 0,
    'login body has no refresh token': () => !!data && !('refreshToken' in data) && !('rawRefreshToken' in data),
    'login sets HttpOnly refresh_token cookie': () => !!cookie && cookie.value !== '' && cookie.http_only === true,
  });
  if (!loggedIn) {
    sleep(1); // a failed login is not retried in a tight loop
    return;
  }
  sessionsCreated.add(1);

  // The jar sends the refresh_token cookie (path /api/auth) automatically.
  const out = post('/api/auth/logout', { endpoint: 'POST /api/auth/logout' });
  const cleared = (out.response.cookies[COOKIE] || [])[0];
  tagPhase();
  const closed = check(out.response, {
    'logout clears refresh_token cookie': () => !!cleared && (cleared.value === '' || cleared.max_age < 0) /* Go parses Max-Age=0 as -1 */,
    'logout returns a message': () => !!parseJson(out.response),
  });
  if (out.ok && closed) sessionsClosed.add(1);
  sleep(3);
}
