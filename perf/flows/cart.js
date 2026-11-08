// Cart workload (rule 3): GET cart -> PATCH one item to quantity 1/2 (alternating)
// -> GET to verify; think 1-3 s. One VU owns one cart. No POST that keeps adding.
//
// Expected values come only from the fixture manifest (20 lines in two shops; the
// target item costs 100000), never from the response being checked. Any money,
// quantity or ownership mismatch is a data invariant violation: the run fails and
// stops (test plan: "no 1 % tolerance for wrong money").
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import { get, patch, query, tagPhase, think } from '../lib/api.js';
import { CARTS } from '../lib/manifest.js';
import { adoptSession, buyerIndexForVu, buyerSession } from '../lib/session.js';

export const invariantViolations = new Counter('cart_invariant_violations');

const key = (productId, variantId) => `${productId}|${variantId || ''}`;
let expectedQty = null; // target quantity this VU believes is stored (null = resync)
let reported = 0;

function cartFor() {
  const entry = CARTS[buyerIndexForVu()];
  if (!entry) throw new Error(`No fixture cart for buyer ${buyerIndexForVu()}; prepare.mjs creates PERF_CARTS carts.`);
  return entry;
}

// Independent expectation for target quantity q.
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

// Returns the list of mismatches (empty = cart is exactly as expected).
function compare(cart, entry, q, acceptedQs) {
  const problems = [];
  if (!cart) return ['no cart body'];
  if (cart.userId !== entry.userId) problems.push(`owner ${cart.userId} != ${entry.userId}`);
  const items = (cart.groups || []).flatMap((g) => g.items || []);
  if (items.length !== entry.items.length) problems.push(`lines ${items.length} != ${entry.items.length}`);
  if ((cart.groups || []).length !== 2) problems.push(`groups ${(cart.groups || []).length} != 2`);

  // Resync mode: read the stored target quantity, accept only 1 or 2.
  if (q === null) {
    const target = items.find((i) => key(i.productId, i.variantId) === key(entry.target.productId, entry.target.variantId));
    q = target && acceptedQs.includes(target.quantity) ? target.quantity : null;
    if (q === null) return problems.concat([`target quantity ${target ? target.quantity : 'missing'} not in ${acceptedQs}`]);
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
    if (Number(item.unitPrice) !== exp.unitPrice) problems.push(`unitPrice ${item.productId} ${item.unitPrice} != ${exp.unitPrice}`);
    if (Number(item.lineTotal) !== exp.lineTotal) problems.push(`lineTotal ${item.productId} ${item.lineTotal} != ${exp.lineTotal}`);
  }
  for (const group of cart.groups || []) {
    if (Number(group.subtotal) !== want.groups[group.shopId]) problems.push(`subtotal ${group.shopId} ${group.subtotal} != ${want.groups[group.shopId]}`);
  }
  if (cart.totalQuantity !== want.totalQuantity) problems.push(`totalQuantity ${cart.totalQuantity} != ${want.totalQuantity}`);
  if (Number(cart.totalAmount) !== want.totalAmount) problems.push(`totalAmount ${cart.totalAmount} != ${want.totalAmount}`);
  if (Number(cart.totalAmount) < 0 || cart.totalQuantity < 0) problems.push('negative total');
  return problems.length ? problems : { q };
}

function verify(label, cart, entry, q) {
  const result = compare(cart, entry, q, [1, 2]);
  const ok = !Array.isArray(result);
  tagPhase();
  check(cart, { [`${label}: cart matches fixture (money/qty/owner)`]: () => ok });
  if (!ok) {
    invariantViolations.add(1);
    if (reported++ < 3) console.error(`cart invariant (${label}) buyer ${entry.email}: ${result.slice(0, 5).join('; ')}`);
    return null;
  }
  return result.q;
}

export function cartIteration(prepared) {
  const entry = cartFor();
  adoptSession(prepared);
  const { token } = buyerSession();

  const before = get('/api/cart', { endpoint: 'GET /api/cart', token });
  if (!before.data) {
    expectedQty = null;
    return think(1, 3);
  }
  const stored = verify('GET before', before.data, entry, expectedQty);
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
  think(1, 3);
}
