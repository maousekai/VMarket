// Shop ids and seller accounts for the shop-page flows.
import { get } from './api.js';
import { login } from './session.js';

// Seed sellers created by scripts/seed-demo-data (seller.<shop-key>@seed.vmarket.test).
const dataset = JSON.parse(open('../../scripts/seed-demo-data/dataset.json'));
export const SEED_SELLER_EMAILS = dataset.shops.map((shop) => `seller.${shop.key}@seed.vmarket.test`);

// Approved shop ids, from (in order): SHOP_IDS env, the seed sellers' /api/shops/me,
// or the shops that own products in the catalog.
export function resolveShopIds({ sellerPassword, catalog } = {}) {
  if (__ENV.SHOP_IDS) return __ENV.SHOP_IDS.split(',').map((id) => id.trim()).filter(Boolean);

  if (sellerPassword) {
    const ids = [];
    for (const email of SEED_SELLER_EMAILS) {
      const { token } = login(email, sellerPassword);
      const { data } = get('/api/shops/me', { endpoint: 'setup:my-shop', token, expect: [200, 404] });
      if (data && data.status === 'APPROVED') ids.push(data.id);
    }
    if (ids.length) return ids;
  }

  if (catalog && catalog.shops.length) return catalog.shops;

  throw new Error(
    'No shop ids. Pass -e SEED_PASSWORD=<seed seller password> (after running scripts/seed-demo-data), ' +
      'or -e SHOP_IDS=id1,id2, or start product-service so shops can be read from the catalog.',
  );
}
