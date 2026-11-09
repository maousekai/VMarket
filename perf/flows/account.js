// Account and order-tracking journey (UC-02, UC-07, FR-AUTH-02/06, FR-USER-01/02,
// FR-ORD-03): log in -> check the token pair (body + HttpOnly refresh cookie) ->
// profile -> address book -> order history -> an order -> refresh the access token
// -> log out (refresh token revoked, cookie cleared).
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';
import { get, post, tagPhase, think } from '../lib/api.js';
import { ORDER_HISTORY } from '../lib/manifest.js';
import { buyerEmail, login, poolIndex } from '../lib/session.js';

export const sessionsOpened = new Counter('sessions_opened');
export const sessionsClosed = new Counter('sessions_closed');

export function accountJourney() {
  const buyer = poolIndex('shopper');
  const { response, data, session } = login(buyerEmail(buyer));
  const cookie = (response.cookies.refresh_token || [])[0];
  tagPhase();
  const ok = check(response, {
    'login: access token in body, refresh token only in HttpOnly cookie': () =>
      !!data && !!data.accessToken && data.expiresIn > 0 && data.expiresIn <= 1800 && !('refreshToken' in data) && !!cookie && cookie.http_only === true,
  });
  if (!ok || !session) return sleep(1);
  sessionsOpened.add(1);
  const token = session.token;
  think(1, 3);

  get('/api/users/me', { endpoint: 'GET /api/users/me', token });
  think(1, 2);
  get('/api/users/me/addresses', { endpoint: 'GET /api/users/me/addresses', token });
  think(1, 2);
  const orders = get('/api/orders', { endpoint: 'GET /api/orders', token });
  const ids = (ORDER_HISTORY[buyer] || {}).orderIds || (Array.isArray(orders.data) ? orders.data.map((o) => o.id) : []);
  if (ids.length) {
    think(1, 2);
    get(`/api/orders/${ids[0]}`, { endpoint: 'GET /api/orders/{id}', token });
  }
  think(2, 4);

  // FR-AUTH-06: new access token from the refresh cookie (path /api/auth).
  const refreshed = post('/api/auth/refresh', { endpoint: 'POST /api/auth/refresh' });
  tagPhase();
  check(refreshed.data, { 'refresh: new access token': (d) => !!d && !!d.accessToken });

  const out = post('/api/auth/logout', { endpoint: 'POST /api/auth/logout' });
  const cleared = (out.response.cookies.refresh_token || [])[0];
  tagPhase();
  if (check(out.response, { 'logout: refresh cookie cleared': () => !!cleared && (cleared.value === '' || cleared.max_age < 0) })) sessionsClosed.add(1);
  think(2, 5);
}
