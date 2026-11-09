// Guest browsing journey (UC-04, FR-PROD-05/06, FR-SHOP-03): category tree ->
// a listing (category / price filter / sort) -> 1-3 product details -> the shop
// page and its products. No login.
import { check } from 'k6';
import { get, pickOne, query, tagPhase, think, weighted } from '../lib/api.js';
import { MANIFEST_INFO, PRODUCTS } from '../lib/manifest.js';
import { checkProductPage } from './read.js';

export function browseJourney() {
  get('/api/products/categories', { endpoint: 'GET /api/products/categories' });
  think(1, 2);

  const c = pickOne(MANIFEST_INFO.categories);
  const listing = weighted([
    [5, { tag: 'GET /api/products?categoryId', params: { categoryId: c.id }, filter: { categoryId: c.id } }],
    [3, { tag: 'GET /api/products', params: { sort: 'NEWEST' }, filter: {} }],
    [2, { tag: 'GET /api/products?price', params: { minPrice: MANIFEST_INFO.priceRange.min, maxPrice: Math.round(MANIFEST_INFO.priceRange.max / 2), sort: 'PRICE_ASC' }, filter: {} }],
  ]);
  const { data } = get(`/api/products${query({ ...listing.params, page: 0, size: 20 })}`, { endpoint: listing.tag });
  checkProductPage(data, 'browse listing', listing.filter);
  think(1, 3);

  const seen = (data && data.content && data.content.length ? data.content : []).map((p) => p.id);
  for (let i = 0, n = 1 + Math.floor(Math.random() * 3); i < n; i++) {
    const id = seen.length ? pickOne(seen) : pickOne(PRODUCTS).id;
    const detail = get(`/api/products/${id}`, { endpoint: 'GET /api/products/{id}' });
    tagPhase();
    check(detail.data, { 'browse detail: ACTIVE product': (d) => !!d && !!d.product && d.product.status === 'ACTIVE' });
    think(2, 5);
  }

  const shop = pickOne(MANIFEST_INFO.shops);
  get(`/api/shops/${shop.id}`, { endpoint: 'GET /api/shops/{id}' });
  const shopProducts = get(`/api/products${query({ shopId: shop.id, page: 0, size: 20 })}`, { endpoint: 'GET /api/products?shopId' });
  checkProductPage(shopProducts.data, 'browse shop products', { shopId: shop.id });
  think(1, 3);
}
