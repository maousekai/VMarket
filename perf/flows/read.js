// NFR-PERF-01 read mix: every read API of the SRS, weighted like real traffic.
// Each response is checked against the contract and the fixture (ACTIVE only,
// no HIDDEN/DRAFT product, cart equals the fixture, order history belongs to the buyer).
import { check } from 'k6';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';
import { get, query, tagPhase, think, weighted } from '../lib/api.js';
import { CARTS, MANIFEST_INFO, ORDER_HISTORY, PRODUCTS, hiddenIds } from '../lib/manifest.js';
import { poolIndex, sessionFor } from '../lib/session.js';
import { verifyCartAnyQuantity } from './cart.js';

export const leaks = new Counter('catalog_unsellable_leaks');
const HIDDEN = new Set(hiddenIds());
const SIZE = 20;

const rotate = (n) => (exec.vu.idInTest * 7919 + exec.vu.iterationInScenario) % n;
const product = () => PRODUCTS[rotate(PRODUCTS.length)];

export function checkProductPage(data, label, { categoryId, shopId, minPrice, maxPrice } = {}) {
  const items = (data && data.content) || [];
  const bad = items.filter((p) => p.status !== 'ACTIVE' || HIDDEN.has(p.id)).length;
  if (bad) leaks.add(bad);
  tagPhase();
  return check(data, {
    [`${label}: page schema`]: (d) => !!d && Array.isArray(d.content) && d.content.length <= d.size && d.totalElements >= d.content.length,
    [`${label}: only ACTIVE, never HIDDEN/DRAFT`]: () => bad === 0,
    [`${label}: items match the filter`]: () =>
      items.every(
        (p) =>
          (!categoryId || p.categoryId === categoryId) &&
          (!shopId || p.shopId === shopId) &&
          (minPrice === undefined || (p.maxPrice >= minPrice && p.minPrice <= maxPrice)),
      ),
  });
}

const OPS = [
  [20, 'list'],
  [12, 'category'],
  [8, 'price'],
  [6, 'rating'],
  [6, 'shopProducts'],
  [18, 'detail'],
  [5, 'categories'],
  [5, 'shop'],
  [8, 'cart'],
  [4, 'orders'],
  [3, 'orderDetail'],
  [3, 'profile'],
  [2, 'addresses'],
];

export function readOnce(prepared) {
  const op = weighted(OPS);
  const buyer = poolIndex('shopper');
  const auth = () => sessionFor(buyer, prepared).token;
  switch (op) {
    case 'list': {
      const { data } = get(`/api/products${query({ page: 0, size: SIZE, sort: 'NEWEST' })}`, { endpoint: 'GET /api/products' });
      checkProductPage(data, 'list');
      break;
    }
    case 'category': {
      const c = MANIFEST_INFO.categories[rotate(MANIFEST_INFO.categories.length)];
      const { data } = get(`/api/products${query({ categoryId: c.id, page: 0, size: SIZE })}`, { endpoint: 'GET /api/products?categoryId' });
      checkProductPage(data, 'category', { categoryId: c.id });
      break;
    }
    case 'price': {
      const { min, max } = MANIFEST_INFO.priceRange;
      const lo = Math.round(min + (max - min) * 0.1);
      const hi = Math.round(min + (max - min) * 0.6);
      const { data } = get(`/api/products${query({ minPrice: lo, maxPrice: hi, sort: 'PRICE_ASC', page: 0, size: SIZE })}`, { endpoint: 'GET /api/products?price' });
      checkProductPage(data, 'price', { minPrice: lo, maxPrice: hi });
      break;
    }
    case 'rating': {
      const { data } = get(`/api/products${query({ minRating: 3, sort: 'BEST_SELLING', page: 0, size: SIZE })}`, { endpoint: 'GET /api/products?rating' });
      checkProductPage(data, 'rating');
      break;
    }
    case 'shopProducts': {
      const shop = MANIFEST_INFO.shops[rotate(MANIFEST_INFO.shops.length)];
      const { data } = get(`/api/products${query({ shopId: shop.id, page: 0, size: SIZE })}`, { endpoint: 'GET /api/products?shopId' });
      checkProductPage(data, 'shop products', { shopId: shop.id });
      break;
    }
    case 'detail': {
      const p = product();
      const { data } = get(`/api/products/${p.id}`, { endpoint: 'GET /api/products/{id}' });
      tagPhase();
      check(data, {
        'detail: requested ACTIVE product with variants': (d) => !!d && !!d.product && d.product.id === p.id && d.product.status === 'ACTIVE' && d.product.variants.length > 0,
        'detail: similar products are ACTIVE (FR-REC-02)': (d) => !!d && (d.similarProducts || []).every((s) => s.status === 'ACTIVE' && !HIDDEN.has(s.id)),
      });
      break;
    }
    case 'categories': {
      const { data } = get('/api/products/categories', { endpoint: 'GET /api/products/categories' });
      check(data, { 'category tree is a non-empty list': (d) => Array.isArray(d) && d.length > 0 });
      break;
    }
    case 'shop': {
      const shop = MANIFEST_INFO.shops[rotate(MANIFEST_INFO.shops.length)];
      const { data } = get(`/api/shops/${shop.id}`, { endpoint: 'GET /api/shops/{id}' });
      check(data, { 'shop page: the requested shop with its public info': (d) => !!d && d.id === shop.id && !!d.name });
      break;
    }
    case 'cart': {
      const { data } = get('/api/cart', { endpoint: 'GET /api/cart', token: auth() });
      if (data) verifyCartAnyQuantity(data, CARTS[buyer], 'read cart');
      break;
    }
    case 'orders': {
      const { data } = get('/api/orders', { endpoint: 'GET /api/orders', token: auth() });
      const expected = (ORDER_HISTORY[buyer] || {}).orderIds || [];
      check(data, { 'order history contains this buyer\'s fixture orders': (d) => Array.isArray(d) && expected.every((id) => d.some((o) => o.id === id)) });
      break;
    }
    case 'orderDetail': {
      const ids = (ORDER_HISTORY[buyer] || {}).orderIds || [];
      if (!ids.length) break;
      const id = ids[exec.vu.iterationInScenario % ids.length];
      const { data } = get(`/api/orders/${id}`, { endpoint: 'GET /api/orders/{id}', token: auth() });
      check(data, { 'order detail: the requested order with items': (d) => !!d && d.id === id && Array.isArray(d.items) && d.items.length > 0 });
      break;
    }
    case 'profile': {
      const { data } = get('/api/users/me', { endpoint: 'GET /api/users/me', token: auth() });
      check(data, { 'profile: own profile': (d) => !!d && (d.userId || d.id) === sessionFor(buyer, prepared).userId });
      break;
    }
    case 'addresses': {
      const { data } = get('/api/users/me/addresses', { endpoint: 'GET /api/users/me/addresses', token: auth() });
      check(data, { 'address book has a default address': (d) => Array.isArray(d) && d.some((a) => a.isDefault) });
      break;
    }
  }
  think(1, 3);
}
