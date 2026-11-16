// Cart journey (UC-05, FR-CART-01/02/03): GET cart -> PATCH one line to quantity
// 1/2 (alternating) -> GET to verify; every 3rd iteration the pre-checkout check
// (POST /api/cart/validate) must report the cart as valid. Think time 1-3 s.
//
// Expected values come only from the fixture manifest (20 lines in two shops; the
// target line costs 100000). Any money, quantity or ownership mismatch is a data
// invariant violation: the run fails and stops (no tolerance for wrong money).
import { check } from 'k6';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';
import { get, patch, post, query, tagPhase, think } from '../lib/api.js';
import { CARTS } from '../lib/manifest.js';
import { poolIndex, sessionFor } from '../lib/session.js';

export const cartViolations = new Counter('cart_invariant_violations');

const key = (productId, variantId) => `${productId}|${variantId || ''}`;
let expectedQty = null; // per VU
let reported = 0;

function expectation(entry, q) {
  const targetKey = key(entry.target.productId, entry.target.variantId);
  const lines = new Map();
  const groups = {};
  let totalQuantity = 0;
  let totalAmount = 0;
  for (const item of entry.items) {
    const k = key(item.productId, item.variantId);
    const quantity = k === targetKey ? q : item.quantity;
    const lineTotal = item.unitPrice * quantity;
    lines.set(k, { ...item, quantity, lineTotal });
    groups[item.shopId] = (groups[item.shopId] || 0) + lineTotal;
    totalQuantity += quantity;
    totalAmount += lineTotal;
  }
  return { lines, groups, totalQuantity, totalAmount };
}

// Mismatches between the cart and the fixture; q = null accepts a stored target of 1 or 2.
function compare(cart, entry, q) {
  if (!cart) return { problems: ['no cart body'] };
  const problems = [];
  if (cart.userId !== entry.userId) problems.push(`owner ${cart.userId} != ${entry.userId}`);
  const items = (cart.groups || []).flatMap((g) => g.items || []);
  if (items.length !== entry.items.length) problems.push(`lines ${items.length} != ${entry.items.length}`);
  if ((cart.groups || []).length !== 2) problems.push(`groups ${(cart.groups || []).length} != 2`);
  if (q === null) {
    const target = items.find((i) => key(i.productId, i.variantId) === key(entry.target.productId, entry.target.variantId));
    q = target && (target.quantity === 1 || target.quantity === 2) ? target.quantity : null;
    if (q === null) return { problems: problems.concat([`target quantity ${target ? target.quantity : 'missing'}`]) };
  }
  const want = expectation(entry, q);
  for (const item of items) {
    const exp = want.lines.get(key(item.productId, item.variantId));
    if (!exp) {
      problems.push(`unexpected line ${item.productId}`);
      continue;
    }
    if (item.shopId !== exp.shopId) problems.push(`shop of ${item.productId}`);
    if (item.quantity !== exp.quantity) problems.push(`qty ${item.productId} ${item.quantity} != ${exp.quantity}`);
    if (Number(item.unitPrice) !== exp.unitPrice) problems.push(`unitPrice ${item.productId}`);
    if (Number(item.lineTotal) !== exp.lineTotal) problems.push(`lineTotal ${item.productId} ${item.lineTotal} != ${exp.lineTotal}`);
  }
  for (const group of cart.groups || []) {
    if (Number(group.subtotal) !== want.groups[group.shopId]) problems.push(`subtotal ${group.shopId} ${group.subtotal} != ${want.groups[group.shopId]}`);
  }
  if (cart.totalQuantity !== want.totalQuantity) problems.push(`totalQuantity ${cart.totalQuantity} != ${want.totalQuantity}`);
  if (Number(cart.totalAmount) !== want.totalAmount) problems.push(`totalAmount ${cart.totalAmount} != ${want.totalAmount}`);
  if (Number(cart.totalAmount) < 0 || cart.totalQuantity < 0) problems.push('negative total');
  return { problems, q };
}

function verify(label, cart, entry, q) {
  const { problems, q: stored } = compare(cart, entry, q);
  const ok = problems.length === 0;
  tagPhase();
  check(cart, { [`${label}: cart matches the fixture (money, quantity, owner)`]: () => ok });
  if (!ok) {
    cartViolations.add(1);
    if (reported++ < 3) console.error(`cart invariant (${label}) ${entry.email}: ${problems.slice(0, 5).join('; ')}`);
    return null;
  }
  return stored;
}

export const verifyCartAnyQuantity = (cart, entry, label) => verify(label, cart, entry, null);

export function cartIteration(prepared) {
  const buyer = poolIndex('shopper');
  const entry = CARTS[buyer];
  if (!entry) throw new Error(`no fixture cart for buyer ${buyer}`);
  const { token } = sessionFor(buyer, prepared);

  const before = get('/api/cart', { endpoint: 'GET /api/cart', token });
  const stored = before.data ? verify('GET before', before.data, entry, expectedQty) : null;
  if (stored === null) {
    expectedQty = null;
    return think(1, 3);
  }
  const next = stored === 1 ? 2 : 1;
  const path = `/api/cart/items/${entry.target.productId}${query({ variantId: entry.target.variantId })}`;
  const updated = patch(path, { endpoint: 'PATCH /api/cart/items/{productId}', token, body: { quantity: next } });
  if (!updated.data) {
    expectedQty = null; // unknown whether it was applied; resync on the next GET
    return think(1, 3);
  }
  verify('PATCH response', updated.data, entry, next);
  const after = get('/api/cart', { endpoint: 'GET /api/cart', token });
  expectedQty = after.data ? verify('GET after', after.data, entry, next) : null;

  if (exec.vu.iterationInScenario % 3 === 0) {
    // FR-CART-03: prices and stock re-checked; the fixture uses current prices.
    const { data } = post('/api/cart/validate', { endpoint: 'POST /api/cart/validate', token });
    tagPhase();
    check(data, { 'pre-checkout check: cart valid, no price/stock issue': (d) => !!d && d.ok === true && (d.issues || []).length === 0 });
  }
  think(1, 3);
}
