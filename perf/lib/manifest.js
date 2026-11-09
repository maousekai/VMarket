// Fixture manifest written by fixtures/prepare.mjs: ids, record counts and the
// expected state (carts, order history, contention stock, deliveries). Checks take
// their expected values from here, never from the response being checked.
// It holds no passwords, tokens or cookies.
import { SharedArray } from 'k6/data';

const PATH = __ENV.MANIFEST || '../fixtures/out/manifest.json';

function load() {
  try {
    return JSON.parse(open(PATH));
  } catch (e) {
    throw new Error(`BLOCKED: fixture manifest ${PATH} not found or invalid; run perf/fixtures/prepare.mjs first (or pass -e MANIFEST=<path>).`);
  }
}

const whole = load();
const catalog = whole.catalog || {};
export const MANIFEST_INFO = {
  runId: whole.runId,
  baseUrl: whole.baseUrl,
  createdAt: whole.createdAt,
  buyers: whole.buyers || {},
  categories: catalog.categories || [],
  activeProducts: catalog.activeProducts || 0,
  hidden: catalog.hidden || {},
  shops: catalog.shops || [],
  priceRange: catalog.priceRange || { min: 0, max: 0 },
  keywords: catalog.keywords || [],
  contention: whole.contention || [],
  seller: whole.seller || null,
  ordersUnavailable: whole.ordersUnavailable || null, // why the order fixture could not be built
};

export const PRODUCTS = new SharedArray('products', () => load().catalog.products || []); // {id, shopId, categoryId, name, variants:[{id, price, available}]}
export const CARTS = new SharedArray('carts', () => load().carts || []); // indexed by shopper buyer index
export const ORDER_HISTORY = new SharedArray('order-history', () => load().orderHistory || []); // [{buyer, orderIds}]
export const DELIVERIES = new SharedArray('deliveries', () => load().deliveries || []); // [{buyer, orderId, assignmentId, shipper}]
export const IMAGES = new SharedArray('images', () => load().images || []); // [{path, bytes, type}]

export const hiddenIds = () => Object.values(MANIFEST_INFO.hidden).filter(Boolean);
