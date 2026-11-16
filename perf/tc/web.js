// PERF-WEB-01/02 (NFR-PERF-04, NFR-USA-03): the home page shows its main content
// within 3 s on a regular network, on desktop and on a 360 px phone.
// Each load uses a fresh browser context (cold cache) with network throttling and
// measures Largest Contentful Paint plus the time until the first product card
// (the main content) is visible.
//   WEB-01: idle system | WEB-02: run.mjs starts tc/read.js (200 VUs) alongside.
//
// "Regular network" (configurable): 10 Mbit/s down, 5 Mbit/s up, 40 ms latency.
import { browser } from 'k6/browser';
import { check } from 'k6';
import exec from 'k6/execution';
import { Trend } from 'k6/metrics';
import { BASE_URL, NFR, SUMMARY_TREND_STATS, WEB_URL, intEnv } from '../lib/config.js';
import { writeSummary } from '../lib/summary.js';
import { requireFixture, requireServices } from '../lib/probe.js';
import { MANIFEST_INFO } from '../lib/manifest.js';
import http from 'k6/http';

const LOADS = __ENV.PROFILE === 'smoke' ? 2 : intEnv('WEB_LOADS', 20); // per viewport
const NETWORK = {
  latency: intEnv('NET_LATENCY_MS', 40),
  download: (intEnv('NET_DOWN_KBPS', 10000) * 1000) / 8,
  upload: (intEnv('NET_UP_KBPS', 5000) * 1000) / 8,
};
const VIEWPORTS = {
  desktop: { viewport: { width: 1366, height: 768 }, isMobile: false },
  mobile: { viewport: { width: 360, height: 740 }, isMobile: true, hasTouch: true, deviceScaleFactor: 3 },
};

const lcp = new Trend('home_lcp_ms', true);
const content = new Trend('home_main_content_ms', true);

export const options = {
  scenarios: {
    home: {
      executor: 'per-vu-iterations',
      exec: 'home',
      vus: 1,
      iterations: LOADS * 2,
      maxDuration: '30m',
      startTime: __ENV.WEB_START || '0s',
      options: { browser: { type: 'chromium' } },
    },
  },
  thresholds: {
    'home_lcp_ms{viewport:desktop}': [`p(95)<=${NFR.HOME_LCP_MS}`],
    'home_lcp_ms{viewport:mobile}': [`p(95)<=${NFR.HOME_LCP_MS}`],
    'home_main_content_ms{viewport:desktop}': [`p(95)<=${NFR.HOME_LCP_MS}`],
    'home_main_content_ms{viewport:mobile}': [`p(95)<=${NFR.HOME_LCP_MS}`],
    checks: ['rate>=0.99'],
  },
  summaryTrendStats: SUMMARY_TREND_STATS,
};

export function setup() {
  // The main content of the home page is the product grid (FR-PROD-05, FR-REC-01):
  // an empty catalog is a missing precondition, not a slow page.
  requireServices(['product']);
  requireFixture(MANIFEST_INFO.activeProducts >= 1000, 'PERF-CATALOG (>= 1000 ACTIVE products) for the home page');
  const res = http.get(`${BASE_URL}/api/products?size=1`, { tags: { endpoint: 'setup:count' } });
  const live = res.status === 200 ? (res.json() || {}).totalElements : 0;
  requireFixture(live >= 1000, `catalog on ${BASE_URL} has ${live} ACTIVE products`);
}

export async function home() {
  const name = exec.vu.iterationInScenario % 2 === 0 ? 'desktop' : 'mobile';
  exec.vu.metrics.tags.viewport = name;
  const context = await browser.newContext(VIEWPORTS[name]);
  const page = await context.newPage();
  try {
    await page.throttleNetwork(NETWORK);
    await page.goto(`${WEB_URL}/`, { waitUntil: 'load', timeout: 30000 });
    let shown = false;
    let cardAt = 0;
    try {
      await page.locator('a[href^="/products/"]').first().waitFor({ state: 'visible', timeout: 15000 });
      cardAt = await page.evaluate(() => performance.now()); // ms since navigation start
      shown = true;
    } catch (_) {
      shown = false;
    }
    const timing = await page.evaluate(
      () =>
        new Promise((resolve) => {
          let value = 0;
          new PerformanceObserver((list) => {
            for (const e of list.getEntries()) value = e.startTime;
          }).observe({ type: 'largest-contentful-paint', buffered: true });
          setTimeout(() => resolve({ lcp: value, now: performance.now() }), 500);
        }),
    );
    if (shown) content.add(cardAt, { viewport: name });
    if (timing.lcp > 0) lcp.add(timing.lcp, { viewport: name });
    check(shown, { [`home (${name}): product cards visible (main content, FR-REC/FR-PROD)`]: (s) => s });
  } finally {
    await page.close();
    await context.close();
  }
}

export function handleSummary(data) {
  return writeSummary(data, { network: NETWORK, loadsPerViewport: LOADS });
}
