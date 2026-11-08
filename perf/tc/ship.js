// PERF-SHIP-01/02 (NFR-PERF-05, FR-SHIP-04/05, UC-23/24): a shipper's position
// reaches the buyer within 5 s.
// One VU per active delivery: the buyer opens the tracking WebSocket for the order
// (tracking ticket), the shipper sends a GPS position every 10-30 s (FR-SHIP-04).
// Latency = time from just before the shipper's request until the buyer's socket
// receives that exact position (both clocks are the same machine).
//   SHIP-01: idle system | SHIP-02: run.mjs starts tc/read.js (200 VUs) alongside.
import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';
import { WebSocket } from 'k6/websockets';
import { setTimeout } from 'k6/timers';
import { Counter, Rate, Trend } from 'k6/metrics';
import { BASE_URL, NFR, SUMMARY_TREND_STATS, THRESHOLD_KINDS, blocked, guardWritableTarget, intEnv, requireEnv } from '../lib/config.js';
import { DELIVERIES } from '../lib/manifest.js';
import { buyerEmail, login } from '../lib/session.js';
import { requireServices } from '../lib/probe.js';
import { writeSummary } from '../lib/summary.js';

requireEnv('PERF_PASSWORD');
guardWritableTarget('tc/ship.js');

const SMOKE = __ENV.PROFILE === 'smoke';
const COUNT = Math.min(DELIVERIES.length, SMOKE ? 2 : intEnv('SHIP_DELIVERIES', 50));
const DURATION_MS = (SMOKE ? 1 : intEnv('SHIP_MINUTES', 10)) * 60000;
const MISS_AFTER_MS = 15000;

THRESHOLD_KINDS['ship_location_samples|count>=200'] = 'samples';
const latency = new Trend('ship_location_latency_ms', true);
const samples = new Counter('ship_location_samples');
const missed = new Rate('ship_location_missed');
const sendFailed = new Rate('ship_location_send_failed');

export const options = {
  scenarios: { tracking: { executor: 'per-vu-iterations', exec: 'track', vus: Math.max(COUNT, 1), iterations: 1, maxDuration: `${DURATION_MS / 60000 + 3}m` } },
  thresholds: {
    ship_location_latency_ms: [`p(95)<=${NFR.SHIP_LATENCY_MS}`],
    ...(SMOKE ? {} : { ship_location_samples: ['count>=200'] }),
    ship_location_missed: ['rate<0.01'],
    ship_location_send_failed: ['rate<0.01'],
    checks: ['rate>=0.99'],
  },
  summaryTrendStats: SUMMARY_TREND_STATS,
  setupTimeout: '5m',
};

export function setup() {
  if (COUNT < 1) blocked('fixture missing: deliveries in transit (manifest.deliveries); run prepare.mjs --only=deliveries');
  const pw = requireEnv('PERF_PASSWORD');
  const out = [];
  for (const d of DELIVERIES.slice(0, COUNT)) {
    const buyer = login(buyerEmail(d.buyer), pw, { endpoint: 'setup:login' }).session;
    const shipper = login(d.shipperEmail, pw, { endpoint: 'setup:login' }).session;
    if (!buyer || !shipper) blocked(`cannot log in buyer/shipper of delivery ${d.assignmentId}`);
    out.push({ ...d, buyerToken: buyer.token, shipperToken: shipper.token });
    if (out.length === 1) requireServices(['delivery', 'order'], buyer.token);
  }
  return out;
}

const wait = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const key = (lat, lng) => `${Number(lat).toFixed(5)},${Number(lng).toFixed(5)}`;

export async function track(data) {
  const d = data[(exec.vu.idInTest - 1) % data.length];
  const ticketRes = http.post(`${BASE_URL}/api/delivery/orders/${d.orderId}/tracking-ticket`, null, {
    headers: { Authorization: `Bearer ${d.buyerToken}` },
    tags: { endpoint: 'POST /api/delivery/orders/{id}/tracking-ticket' },
  });
  const ticket = ticketRes.status === 200 ? ticketRes.json() : null;
  if (!check(ticket, { 'tracking ticket issued to the buyer of the order': (t) => !!t && !!t.webSocketUrl })) return;

  const pending = new Map(); // position -> send time
  let opened = false;
  const ws = new WebSocket(ticket.webSocketUrl);
  ws.onopen = () => (opened = true);
  ws.onmessage = (event) => {
    let msg;
    try {
      msg = JSON.parse(event.data);
    } catch (_) {
      return;
    }
    const loc = msg && msg.location;
    if (!loc || loc.latitude === undefined) return;
    const k = key(loc.latitude, loc.longitude);
    if (pending.has(k)) {
      latency.add(Date.now() - pending.get(k));
      samples.add(1);
      missed.add(false);
      pending.delete(k);
    }
  };
  for (let i = 0; i < 50 && !opened; i++) await wait(100);
  check(opened, { 'buyer tracking socket opened': (o) => o });

  const end = Date.now() + DURATION_MS;
  while (Date.now() < end) {
    await wait(10000 + Math.random() * 20000); // FR-SHIP-04: every 10-30 s
    const lat = 16.04 + Math.random() * 0.04;
    const lng = 108.19 + Math.random() * 0.05;
    pending.set(key(lat, lng), Date.now());
    const res = http.post(`${BASE_URL}/api/delivery/shipper/assignments/${d.assignmentId}/location`, JSON.stringify({ latitude: lat, longitude: lng }), {
      headers: { Authorization: `Bearer ${d.shipperToken}`, 'Content-Type': 'application/json' },
      tags: { endpoint: 'POST /api/delivery/shipper/assignments/{id}/location' },
    });
    sendFailed.add(res.status !== 200);
    if (res.status !== 200) pending.delete(key(lat, lng));
    for (const [k, at] of pending) {
      if (Date.now() - at > MISS_AFTER_MS) {
        missed.add(true);
        pending.delete(k);
      }
    }
  }
  await wait(MISS_AFTER_MS);
  for (const [, at] of pending) if (Date.now() - at > MISS_AFTER_MS) missed.add(true);
  ws.close();
}

export function handleSummary(data) {
  return writeSummary(data, { deliveries: COUNT });
}
