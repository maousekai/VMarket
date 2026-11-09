// Thin wrapper over k6/http. Every request is tagged with
//   endpoint: a stable name (method + route template, never the concrete id)
//   phase:    warmup / ramp / hold / spike / recover ... (config.currentPhase)
// so thresholds apply per endpoint and only in the measured phase.
import http from 'k6/http';
import exec from 'k6/execution';
import { check, sleep } from 'k6';
import { BASE_URL, currentPhase } from './config.js';

let rateLimitWarned = false; // per VU

// Tag the VU's metrics (requests, checks, custom metrics) with the current phase.
export function tagPhase() {
  try {
    exec.vu.metrics.tags.phase = currentPhase();
  } catch (_) {
    // setup()/teardown(): no VU tags; their requests use "setup:*" endpoint names
  }
}

export function request(method, path, { endpoint, token, body, headers = {}, expect = [200], timeout = '30s' } = {}) {
  if (!endpoint) throw new Error(`request ${method} ${path}: endpoint tag is required`);
  tagPhase();
  const allHeaders = { Accept: 'application/json', ...headers };
  if (body !== undefined) allHeaders['Content-Type'] = 'application/json';
  if (token) allHeaders.Authorization = `Bearer ${token}`;

  // Statuses listed in `expect` are not counted in http_req_failed.
  const response = http.request(method, BASE_URL + path, body === undefined ? null : JSON.stringify(body), {
    headers: allHeaders,
    tags: { endpoint },
    responseCallback: http.expectedStatuses(...expect),
    timeout,
  });

  if (response.status === 429 && !rateLimitWarned) {
    rateLimitWarned = true;
    console.warn(
      `HTTP 429 on ${endpoint}: a rate limit is on (gateway or Cloudflare). Capacity runs need ` +
        'APP_RATE_LIMIT_ENABLED=false on ENV-P and a Cloudflare skip rule for this IP; analyse these 429s.',
    );
  }

  const ok = check(response, { [`${endpoint} status ${expect.join('|')}`]: (r) => expect.includes(r.status) });
  return { response, ok, data: ok ? parseJson(response) : null };
}

export const get = (path, options) => request('GET', path, options);
export const post = (path, options) => request('POST', path, options);
export const patch = (path, options) => request('PATCH', path, options);
export const put = (path, options) => request('PUT', path, options);
export const del = (path, options) => request('DELETE', path, options);

export const pickOne = (items) => items[Math.floor(Math.random() * items.length)];

// Weighted choice: [[weight, value], ...]
export function weighted(entries) {
  const total = entries.reduce((n, [w]) => n + w, 0);
  let roll = Math.random() * total;
  for (const [w, value] of entries) if ((roll -= w) < 0) return value;
  return entries[entries.length - 1][1];
}

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

export function think(min = 1, max = 3) {
  sleep(min + Math.random() * Math.max(0, max - min));
}

export function query(params) {
  const parts = Object.entries(params)
    .filter(([, value]) => value !== undefined && value !== null && value !== '')
    .map(([key, value]) => `${encodeURIComponent(key)}=${encodeURIComponent(value)}`);
  return parts.length ? `?${parts.join('&')}` : '';
}
