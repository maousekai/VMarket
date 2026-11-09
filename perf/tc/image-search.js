// PERF-SRCH-03 (NFR-PERF-05, FR-SRCH-05, UC-21): image search p95 <= 3 s.
// 10 users each upload a photo every 5-15 s for 5 minutes (realistic use of a
// camera search, about 1 request/s). Photos: real product photos plus a large
// (~6-9 MB) PNG, all <= 10 MB as the SRS allows. A "busy" answer (429) is a failure.
import http from 'k6/http';
import { check, sleep } from 'k6';
import { BASE_URL, NFR, SUMMARY_TREND_STATS, blocked, buildThresholds, intEnv } from '../lib/config.js';
import { IMAGE_SEARCH } from '../lib/endpoints.js';
import { IMAGES } from '../lib/manifest.js';
import { imageSearchAvailable, requireFixture, requireServices } from '../lib/probe.js';
import { parseJson } from '../lib/api.js';
import { writeSummary } from '../lib/summary.js';

const VUS = intEnv('IMAGE_VUS', 10);
const files = IMAGES.map((img) => ({ name: img.path.split(/[\\/]/).pop(), type: img.type, bytes: open(img.path, 'b') }));

export const options = {
  scenarios: {
    image: { executor: 'constant-vus', exec: 'image', vus: VUS, duration: __ENV.PROFILE === 'smoke' ? '1m' : '5m' },
  },
  thresholds: buildThresholds({ endpoints: IMAGE_SEARCH, profile: 'baseline', minSamples: __ENV.PROFILE === 'smoke' ? 0 : 200 }),
  summaryTrendStats: SUMMARY_TREND_STATS,
};
// constant-vus has no stages: everything after the first 30 s is the measured phase.
const START = Date.now();

export function setup() {
  requireFixture(files.length >= 5, 'at least 5 query photos (fixtures/out/images)');
  requireServices(['search']);
  if (!imageSearchAvailable()) blocked('ai-search-service is up but image search (CNN) is not available (GET /api/ai/search/health)');
}

export function image() {
  const f = files[Math.floor(Math.random() * files.length)];
  const phase = Date.now() - START < 30000 ? 'warmup' : 'baseline-hold';
  const res = http.post(
    `${BASE_URL}/api/ai/search/image`,
    { file: http.file(f.bytes, f.name, f.type), limit: '20' },
    { tags: { endpoint: 'POST /api/ai/search/image', phase: phase === 'warmup' ? 'warmup' : 'hold' }, timeout: '30s' },
  );
  const body = parseJson(res) || {};
  check(
    res,
    {
      'image search: 200 with a ranked list': (r) => r.status === 200 && Array.isArray(body.content || body.items || body.results || body),
      'image search: not rejected as busy': (r) => r.status !== 429,
    },
    { phase: phase === 'warmup' ? 'warmup' : 'hold' },
  );
  sleep(5 + Math.random() * 10);
}

export function handleSummary(data) {
  return writeSummary(data, { target: { p95: NFR.IMAGE_SEARCH_P95_MS } });
}
