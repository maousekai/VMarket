// Endpoint tags and their p95 budget (ms), with the requirement they verify.
// Tags are method + route template, never a concrete id.
import { NFR } from './config.js';

const R = NFR.READ_P95_MS;

// NFR-PERF-01: ordinary read APIs (R). Every read API the SRS defines for buyers.
export const READ = {
  'GET /api/products': R, // FR-PROD-05 list, newest first
  'GET /api/products?categoryId': R, // FR-PROD-05 by category
  'GET /api/products?price': R, // FR-PROD-05 price range + price sort
  'GET /api/products?rating': R, // FR-PROD-05 rating filter + best-selling sort
  'GET /api/products?shopId': R, // FR-SHOP-03 shop's product list
  'GET /api/products/{id}': R, // FR-PROD-06 detail + similar products
  'GET /api/products/categories': R, // FR-PROD-03 category tree (buyer side)
  'GET /api/shops/{id}': R, // FR-SHOP-03 public shop page
  'GET /api/cart': R, // FR-CART-02 grouped cart
  'GET /api/orders': R, // FR-ORD-03 order history
  'GET /api/orders/{id}': R, // FR-ORD-03 order detail
  'GET /api/users/me': R, // FR-USER-01 profile
  'GET /api/users/me/addresses': R, // FR-USER-02 address book
};

// NFR-PERF-02 (R)
export const SEARCH = {
  'GET /api/ai/search': NFR.SEARCH_P95_MS,
  'GET /api/ai/search/suggestions': NFR.SUGGEST_P95_MS,
};
// UC-03 exception flow: search service down -> basic name search in the catalog.
export const SEARCH_FALLBACK = { 'GET /api/products?q (fallback)': NFR.SEARCH_P95_MS };

// NFR-PERF-05 (R)
export const IMAGE_SEARCH = { 'POST /api/ai/search/image': NFR.IMAGE_SEARCH_P95_MS };

// Writes (Q): the SRS sets no latency for them; budgets keep them visible per endpoint.
export const WRITE = {
  'POST /api/auth/login': NFR.MUTATION_P95_MS,
  'POST /api/cart/items': NFR.MUTATION_P95_MS,
  'PATCH /api/cart/items/{productId}': NFR.MUTATION_P95_MS,
  'POST /api/cart/validate': NFR.MUTATION_P95_MS,
  'POST /api/orders': NFR.ORDER_P95_MS,
  'PUT /api/orders/{id}/cancel': NFR.MUTATION_P95_MS,
};

export const INFO = ['POST /api/auth/logout', 'POST /api/auth/refresh', 'DELETE /api/cart', 'POST /api/orders (replay)'];

export const pick = (obj, keys) => Object.fromEntries(keys.map((k) => [k, obj[k]]));
