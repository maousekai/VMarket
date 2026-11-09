// PERF-LOAD-06 (FR-ORD-01, FR-PROD-02, UC-06 A4 and post-condition "tồn kho nhất
// quán"): 100 buyers place COD orders at the same time on 5 products that have
// only 50 units each, for 5 minutes (demand >> stock).
//
// After the run settles, for every contested variant:
//   - the quantity of orders that are still active never exceeds the stock (no oversell)
//   - reservedStock equals that quantity, availableStock = stock - reserved >= 0
//   - replaying an Idempotency-Key returned the same order (checked in the flow)
//   - out-of-stock orders were refused or cancelled, never left half-done
// Then every order of the run is cancelled and the reservation must return to 0
// within 60 s (stock released on cancel).
import { check, sleep } from 'k6';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';
import { NFR, SUMMARY_TREND_STATS, THRESHOLD_KINDS, blocked, buildThresholds, guardWritableTarget, intEnv, requireEnv } from '../lib/config.js';
import { del, get, pickOne, post, put } from '../lib/api.js';
import { MANIFEST_INFO } from '../lib/manifest.js';
import { POOLS, poolIndex, prelogin, range, sessionFor } from '../lib/session.js';
import { requireServices } from '../lib/probe.js';
import { writeSummary } from '../lib/summary.js';

requireEnv('PERF_PASSWORD');
guardWritableTarget('tc/contention.js');

const VUS = intEnv('CONTENTION_VUS', 100);
const stockViolations = new Counter('stock_invariant_violations');
const placed = new Counter('orders_created');
const refused = new Counter('orders_refused_stock');
const dupes = new Counter('order_invariant_violations');
THRESHOLD_KINDS['orders_refused_stock|count>0'] = 'invalid';

export const options = {
  scenarios: { buyers: { executor: 'constant-vus', exec: 'buyer', vus: __ENV.PROFILE === 'smoke' ? 2 : VUS, duration: __ENV.PROFILE === 'smoke' ? '1m' : '5m' } },
  thresholds: buildThresholds({
    profile: 'smoke', // no p95 acceptance here: correctness under contention is the point
    info: ['POST /api/orders', 'POST /api/cart/items', 'DELETE /api/cart'],
    data: { stock_invariant_violations: 'count==0', order_invariant_violations: 'count==0' },
    custom: [['orders_refused_stock', 'count>0', 'invalid']],
  }),
  summaryTrendStats: SUMMARY_TREND_STATS,
  setupTimeout: '10m',
  teardownTimeout: '15m',
};
// constant-vus: drop the smoke-only "rate==0" checks so expected refusals do not fail it.
delete options.thresholds['http_req_failed{phase:hold}'];
delete options.thresholds['checks{phase:hold}'];

function variantState(c, token) {
  const { data } = get(`/api/products/${c.productId}`, { endpoint: 'setup:stock', token });
  const v = data && data.product && data.product.variants.find((x) => x.id === c.variantId);
  return v ? { stock: v.stock, reserved: v.reservedStock, available: v.availableStock } : null;
}

export function setup() {
  if (MANIFEST_INFO.ordersUnavailable) blocked(`placing an order fails, contention cannot be measured: ${MANIFEST_INFO.ordersUnavailable}`);
  const count = __ENV.PROFILE === 'smoke' ? 2 : VUS;
  const sessions = prelogin(range(POOLS.order.start, count));
  requireServices(['product', 'cart', 'order', 'user'], Object.values(sessions)[0].token);
  const products = MANIFEST_INFO.contention;
  if (products.length < 1) blocked('fixture missing: contention products (manifest.contention)');
  for (const c of products) {
    const s = variantState(c);
    if (!s || s.reserved !== 0 || s.available !== c.stock) blocked(`contention product ${c.productId} is not clean (reserved ${s && s.reserved}); cancel leftover orders or re-run the fixture`);
  }
  return { sessions, note: `perf-contention-${Date.now()}`, count };
}

export function buyer(data) {
  const index = poolIndex('order');
  const { token } = sessionFor(index, data.sessions);
  const c = pickOne(MANIFEST_INFO.contention);
  const quantity = 1 + Math.floor(Math.random() * 2);
  del('/api/cart', { endpoint: 'DELETE /api/cart', token });
  post('/api/cart/items', { endpoint: 'POST /api/cart/items', token, expect: [201], body: { productId: c.productId, variantId: c.variantId, shopId: c.shopId, quantity, unitPrice: c.price } });
  const addresses = get('/api/users/me/addresses', { endpoint: 'GET /api/users/me/addresses', token }).data || [];
  const addressId = (addresses.find((a) => a.isDefault) || addresses[0] || {}).id;
  const key = `perf-ct-${exec.vu.idInTest}-${exec.vu.iterationInScenario}-${Date.now()}`;
  const body = { addressId, note: data.note, paymentMethod: 'COD' };
  const first = post('/api/orders', { endpoint: 'POST /api/orders', token, expect: [201, 409, 422], headers: { 'Idempotency-Key': key }, body });
  if (first.response.status === 201 && first.data) {
    placed.add(1);
    const again = post('/api/orders', { endpoint: 'POST /api/orders (replay)', token, expect: [200, 201], headers: { 'Idempotency-Key': key }, body });
    if (!check(again.data, { 'replay returns the same order (no duplicate)': (d) => !!d && d.id === first.data.id })) dupes.add(1);
  } else if (first.response.status === 409 || first.response.status === 422) {
    refused.add(1);
  }
  sleep(0.5 + Math.random());
}

export function teardown(data) {
  sleep(20); // let StockReserved / StockReleased events settle
  const indices = range(POOLS.order.start, data.count);
  const orders = [];
  for (const index of indices) {
    const { token } = sessionFor(index, data.sessions);
    const list = get('/api/orders', { endpoint: 'setup:orders', token }).data || [];
    for (const o of list) if (o.note === data.note) orders.push({ ...o, token });
  }
  const systemCancelled = orders.filter((o) => o.status === 'CANCELLED').length;
  if (systemCancelled) refused.add(systemCancelled);
  const active = orders.filter((o) => o.status !== 'CANCELLED');
  let ok = true;
  for (const c of MANIFEST_INFO.contention) {
    const qty = active.reduce((n, o) => n + o.items.filter((i) => i.variantId === c.variantId).reduce((m, i) => m + i.quantity, 0), 0);
    const s = variantState(c);
    const good = !!s && qty <= c.stock && s.reserved === qty && s.available === s.stock - s.reserved && s.available >= 0;
    if (!good) {
      ok = false;
      console.error(`stock invariant ${c.productId}: active qty ${qty}, stock ${s && s.stock}, reserved ${s && s.reserved}, available ${s && s.available}`);
    }
    console.log(`contention ${c.productId}: stock ${c.stock}, active order qty ${qty}, reserved ${s && s.reserved}`);
  }
  if (!check(ok, { 'no oversell; reserved stock equals active orders (UC-06)': (x) => x })) stockViolations.add(1);

  // Clean up: cancel every active order of the run; reservations must return to 0.
  for (const o of active) put(`/api/orders/${o.id}/cancel`, { endpoint: 'setup:cancel', token: o.token, expect: [200, 409] });
  const deadline = Date.now() + 60000;
  let released = false;
  while (!released && Date.now() < deadline) {
    sleep(3);
    released = MANIFEST_INFO.contention.every((c) => {
      const s = variantState(c);
      return s && s.reserved === 0 && s.available === c.stock;
    });
  }
  if (!check(released, { 'stock released within 60 s after cancel (FR-PROD-02)': (x) => x })) stockViolations.add(1);
  console.log(`orders placed ${orders.length}, active ${active.length}, cancelled by the system ${systemCancelled}`);
}

export function handleSummary(data) {
  return writeSummary(data, { target: { orderP95: NFR.ORDER_P95_MS } });
}
