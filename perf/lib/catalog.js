// Reads the seeded catalog once in setup() so VUs request varied, real ids
// (one hot id would only measure the cache).
import { get, query } from './api.js';
import { intEnv } from './config.js';

const PAGE_SIZE = 100; // product-service max

export function loadCatalog({ requireStock = false } = {}) {
  const maxProducts = intEnv('MAX_PRODUCTS', 2000);

  const categories = [];
  const tree = get('/api/products/categories', { endpoint: 'setup:categories' }).data || [];
  const walk = (nodes) =>
    nodes.forEach((node) => {
      if (node.active !== false) categories.push(node.id);
      walk(node.children || []);
    });
  walk(tree);

  const products = [];
  for (let page = 0; products.length < maxProducts; page++) {
    const { data } = get(`/api/products${query({ page, size: PAGE_SIZE })}`, { endpoint: 'setup:products' });
    if (!data || !data.content || data.content.length === 0) break;
    for (const product of data.content) {
      const variants = (product.variants || [])
        .filter((v) => !requireStock || v.availableStock > 0)
        .map((v) => ({ id: v.id, price: v.price, availableStock: v.availableStock }));
      if (requireStock && variants.length === 0) continue;
      products.push({ id: product.id, shopId: product.shopId, categoryId: product.categoryId, variants });
    }
    if (page + 1 >= data.totalPages) break;
  }

  const shops = [...new Set(products.map((p) => p.shopId))];
  if (products.length === 0) {
    throw new Error(
      'No products found. Start product-service and run scripts/seed-demo-data/seed.mjs first (see perf/README.md).',
    );
  }
  console.log(`catalog: ${products.length} products, ${shops.length} shops, ${categories.length} categories`);
  return { categories, products, shops };
}
