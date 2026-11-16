// Preconditions checked in setup(). A service that is not deployed makes the test
// case "blocked" (Pending with a reason) instead of producing misleading failures.
import http from 'k6/http';
import { BASE_URL, blocked } from './config.js';
import { parseJson } from './api.js';
import { MANIFEST_INFO } from './manifest.js';

function status(path, token) {
  const res = http.get(BASE_URL + path, {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
    tags: { endpoint: 'setup:probe' },
    timeout: '10s',
    responseCallback: http.expectedStatuses({ min: 200, max: 599 }),
  });
  return res;
}

const PROBES = {
  product: () => status('/api/products?size=1'),
  cart: (token) => status('/api/cart', token),
  order: (token) => status('/api/orders', token),
  user: (token) => status('/api/users/me', token),
  shop: () => (MANIFEST_INFO.shops[0] ? status(`/api/shops/${MANIFEST_INFO.shops[0].id}`) : status('/api/shops/none')),
  search: () => status('/api/ai/search/health'),
  delivery: () => status('/api/delivery/health'),
};

const SERVICE_NAMES = {
  product: 'product-service',
  cart: 'cart-service',
  order: 'order-service',
  user: 'user-service',
  shop: 'shop-service',
  search: 'ai-search-service',
  delivery: 'delivery-service',
};

// true if the service answers through the gateway (any non-5xx, non-404-route answer)
export function available(service, token) {
  const res = PROBES[service](token);
  if (service === 'search') {
    const body = parseJson(res);
    return res.status === 200 && !!body && body.status === 'UP';
  }
  if (service === 'shop') return res.status === 200 || res.status === 404;
  return res.status > 0 && res.status < 500;
}

// Image search needs the CNN feature (health.image) on top of the service.
export function imageSearchAvailable() {
  const body = parseJson(PROBES.search());
  return !!body && body.status === 'UP' && (body.image === 'UP' || (body.image && body.image.status === 'UP'));
}

export function requireServices(services, token) {
  const missing = services.filter((s) => !available(s, token));
  if (missing.length) blocked(`${missing.map((s) => SERVICE_NAMES[s]).join(', ')} not reachable through the gateway (${BASE_URL})`);
}

export function requireFixture(condition, what) {
  if (!condition) blocked(`fixture missing: ${what}; run perf/fixtures/prepare.mjs`);
}
