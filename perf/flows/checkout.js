// Phase 4 flow: cart and COD checkout.
//
// Safety: COD only, never PayOS; no payment/refund endpoints. Each placed order
// is cancelled right away (still PENDING) so the reserved stock is released and
// the seeded inventory is not drained by a long run. Run against a test DB only.
import { Counter, Rate } from 'k6/metrics';
import exec from 'k6/execution';
import { del, get, patch, pick, post, request, think } from '../lib/api.js';
import { buyerToken } from '../lib/session.js';

export const ordersCreated = new Counter('orders_created');
export const ordersCancelled = new Counter('orders_cancelled');
export const idempotencyHeld = new Rate('idempotency_replay_same_order');

const ORDER_RATIO = parseFloat(__ENV.ORDER_RATIO || '0.3');
const CANCEL_AFTER_ORDER = __ENV.CANCEL_AFTER_ORDER !== 'false';

let addressId = null; // per VU

function ensureAddress(token) {
  if (addressId) return addressId;
  const { data } = get('/api/users/me/addresses', { endpoint: 'GET /api/users/me/addresses', token });
  if (data && data.length) {
    addressId = (data.find((a) => a.isDefault) || data[0]).id;
    return addressId;
  }
  // setup/create-buyers.js normally creates it; fall back here for older accounts.
  const created = post('/api/users/me/addresses', {
    endpoint: 'POST /api/users/me/addresses',
    token,
    expect: [201],
    body: testAddress(),
  });
  if (!created.data) throw new Error('could not create a delivery address for this buyer');
  addressId = created.data.id;
  return addressId;
}

export function testAddress() {
  return {
    recipientName: 'Perf Test Buyer',
    phone: '0900000000',
    province: 'Đà Nẵng',
    ward: 'Thạch Thang',
    streetAddress: '1 Perf Test',
    note: 'perf-test',
  };
}

// Clear cart -> add 1-3 items -> view -> change a quantity -> validate,
// then (ORDER_RATIO of iterations) place a COD order, replay it, view it, cancel it.
export function cartAndCheckout(catalog) {
  const token = buyerToken();
  const address = ensureAddress(token);

  del('/api/cart', { endpoint: 'DELETE /api/cart', token });

  const items = 1 + Math.floor(Math.random() * 3);
  const added = [];
  for (let i = 0; i < items; i++) {
    const product = pick(catalog.products);
    const variant = pick(product.variants);
    const { ok } = post('/api/cart/items', {
      endpoint: 'POST /api/cart/items',
      token,
      expect: [201],
      body: { productId: product.id, variantId: variant.id, shopId: product.shopId, quantity: 1, unitPrice: variant.price },
    });
    if (ok) added.push({ productId: product.id, variantId: variant.id });
    think(0.5, 2);
  }

  get('/api/cart', { endpoint: 'GET /api/cart', token });
  think();

  if (added.length) {
    const item = pick(added);
    patch(`/api/cart/items/${item.productId}?variantId=${encodeURIComponent(item.variantId)}`, {
      endpoint: 'PATCH /api/cart/items/{productId}',
      token,
      body: { quantity: 2 },
    });
    think(0.5, 1.5);
  }

  post('/api/cart/validate', { endpoint: 'POST /api/cart/validate', token });
  think();

  if (!added.length || Math.random() >= ORDER_RATIO) return;

  const key = `perf-${exec.vu.idInTest}-${exec.vu.iterationInScenario}-${Date.now()}`;
  const body = { addressId: address, note: 'perf-test', paymentMethod: 'COD' };
  const placed = post('/api/orders', {
    endpoint: 'POST /api/orders',
    token,
    expect: [201],
    headers: { 'Idempotency-Key': key },
    body,
  });
  if (!placed.data) return;
  ordersCreated.add(1);

  // Same key again must return the same order, not a second one.
  const replay = post('/api/orders', {
    endpoint: 'POST /api/orders (replay)',
    token,
    expect: [200, 201],
    headers: { 'Idempotency-Key': key },
    body,
  });
  idempotencyHeld.add(!!replay.data && replay.data.id === placed.data.id);
  think();

  get('/api/orders', { endpoint: 'GET /api/orders', token });
  think(0.5, 1.5);
  get(`/api/orders/${placed.data.id}`, { endpoint: 'GET /api/orders/{orderId}', token });

  if (CANCEL_AFTER_ORDER) {
    // 409 ORDER_NOT_CANCELLABLE: something already moved the order past PENDING;
    // not a load failure, but the stock stays reserved, so it is not counted.
    const { response } = request('PUT', `/api/orders/${placed.data.id}/cancel`, {
      endpoint: 'PUT /api/orders/{orderId}/cancel',
      token,
      expect: [200, 409],
    });
    if (response.status === 200) ordersCancelled.add(1);
  }
  think();
}
