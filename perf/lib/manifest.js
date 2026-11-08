// Fixture manifest written by fixtures/prepare.mjs. It holds the ids, record counts
// and the expected cart contents, so checks never take their expected values from
// the response under test. No passwords or tokens are stored in it.
import { SharedArray } from 'k6/data';

const PATH = __ENV.MANIFEST || '../fixtures/out/manifest.json';

function load() {
  let raw;
  try {
    raw = open(PATH);
  } catch (e) {
    throw new Error(`Cannot read the fixture manifest at ${PATH}. Run fixtures/prepare.mjs first, or pass -e MANIFEST=<path>.`);
  }
  return JSON.parse(raw);
}

// Small parts are copied per VU; the large arrays are shared read-only between VUs.
const whole = load();
export const MANIFEST_INFO = {
  runId: whole.runId,
  baseUrl: whole.baseUrl,
  createdAt: whole.createdAt,
  buyers: whole.buyers,
  categories: whole.catalog.categories,
  activeProducts: whole.catalog.activeProducts,
  hidden: whole.catalog.hidden,
  pageSize: whole.catalog.pageSize || 20,
};

export const PRODUCT_IDS = new SharedArray('product-ids', () => load().catalog.productIds || []);
export const CARTS = new SharedArray('carts', () => load().carts || []);

export function hiddenIds() {
  return Object.values(MANIFEST_INFO.hidden || {}).filter(Boolean);
}
