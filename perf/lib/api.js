// Thin wrapper over k6/http: every request is tagged with a stable `endpoint`
// name (method + route template, never the concrete id) so thresholds and the
// summary report one line per endpoint instead of one per URL.
import http from 'k6/http';
import { check, sleep } from 'k6';
import { BASE_URL, THINK_MAX, THINK_MIN } from './config.js';

let rateLimitWarned = false; // per VU

export function request(method, path, { endpoint, token, body, headers = {}, expect = [200], params = {} } = {}) {
  if (!endpoint) throw new Error(`request ${method} ${path}: endpoint tag is required`);
  const allHeaders = { Accept: 'application/json', ...headers };
  if (body !== undefined) allHeaders['Content-Type'] = 'application/json';
  if (token) allHeaders.Authorization = `Bearer ${token}`;

  // Statuses listed in `expect` are not counted in http_req_failed.
  const response = http.request(method, BASE_URL + path, body === undefined ? null : JSON.stringify(body), {
    headers: allHeaders,
    tags: { endpoint, ...(params.tags || {}) },
    responseCallback: http.expectedStatuses(...expect),
    timeout: params.timeout || '30s',
  });

  if (response.status === 429 && !rateLimitWarned) {
    rateLimitWarned = true;
    console.warn(
      'HTTP 429 from the gateway: its per-IP rate limit is on. On the test environment only, restart ' +
        'api-gateway with APP_RATE_LIMIT_ENABLED=false (see perf/README.md); results are invalid otherwise.',
    );
  }

  const ok = check(response, { [`${endpoint} -> ${expect.join('|')}`]: (r) => expect.includes(r.status) });
  return { response, ok, data: ok ? parseJson(response) : null };
}

export const get = (path, options) => request('GET', path, options);
export const post = (path, options) => request('POST', path, options);
export const patch = (path, options) => request('PATCH', path, options);
export const del = (path, options) => request('DELETE', path, options);

export function parseJson(response) {
  if (!response.body) return null;
  try {
    return response.json();
  } catch (_) {
    return null;
  }
}

export function errorCode(response) {
  const body = parseJson(response);
  return body && body.error ? body.error.code : undefined;
}

export function think(min = THINK_MIN, max = THINK_MAX) {
  sleep(min + Math.random() * Math.max(0, max - min));
}

export function pick(items) {
  return items[Math.floor(Math.random() * items.length)];
}

export function query(params) {
  const parts = Object.entries(params)
    .filter(([, value]) => value !== undefined && value !== null && value !== '')
    .map(([key, value]) => `${encodeURIComponent(key)}=${encodeURIComponent(value)}`);
  return parts.length ? `?${parts.join('&')}` : '';
}
