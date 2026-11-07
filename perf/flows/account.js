// Phase 1 flows: buyer profile + public shop page, seller "my shop".
import { get, pick, think } from '../lib/api.js';
import { buyerToken, login } from '../lib/session.js';

// Buyer: (login once per VU) -> my profile -> a public shop page or two.
export function buyerProfileAndShops(shopIds) {
  const token = buyerToken();
  get('/api/users/me', { endpoint: 'GET /api/users/me', token });
  think();
  get(`/api/shops/${pick(shopIds)}`, { endpoint: 'GET /api/shops/{shopId}' });
  think();
  if (Math.random() < 0.5) {
    get(`/api/shops/${pick(shopIds)}`, { endpoint: 'GET /api/shops/{shopId}' });
    think();
  }
}

// Seller: (login once per VU) -> my shop. Seed sellers from scripts/seed-demo-data.
let sellerSession = null;
export function sellerMyShop(sellerEmails, password) {
  if (!sellerSession || Date.now() >= sellerSession.expiresAt) {
    sellerSession = login(sellerEmails[(__VU - 1) % sellerEmails.length], password);
  }
  get('/api/shops/me', { endpoint: 'GET /api/shops/me', token: sellerSession.token });
  think();
}
