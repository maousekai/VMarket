// Phase 2 flow: an anonymous shopper browsing the catalog (all public GETs).
import { get, pick, query, think } from '../lib/api.js';

const SORTS = ['NEWEST', 'NEWEST', 'BEST_SELLING', 'PRICE_ASC', 'PRICE_DESC'];

// Home -> categories -> a category listing (maybe page 2) -> 2-3 product details.
export function browseCatalog(catalog) {
  get('/api/products/categories', { endpoint: 'GET /api/products/categories' });
  think(0.5, 1.5);

  const categoryId = catalog.categories.length && Math.random() < 0.8 ? pick(catalog.categories) : undefined;
  const sort = pick(SORTS);
  get(`/api/products${query({ categoryId, sort, page: 0, size: 20 })}`, { endpoint: 'GET /api/products' });
  think();

  if (Math.random() < 0.3) {
    get(`/api/products${query({ categoryId, sort, page: 1, size: 20 })}`, { endpoint: 'GET /api/products' });
    think();
  }

  if (Math.random() < 0.2) {
    get('/api/products/brands', { endpoint: 'GET /api/products/brands' });
    think(0.5, 1.5);
  }

  const details = 2 + Math.floor(Math.random() * 2);
  for (let i = 0; i < details; i++) {
    get(`/api/products/${pick(catalog.products).id}`, { endpoint: 'GET /api/products/{id}' });
    think();
  }
}
