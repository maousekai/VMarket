// Catalog workload (rule 2):
//   60 % GET /api/products?page=0&size=20
//   20 % GET /api/products?categoryId=<id>&size=20   (categories rotate)
//   20 % GET /api/products/{id}                       (valid ids rotate)
// Every response is checked: schema, pagination, ACTIVE only, no PH/PD leak.
import { check } from 'k6';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';
import { get, query, tagPhase, think } from '../lib/api.js';
import { MANIFEST_INFO, PRODUCT_IDS, hiddenIds } from '../lib/manifest.js';

export const leakedProducts = new Counter('catalog_unsellable_leaks');

const HIDDEN = new Set(hiddenIds());
const SIZE = MANIFEST_INFO.pageSize;

// Different VUs start at different offsets so they do not hit the same id together.
function rotate(length) {
  return (exec.vu.idInTest * 7919 + exec.vu.iterationInScenario) % length;
}

function checkPage(data, { categoryId } = {}) {
  const items = (data && data.content) || [];
  const leaks = items.filter((p) => p.status !== 'ACTIVE' || HIDDEN.has(p.id)).length;
  if (leaks) leakedProducts.add(leaks);
  tagPhase();
  return check(data, {
    'list has content array': (d) => !!d && Array.isArray(d.content),
    'list page/size match the request': (d) => !!d && d.page === 0 && d.size === SIZE && d.content.length <= SIZE,
    'list totals are consistent': (d) => !!d && d.totalElements >= d.content.length && d.totalPages >= 1,
    'list items have id, shopId, variants': () =>
      items.every((p) => p.id && p.shopId && Array.isArray(p.variants) && p.variants.length > 0),
    'list shows only ACTIVE, never PH/PD': () => leaks === 0,
    'category list only has that category': () => !categoryId || items.every((p) => p.categoryId === categoryId),
  });
}

export function browseOnce() {
  const roll = Math.random();
  if (roll < 0.6) {
    const { data } = get(`/api/products${query({ page: 0, size: SIZE })}`, { endpoint: 'GET /api/products' });
    checkPage(data);
  } else if (roll < 0.8) {
    const category = MANIFEST_INFO.categories[rotate(MANIFEST_INFO.categories.length)];
    const { data } = get(`/api/products${query({ categoryId: category.id, page: 0, size: SIZE })}`, {
      endpoint: 'GET /api/products?categoryId',
    });
    checkPage(data, { categoryId: category.id });
  } else {
    const id = PRODUCT_IDS[rotate(PRODUCT_IDS.length)];
    const { data } = get(`/api/products/${id}`, { endpoint: 'GET /api/products/{id}' });
    const product = data && data.product;
    tagPhase();
    check(product, {
      'detail returns the requested product': (p) => !!p && p.id === id,
      'detail product is ACTIVE with variants': (p) => !!p && p.status === 'ACTIVE' && p.variants.length > 0,
    });
  }
  think(1, 3);
}

// PH (HIDDEN) and PD (DRAFT) must not be readable by id. Run in setup/teardown
// and in the smoke profile; a leak fails the run.
export function checkHiddenNotReadable() {
  for (const [alias, id] of Object.entries(MANIFEST_INFO.hidden || {})) {
    const { response } = get(`/api/products/${id}`, { endpoint: 'GET /api/products/{id} (PH/PD)', expect: [404] });
    if (response.status !== 404) leakedProducts.add(1);
    check(response, { [`${alias} detail is not readable (404)`]: (r) => r.status === 404 });
  }
}
