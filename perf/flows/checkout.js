// Checkout journey (UC-06 alternative A1 COD, FR-CART-03, FR-ORD-01, FR-PROD-02,
// FR-ORD-03): empty cart -> add 1-3 lines of one shop -> pre-checkout check ->
// place a COD order with an Idempotency-Key -> replay the same key (must return the
// same order) -> view it -> cancel it (allowed while not yet confirmed) so the
// reserved stock is released.
//
// Money check: the order total must equal the sum of the lines at the catalog
// prices recorded in the manifest. COD only; VNPay is never called.
import { check } from 'k6';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';
import { del, get, pickOne, post, put, tagPhase, think } from '../lib/api.js';
import { PRODUCTS } from '../lib/manifest.js';
import { poolIndex, sessionFor } from '../lib/session.js';

export const ordersCreated = new Counter('orders_created');
export const ordersCancelled = new Counter('orders_cancelled');
export const orderViolations = new Counter('order_invariant_violations');

const addresses = {}; // per VU: buyer -> default address id

function defaultAddress(token, buyer) {
  if (addresses[buyer]) return addresses[buyer];
  const { data } = get('/api/users/me/addresses', { endpoint: 'GET /api/users/me/addresses', token });
  const a = Array.isArray(data) && (data.find((x) => x.isDefault) || data[0]);
  if (!a) throw new Error(`buyer ${buyer} has no delivery address; the fixture creates one`);
  return (addresses[buyer] = a.id);
}

// Lines from one shop (an order is split per shop; one shop keeps the check exact).
function chooseLines() {
  const first = pickOne(PRODUCTS);
  const sameShop = PRODUCTS.filter((p) => p.shopId === first.shopId);
  const lines = [];
  for (let i = 0, n = 1 + Math.floor(Math.random() * 3); i < n; i++) {
    const p = pickOne(sameShop);
    const v = p.variants[0];
    if (lines.some((l) => l.variantId === v.id)) continue;
    lines.push({ productId: p.id, variantId: v.id, shopId: p.shopId, quantity: 1, unitPrice: v.price });
  }
  return lines;
}

export function checkoutJourney(prepared, { pool = 'order', cancel = true, note = 'perf-checkout' } = {}) {
  const buyer = poolIndex(pool);
  const { token } = sessionFor(buyer, prepared);
  const addressId = defaultAddress(token, buyer);

  del('/api/cart', { endpoint: 'DELETE /api/cart', token });
  const lines = chooseLines();
  for (const line of lines) {
    post('/api/cart/items', { endpoint: 'POST /api/cart/items', token, expect: [201], body: line });
    think(0.5, 1.5);
  }
  const validated = post('/api/cart/validate', { endpoint: 'POST /api/cart/validate', token });
  tagPhase();
  if (!check(validated.data, { 'checkout: pre-checkout check passes': (d) => !!d && d.ok === true })) return think(1, 3);
  think(1, 3);

  const key = `perf-${exec.vu.idInTest}-${exec.vu.iterationInScenario}-${Date.now()}`;
  const body = { addressId, note, paymentMethod: 'COD' };
  const placed = post('/api/orders', { endpoint: 'POST /api/orders', token, expect: [201], headers: { 'Idempotency-Key': key }, body });
  if (!placed.data) return think(1, 3);
  ordersCreated.add(1);
  const order = placed.data;
  const expectedTotal = lines.reduce((n, l) => n + l.unitPrice * l.quantity, 0);
  const replay = post('/api/orders', { endpoint: 'POST /api/orders (replay)', token, expect: [200, 201], headers: { 'Idempotency-Key': key }, body });
  tagPhase();
  const ok = check(order, {
    'order: COD, waiting for confirmation (UC-06 A1)': (o) => o.paymentMethod === 'COD' && o.status === 'PENDING',
    'order: total equals the catalog prices of the lines': (o) => Number(o.totalAmount) === expectedTotal,
    'order: same Idempotency-Key returns the same order': () => !!replay.data && replay.data.id === order.id,
  });
  if (!ok) {
    orderViolations.add(1);
    console.error(`order invariant: ${order.id} total ${order.totalAmount} expected ${expectedTotal}, replay ${replay.data && replay.data.id}`);
  }
  think(1, 2);
  get(`/api/orders/${order.id}`, { endpoint: 'GET /api/orders/{id}', token });
  think(1, 2);
  if (cancel) {
    const { response } = put(`/api/orders/${order.id}/cancel`, { endpoint: 'PUT /api/orders/{id}/cancel', token, expect: [200, 409] });
    if (response.status === 200) ordersCancelled.add(1);
  }
  think(1, 3);
  return order;
}
