#!/usr/bin/env node
// Fixture for the performance test plan (SRS v1.1, NFR-PERF-01..05). Goes through the
// real APIs behind the gateway, like scripts/seed-demo-data.
//
//   catalog     >= PERF_PRODUCTS ACTIVE products (1000) in 10 categories, PH (HIDDEN)
//               and PD (DRAFT), one 100000-VND product per shop (cart target line),
//               5 contention products with 50 units each (PERF-LOAD-06)
//   buyers      850 verified buyers with a default address, in pools:
//               shopper 0-399, order 400-799, delivery 800-849
//   history     2 cancelled COD orders for shoppers 0-199 (order history reads)
//   carts       400 carts (shoppers 0-399), 20 lines in two shops
//   deliveries  50 orders handed off, assigned to 50 shippers and in transit (PERF-SHIP)
//   images      query photos for image search (PERF-SRCH-03), one of them 6-9 MB
//
// Writes perf/fixtures/out/manifest.json (ids, counts, expected state, run id, git SHA).
// No passwords, tokens or cookies are stored. Re-running is safe; --only=carts resets
// the carts to the fixture state.
//
// Run it on the machine that runs the stack: it reads OTP codes from the auth-service
// log (AUTH_EMAIL_PROVIDER=log). See perf/README.md.
import { execFileSync, execSync } from 'node:child_process';
import { existsSync, mkdirSync, readFileSync, statSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { deflateSync } from 'node:zlib';

const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(here, '../..');
const dataset = JSON.parse(readFileSync(resolve(repoRoot, 'scripts/seed-demo-data/dataset.json'), 'utf8'));

const args = process.argv.slice(2);
const flag = (name) => args.includes(`--${name}`);
const option = (name, fallback) => (args.find((a) => a.startsWith(`--${name}=`)) || '').split('=')[1] || fallback;

const API = (process.env.BASE_URL || 'http://localhost:8080').replace(/\/$/, '');
const ADMIN_EMAIL = process.env.DEV_ADMIN_EMAIL || 'admin@vmarket.local';
const ADMIN_PASSWORD = process.env.DEV_ADMIN_PASSWORD;
const SELLER_PASSWORD = process.env.SEED_PASSWORD;
const BUYER_PASSWORD = process.env.PERF_PASSWORD;
const PRODUCTS = Number(process.env.PERF_PRODUCTS || 1000);
const PREFIX = process.env.BUYER_PREFIX || 'perf.p73';
const DOMAIN = 'perf.vmarket.test';
const POOLS = { shopper: { start: 0, size: 400 }, order: { start: 400, size: 400 }, delivery: { start: 800, size: 50 } };
const BUYERS = POOLS.delivery.start + POOLS.delivery.size;
const HISTORY_BUYERS = 200;
const OTP_LOG_CMD = process.env.OTP_LOG_CMD || 'docker compose logs --since 30m auth-service';
const OUT = resolve(option('out', resolve(here, 'out/manifest.json')));
const IMAGE_DIR = resolve(dirname(OUT), 'images');
const ALL_STEPS = ['seed', 'catalog', 'buyers', 'history', 'carts', 'deliveries', 'images'];
const STEPS = new Set(option('only', ALL_STEPS.join(',')).split(','));
const TARGET_PRICE = 100000;
const CONTENTION = { products: 5, stock: 50, price: 50000 };
const LINES_PER_SHOP = 10;
const EXTRA_CATEGORIES = [
  { key: 'home', name: 'Nhà cửa & Đời sống', sortOrder: 9 },
  { key: 'office', name: 'Văn phòng phẩm', sortOrder: 10 },
];
const PERF_NAME = /^PERF (giá cố định|tranh chấp|đồng bộ|PH|PD)/;

function fail(message) {
  console.error(`\n✗ ${message}`);
  process.exit(1);
}
const local = /^https?:\/\/(localhost|127\.0\.0\.1|\[::1\])(:\d+)?$/.test(API);
if (!local && !flag('allow-remote')) fail(`BASE_URL=${API} is not local. Pass --allow-remote only for the performance environment (ENV-P).`);
const needsAdmin = ['seed', 'catalog', 'carts', 'deliveries'].some((s) => STEPS.has(s));
if (needsAdmin && !ADMIN_PASSWORD) fail('DEV_ADMIN_PASSWORD is required (ADMIN account: categories, shops, shippers).');
if (needsAdmin && !SELLER_PASSWORD) fail('SEED_PASSWORD is required (seed seller accounts).');
if (!BUYER_PASSWORD || !/^(?=.*[A-Z])(?=.*\d)(?=.*[^A-Za-z0-9]).{8,32}$/.test(BUYER_PASSWORD)) {
  fail('PERF_PASSWORD is required: 8-32 chars with an uppercase letter, a digit and a special character.');
}

// ---------- http ----------
const sleep = (ms) => new Promise((done) => setTimeout(done, ms));
const stats = { requests: 0, waits429: 0 };

class ApiError extends Error {
  constructor(status, body, path) {
    super(`${status} ${body?.error?.code || ''} ${body?.error?.message || ''} (${path})`.replace(/\s+/g, ' '));
    this.status = status;
    this.code = body?.error?.code;
  }
}

async function api(method, path, { token, body, headers = {} } = {}) {
  for (let attempt = 1; ; attempt++) {
    stats.requests++;
    let response;
    try {
      response = await fetch(API + path, {
        method,
        headers: { Accept: 'application/json', ...(body ? { 'Content-Type': 'application/json' } : {}), ...(token ? { Authorization: `Bearer ${token}` } : {}), ...headers },
        body: body ? JSON.stringify(body) : undefined,
      });
    } catch (error) {
      if (attempt >= 5) throw error;
      await sleep(2000);
      continue;
    }
    const text = await response.text();
    let data = null;
    try {
      data = text ? JSON.parse(text) : null;
    } catch {
      // HTML error page from a proxy; keep the status
    }
    if (response.status === 429 && attempt <= 5) {
      stats.waits429++;
      if (stats.waits429 === 1) console.log('  … HTTP 429 (rate limit on); waiting for the next window. Faster with APP_RATE_LIMIT_ENABLED=false on ENV-P.');
      await sleep((61 - (Math.floor(Date.now() / 1000) % 60)) * 1000);
      continue;
    }
    if ((response.status === 502 || response.status === 503) && attempt <= 5) {
      await sleep(2000);
      continue;
    }
    if (!response.ok) throw new ApiError(response.status, data, `${method} ${path}`);
    return data;
  }
}

async function retryWhile(label, attempt, when, { tries = 15, delay = 2000 } = {}) {
  for (let i = 1; ; i++) {
    try {
      return await attempt();
    } catch (error) {
      if (i >= tries || !when(error)) throw error;
      if (i === 1) console.log(`  … waiting: ${label}`);
      await sleep(delay);
    }
  }
}

async function pool(items, size, worker) {
  let next = 0;
  await Promise.all(
    Array.from({ length: Math.min(size, items.length) }, async () => {
      while (next < items.length) {
        const index = next++;
        await worker(items[index], index);
      }
    }),
  );
}

function hash(text) {
  let value = 2166136261;
  for (const char of text) value = Math.imul(value ^ char.codePointAt(0), 16777619) >>> 0;
  return value;
}

const userIdOf = (token) => JSON.parse(Buffer.from(token.split('.')[1], 'base64url').toString()).sub;
const login = (email, password) => api('POST', '/api/auth/login', { body: { email, password } });
const buyerUsername = (i) => `${PREFIX}.${String(i).padStart(4, '0')}`;
const buyerEmail = (i) => `${buyerUsername(i)}@${DOMAIN}`;
const shipperEmail = (i) => `perf.shipper.${String(i).padStart(3, '0')}@${DOMAIN}`;

async function allPages(path) {
  const items = [];
  for (let page = 0; ; page++) {
    const sep = path.includes('?') ? '&' : '?';
    const data = await api('GET', `${path}${sep}page=${page}&size=100`);
    items.push(...data.content);
    if (page + 1 >= data.totalPages || data.content.length === 0) return items;
  }
}

// ---------- OTP from the auth-service log ----------
function readOtpLog() {
  return execSync(OTP_LOG_CMD, { cwd: repoRoot, encoding: 'utf8', maxBuffer: 64 * 1024 * 1024, stdio: ['ignore', 'pipe', 'ignore'] });
}
function otpFor(logs, email) {
  for (const entry of logs.split(`[DEV EMAIL] to=${email} | subject=`).slice(1).reverse()) {
    const match = entry.match(/Mã xác thực email của bạn là:\s*(\d{6})/);
    if (match) return match[1];
  }
  return null;
}

// Verify PENDING accounts in batches: request OTPs, read the log once, verify.
async function verifyAccounts(emails) {
  if (!emails.length) return 0;
  if (flag('allow-pending')) {
    console.log(`  --allow-pending: ${emails.length} accounts stay PENDING (note it in the report)`);
    return 0;
  }
  let verified = 0;
  for (let start = 0; start < emails.length; start += 25) {
    const batch = emails.slice(start, start + 25);
    for (const email of batch) await api('POST', '/api/auth/otp/request', { body: { email } });
    let waiting = batch;
    for (let attempt = 0; attempt < 15 && waiting.length; attempt++) {
      await sleep(2000);
      let logs;
      try {
        logs = readOtpLog();
      } catch (error) {
        fail(`Cannot read OTP codes with "${OTP_LOG_CMD}" (${error.message.split('\n')[0]}). Run this on the stack host or set OTP_LOG_CMD.`);
      }
      const still = [];
      for (const email of waiting) {
        const otp = otpFor(logs, email);
        if (!otp) still.push(email);
        else {
          await api('POST', '/api/auth/otp/verify', { body: { email, otp } });
          verified++;
        }
      }
      waiting = still;
    }
    if (waiting.length) fail(`No OTP in the auth-service log for ${waiting.length} accounts (AUTH_EMAIL_PROVIDER=log?).`);
    process.stdout.write(`  verified ${Math.min(start + 25, emails.length)}/${emails.length}\r`);
  }
  console.log('');
  return verified;
}

// ---------- seed, admin, sellers ----------
function runSeed() {
  console.log('\n[seed] demo shops, sellers, 8 categories, 96 products');
  execFileSync(process.execPath, ['scripts/seed-demo-data/seed.mjs', '--skip-image-check', ...(local ? [] : ['--allow-remote'])], {
    cwd: repoRoot,
    stdio: 'inherit',
    env: { ...process.env, SEED_API_URL: API },
  });
}

let adminToken = null;
async function admin() {
  if (adminToken) return adminToken;
  const session = await login(ADMIN_EMAIL, ADMIN_PASSWORD);
  if (!session.roles?.includes('ADMIN')) fail(`${ADMIN_EMAIL} is not an ADMIN.`);
  return (adminToken = session.accessToken);
}

async function ensureCategories() {
  const token = await admin();
  const existing = await api('POST', '/api/products/categories/admin/query', { token });
  const byName = new Map(existing.map((c) => [c.name, c.id]));
  const ids = {};
  for (const category of [...dataset.categories, ...EXTRA_CATEGORIES]) {
    if (!byName.has(category.name)) {
      const created = await api('POST', '/api/products/categories', { token, body: { name: category.name, parentId: null, sortOrder: category.sortOrder, active: true } });
      byName.set(category.name, created.id);
      console.log(`  + category ${category.name}`);
    }
    ids[category.key] = byName.get(category.name);
  }
  return ids;
}

let shopsCache = null;
async function sellerShops() {
  if (shopsCache) return shopsCache;
  const shops = [];
  for (const shop of dataset.shops) {
    const email = `seller.${shop.key}@seed.vmarket.test`;
    const session = await login(email, SELLER_PASSWORD);
    if (!session.roles?.includes('SELLER')) fail(`${email} has no SELLER role yet; run the seed step first.`);
    const mine = await api('GET', '/api/shops/me', { token: session.accessToken });
    if (mine.status !== 'ACTIVE') fail(`Shop ${shop.name} is ${mine.status}.`);
    shops.push({ ...shop, email, shopId: mine.id, sellerId: userIdOf(session.accessToken), token: session.accessToken });
  }
  await syncShopAccess(shops);
  return (shopsCache = shops);
}

// product-service learns about approved shops from ShopApproved events. Shops approved
// before a fresh product-service started are unknown to it (product creation -> 403).
// With INTERNAL_API_KEY set, push their access snapshot to product-service directly.
async function syncShopAccess(shops) {
  const key = process.env.INTERNAL_API_KEY;
  if (!key) return;
  const url = (process.env.PRODUCT_SERVICE_URL || 'http://localhost:8084').replace(/\/$/, '');
  const res = await fetch(`${url}/api/products/internal/shop-access/reconcile`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-Internal-Api-Key': key },
    body: JSON.stringify({ shops: shops.map((s) => ({ shopId: s.shopId, sellerId: s.sellerId, active: true, updatedAt: new Date().toISOString() })) }),
  });
  if (!res.ok) fail(`shop access reconcile at ${url} failed: HTTP ${res.status}`);
  console.log(`  shop access synced to product-service for ${shops.length} shops`);
}

function variants(shop, index, price, setKey) {
  const set = setKey && dataset.variantSets[setKey];
  const stock = 500 + (hash(`${shop.key}-perf-${index}`) % 500); // large: checkout journeys must not run dry
  if (!set) return [{ sku: `PERF-${shop.key}-${index}`.toUpperCase(), attributes: { 'Phân loại': 'Tiêu chuẩn' }, price, stock }];
  return set.values.map((value) => ({ sku: `PERF-${shop.key}-${index}-${value}`.toUpperCase(), attributes: { [set.label]: value }, price, stock: Math.round(stock / set.values.length) + 50 }));
}

async function createProduct(shop, key, body) {
  return retryWhile(
    'Product Service learns about the approved shop',
    () => api('POST', '/api/products', { token: shop.token, body: { shopId: shop.shopId, currency: 'VND', brandId: null, ...body }, headers: { 'Idempotency-Key': key } }),
    (error) => error.status === 403,
    { tries: 10 },
  );
}

const oneVariant = (sku, price, stock) => [{ sku, attributes: { 'Phân loại': 'Tiêu chuẩn' }, price, stock }];

async function ensureCatalog() {
  console.log(`\n[catalog] >= ${PRODUCTS} ACTIVE products, 10 categories, PH/PD, contention products`);
  const categoryIds = await ensureCategories();
  const shops = await sellerShops();
  const perShop = Math.ceil(PRODUCTS / shops.length);
  const extraIds = EXTRA_CATEGORIES.map((c) => categoryIds[c.key]);
  let created = 0;
  await pool(shops, 4, async (shop, s) => {
    const listed = await allPages(`/api/products?shopId=${shop.shopId}`);
    const names = new Set(listed.map((p) => p.name));
    const image = (seed) => [`https://picsum.photos/seed/${hash(seed)}/600/600`];
    const fixedName = `PERF giá cố định 100000 - ${shop.name}`;
    if (!names.has(fixedName)) {
      await createProduct(shop, `perf-${shop.key}-fixed`, { name: fixedName, description: 'Sản phẩm kiểm thử hiệu năng, giá cố định 100000 VND.', imageUrls: image(`fixed-${shop.key}`), categoryId: categoryIds[shop.category], status: 'ACTIVE', variants: oneVariant(`PERF-${shop.key}-FIXED`.toUpperCase(), TARGET_PRICE, 100000) });
      created++;
    }
    if (s < CONTENTION.products) {
      const name = `PERF tranh chấp ${s + 1} - ${shop.name}`;
      if (!names.has(name)) {
        await createProduct(shop, `perf-ct-${shop.key}`, { name, description: 'Sản phẩm kiểm thử tranh chấp tồn kho (PERF-LOAD-06).', imageUrls: image(`ct-${shop.key}`), categoryId: categoryIds[shop.category], status: 'ACTIVE', variants: oneVariant(`PERF-${shop.key}-CT`.toUpperCase(), CONTENTION.price, CONTENTION.stock) });
        created++;
      }
    }
    let have = listed.filter((p) => !PERF_NAME.test(p.name)).length;
    for (let g = 0; have < perShop; g++) {
      const [baseName, price, keyword, setKey] = shop.products[g % shop.products.length];
      const name = `${baseName} - PERF ${String(g + 1).padStart(3, '0')}`;
      if (names.has(name)) continue;
      await createProduct(shop, `perf-${shop.key}-${g}`, { name, description: `${baseName} (dữ liệu kiểm thử hiệu năng) từ ${shop.name}.`, imageUrls: image(`${keyword}-${shop.key}-${g}`), categoryId: g % 5 === 4 ? extraIds[g % 2] : categoryIds[shop.category], status: 'ACTIVE', variants: variants(shop, g, price, setKey) });
      created++;
      have++;
    }
  });
  console.log(`  + ${created} products`);

  const hidden = {};
  for (const [alias, status] of [['PH', 'HIDDEN'], ['PD', 'DRAFT']]) {
    const p = await createProduct(shops[0], `perf-${alias.toLowerCase()}`, { name: `PERF ${alias} ${status}`, description: `Sản phẩm kiểm thử ${status}: không được xuất hiện phía người mua.`, imageUrls: [`https://picsum.photos/seed/${alias}/600/600`], categoryId: categoryIds[shops[0].category], status, variants: oneVariant(`PERF-${alias}`, 123000, 10) });
    hidden[alias] = p.id;
  }

  const active = await allPages('/api/products');
  const regular = active.filter((p) => !PERF_NAME.test(p.name));
  const contention = active
    .filter((p) => p.name.startsWith('PERF tranh chấp'))
    .map((p) => ({ productId: p.id, variantId: p.variants[0].id, shopId: p.shopId, price: p.variants[0].price, stock: CONTENTION.stock }));
  const categories = [];
  for (const [key, id] of Object.entries(categoryIds)) {
    const page = await api('GET', `/api/products?categoryId=${id}&page=0&size=1`);
    categories.push({ key, id, products: page.totalElements });
  }
  const prices = regular.flatMap((p) => p.variants.map((v) => v.price));
  const keywords = [...new Set(dataset.shops.flatMap((s) => s.products.map(([n]) => n.split(' ').slice(0, 2).join(' ').toLowerCase())))];
  return {
    catalog: {
      activeProducts: active.length,
      pageSize: 20,
      categories,
      variants: active.reduce((n, p) => n + p.variants.length, 0),
      images: active.reduce((n, p) => n + (p.imageUrls || []).length, 0),
      priceRange: { min: Math.min(...prices), max: Math.max(...prices) },
      keywords,
      shops: shops.map((s) => ({ id: s.shopId, key: s.key, name: s.name })),
      hidden,
      products: regular.map((p) => ({ id: p.id, shopId: p.shopId, categoryId: p.categoryId, name: p.name, imageUrl: (p.imageUrls || [])[0], variants: [{ id: p.variants[0].id, price: p.variants[0].price }] })),
    },
    contention,
    seller: { email: shops[0].email, shopId: shops[0].shopId, categoryId: categoryIds[shops[0].category] },
    byShop: Object.fromEntries(shops.map((s) => [s.shopId, active.filter((p) => p.shopId === s.shopId)])),
  };
}

// ---------- buyers ----------
const ADDRESS = (i) => ({ recipientName: `Perf Buyer ${i}`, phone: '0900000000', province: 'Đà Nẵng', ward: 'Thạch Thang', streetAddress: `${i + 1} Đường Kiểm Thử`, note: 'perf-test', isDefault: true });

async function ensureBuyers() {
  console.log(`\n[buyers] ${BUYERS} verified buyers with a default address (${buyerEmail(0)} ...)`);
  const buyers = new Array(BUYERS);
  const pending = [];
  let registered = 0;
  await pool([...Array(BUYERS).keys()], 8, async (i) => {
    const email = buyerEmail(i);
    let session;
    try {
      session = await login(email, BUYER_PASSWORD);
    } catch (error) {
      if (error.status === 423) fail(`${email} is locked. Wait 15 min or use another BUYER_PREFIX.`);
      if (error.status !== 401) throw error;
      try {
        await api('POST', '/api/auth/register', { body: { email, username: buyerUsername(i), password: BUYER_PASSWORD } });
      } catch (registerError) {
        if (registerError.status === 409) fail(`${email} exists with another password. Use the original PERF_PASSWORD or another BUYER_PREFIX.`);
        throw registerError;
      }
      registered++;
      session = await login(email, BUYER_PASSWORD);
    }
    buyers[i] = { index: i, email, userId: userIdOf(session.accessToken), status: session.status };
    if (session.status === 'PENDING') pending.push(email);
    const addresses = await api('GET', '/api/users/me/addresses', { token: session.accessToken }).catch(() => []);
    if (!Array.isArray(addresses) || addresses.length === 0) await api('POST', '/api/users/me/addresses', { token: session.accessToken, body: ADDRESS(i) });
  });
  console.log(`  + ${registered} registered, ${pending.length} need OTP verification`);
  await verifyAccounts(pending);
  for (const b of buyers) if (pending.includes(b.email) && !flag('allow-pending')) b.status = 'ACTIVE';
  const verified = buyers.filter((b) => b.status === 'ACTIVE').length;
  console.log(`  ${verified}/${BUYERS} buyers ACTIVE`);
  return { buyers, verified };
}

// ---------- orders ----------
async function placeCod(token, line, note, key) {
  await api('DELETE', '/api/cart', { token });
  await api('POST', '/api/cart/items', { token, body: line });
  const addresses = await api('GET', '/api/users/me/addresses', { token });
  const addressId = (addresses.find((a) => a.isDefault) || addresses[0]).id;
  return api('POST', '/api/orders', { token, headers: { 'Idempotency-Key': key }, body: { addressId, note, paymentMethod: 'COD' } });
}
const lineOf = (p) => ({ productId: p.id, variantId: p.variants[0].id, shopId: p.shopId, quantity: 1, unitPrice: p.variants[0].price });

async function buildHistory(buyers, catalog) {
  console.log(`\n[history] 2 cancelled COD orders for shoppers 0-${HISTORY_BUYERS - 1}`);
  const history = [];
  await pool([...Array(HISTORY_BUYERS).keys()], 8, async (b) => {
    const { accessToken } = await login(buyers[b].email, BUYER_PASSWORD);
    const existing = (await api('GET', '/api/orders', { token: accessToken })).filter((o) => o.note === 'perf-history');
    const cancel = async (id) => {
      try {
        await api('PUT', `/api/orders/${id}/cancel`, { token: accessToken });
      } catch (error) {
        console.warn(`  ! history order ${id} not cancelled (${error.message}); it keeps stock reserved, retried on the next run`);
      }
    };
    for (const o of existing) if (o.status !== 'CANCELLED') await cancel(o.id); // leftovers of an earlier run
    const orderIds = existing.map((o) => o.id);
    for (let k = orderIds.length; k < 2; k++) {
      const p = catalog.products[(b * 2 + k) % catalog.products.length];
      const order = await placeCod(accessToken, lineOf(p), 'perf-history', `perf-history-${b}-${k}`);
      orderIds.push(order.id);
      await cancel(order.id);
    }
    history[b] = { buyer: b, orderIds: orderIds.slice(0, 2) };
  });
  return history;
}

// ---------- carts ----------
async function buildCarts(buyers, catalogResult) {
  const count = POOLS.shopper.size;
  console.log(`\n[carts] ${count} carts x ${LINES_PER_SHOP * 2} lines in two shops`);
  const shops = await sellerShops();
  const firstVariant = (p) => [...p.variants].sort((a, b) => a.id.localeCompare(b.id))[0];
  const pools = Object.fromEntries(
    shops.map((s) => {
      const products = catalogResult.byShop[s.shopId] || [];
      const fixed = products.find((p) => p.name.startsWith('PERF giá cố định'));
      if (!fixed) fail(`Shop ${s.name} has no fixed-price product; run the catalog step.`);
      const others = products.filter((p) => !PERF_NAME.test(p.name)).sort((a, b) => a.id.localeCompare(b.id));
      if (others.length < LINES_PER_SHOP) fail(`Shop ${s.name} has only ${others.length} products for cart lines.`);
      return [s.shopId, { fixed, others }];
    }),
  );
  const carts = new Array(count);
  let done = 0;
  await pool([...Array(count).keys()], 8, async (b) => {
    const shopA = shops[(2 * b) % shops.length];
    const shopB = shops[(2 * b + 1) % shops.length];
    const items = [];
    const line = (product, quantity) => {
      const v = firstVariant(product);
      items.push({ productId: product.id, variantId: v.id, shopId: product.shopId, quantity, unitPrice: v.price });
    };
    const { fixed } = pools[shopA.shopId];
    line(fixed, 1);
    for (const [shop, n] of [[shopA, LINES_PER_SHOP - 1], [shopB, LINES_PER_SHOP]]) {
      const { others } = pools[shop.shopId];
      const offset = (b * 7) % others.length;
      for (let k = 0; k < n; k++) {
        const product = others[(offset + k) % others.length];
        line(product, 1 + (hash(`${b}-${product.id}`) % 3));
      }
    }
    const buyer = buyers[b];
    const { accessToken } = await login(buyer.email, BUYER_PASSWORD);
    await api('DELETE', '/api/cart', { token: accessToken });
    for (const item of items) await api('POST', '/api/cart/items', { token: accessToken, body: item });
    const cart = await api('GET', '/api/cart', { token: accessToken });
    const expectedTotal = items.reduce((n, i) => n + i.unitPrice * i.quantity, 0);
    const lines = cart.groups.reduce((n, g) => n + g.items.length, 0);
    if (lines !== items.length || Number(cart.totalAmount) !== expectedTotal || cart.userId !== buyer.userId) {
      fail(`Cart of ${buyer.email} does not match the fixture (lines ${lines}, total ${cart.totalAmount} != ${expectedTotal}).`);
    }
    carts[b] = { buyer: b, email: buyer.email, userId: buyer.userId, target: { productId: fixed.id, variantId: firstVariant(fixed).id, shopId: fixed.shopId, unitPrice: TARGET_PRICE }, items };
    if (++done % 25 === 0) process.stdout.write(`  ${done}/${count}\r`);
  });
  console.log(`  ${count} carts verified`);
  return carts;
}

// ---------- deliveries (UC-23/24) ----------
const pickId = (o, ...keys) => keys.map((k) => o && o[k]).find(Boolean);

async function buildDeliveries(buyers, catalog, previous = []) {
  const count = POOLS.delivery.size;
  console.log(`\n[deliveries] ${count} orders handed off, assigned and in transit`);
  const token = await admin();
  const shops = await sellerShops();
  const shipperIds = [];
  for (let i = 0; i < count; i++) {
    const email = shipperEmail(i);
    const created = await api('POST', '/api/delivery/admin/shippers', {
      token,
      headers: { 'Idempotency-Key': `perf-shipper-${String(i).padStart(3, '0')}` },
      body: { account: { email, username: `perf.shipper.${String(i).padStart(3, '0')}`, password: BUYER_PASSWORD }, name: `Perf Shipper ${i}`, phone: '0900000001', province: 'Đà Nẵng', district: 'Hải Châu' },
    });
    shipperIds[i] = pickId(created, 'id', 'shipperId') || pickId(created.shipper, 'id');
  }
  const deliveries = [];
  await pool([...Array(count).keys()], 4, async (i) => {
    const kept = previous.find((d) => d.index === i);
    const shipperSession = await login(shipperEmail(i), BUYER_PASSWORD);
    if (kept) {
      const mine = await api('GET', '/api/delivery/shipper/assignments?size=100', { token: shipperSession.accessToken });
      const a = mine.find((x) => x.id === kept.assignmentId);
      if (a && (a.status === 'IN_TRANSIT' || a.status === 'PICKED_UP')) return deliveries.push(kept);
    }
    const b = POOLS.delivery.start + i;
    const shop = shops[i % shops.length];
    const product = catalog.products.find((p) => p.shopId === shop.shopId);
    const { accessToken } = await login(buyers[b].email, BUYER_PASSWORD);
    // Fixed key: a rerun after a failed handoff/assign gets the same order back (no second reservation).
    const order = await placeCod(accessToken, lineOf(product), 'perf-delivery', `perf-delivery-${i}`);
    await retryWhile('stock reservation of the delivery order', async () => {
      const o = await api('GET', `/api/orders/${order.id}`, { token: accessToken });
      if (!o.stockReserved) throw Object.assign(new Error('not reserved yet'), { status: 0 });
    }, (e) => e.status === 0, { tries: 15 });
    const handed = await api('POST', `/api/delivery/seller/orders/${order.id}/handoff`, { token: shop.token });
    const assignmentId = pickId(handed, 'id', 'assignmentId');
    let assignment = await api('POST', `/api/delivery/admin/assignments/${assignmentId}/assign`, { token, body: { shipperId: shipperIds[i], expectedVersion: handed.version ?? 0, overrideArea: true, reason: 'perf-test' } });
    for (const status of ['PICKED_UP', 'IN_TRANSIT']) {
      assignment = await api('PUT', `/api/delivery/shipper/assignments/${assignmentId}/status`, { token: shipperSession.accessToken, body: { status, expectedVersion: assignment.version } });
    }
    deliveries.push({ index: i, buyer: b, orderId: order.id, assignmentId, shipperEmail: shipperEmail(i) });
  });
  deliveries.sort((a, b) => a.index - b.index);
  console.log(`  ${deliveries.length} deliveries in transit`);
  return deliveries;
}

// ---------- images (PERF-SRCH-03) ----------
function crc32(buf) {
  let c = ~0;
  for (const byte of buf) {
    c ^= byte;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
  }
  return ~c >>> 0;
}
function png(width, height, seed) {
  const raw = Buffer.alloc((width * 3 + 1) * height);
  let x = seed;
  for (let i = 0; i < raw.length; i++) raw[i] = i % (width * 3 + 1) === 0 ? 0 : ((x = (Math.imul(x, 1103515245) + 12345) >>> 0) >>> 24);
  const chunk = (type, data) => {
    const len = Buffer.alloc(4);
    len.writeUInt32BE(data.length);
    const td = Buffer.concat([Buffer.from(type), data]);
    const crc = Buffer.alloc(4);
    crc.writeUInt32BE(crc32(td));
    return Buffer.concat([len, td, crc]);
  };
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr.set([8, 2, 0, 0, 0], 8);
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', ihdr), chunk('IDAT', deflateSync(raw, { level: 1 })), chunk('IEND', Buffer.alloc(0))]);
}

async function buildImages(catalog) {
  console.log('\n[images] query photos for image search');
  mkdirSync(IMAGE_DIR, { recursive: true });
  const images = [];
  const sample = catalog.products.filter((p) => p.imageUrl).filter((_, i) => i % Math.max(1, Math.floor(catalog.products.length / 15)) === 0).slice(0, 15);
  for (const [i, p] of sample.entries()) {
    const file = resolve(IMAGE_DIR, `query-${String(i).padStart(2, '0')}.jpg`);
    if (!existsSync(file)) {
      try {
        const res = await fetch(p.imageUrl, { redirect: 'follow' });
        if (!res.ok || !(res.headers.get('content-type') || '').startsWith('image/')) continue;
        writeFileSync(file, Buffer.from(await res.arrayBuffer()));
      } catch {
        continue;
      }
    }
    images.push({ path: file.replace(/\\/g, '/'), bytes: statSync(file).size, type: 'image/jpeg' });
  }
  const big = resolve(IMAGE_DIR, 'query-large.png');
  if (!existsSync(big)) writeFileSync(big, png(1600, 1600, 7));
  images.push({ path: big.replace(/\\/g, '/'), bytes: statSync(big).size, type: 'image/png' });
  console.log(`  ${images.length} photos (largest ${(Math.max(...images.map((x) => x.bytes)) / 1e6).toFixed(1)} MB)`);
  return images;
}

function gitSha() {
  try {
    return execSync('git rev-parse HEAD', { cwd: repoRoot, encoding: 'utf8' }).trim();
  } catch {
    return null;
  }
}

async function main() {
  const startedAt = new Date();
  let previous = {};
  try {
    previous = JSON.parse(readFileSync(OUT, 'utf8'));
  } catch {
    // first run
  }
  const m = { ...previous };
  if (STEPS.has('seed') && !flag('skip-seed')) runSeed();

  let catalogResult = null;
  if (['catalog', 'carts', 'history', 'deliveries', 'images'].some((s) => STEPS.has(s))) {
    if (STEPS.has('catalog') || STEPS.has('carts') || !previous.catalog) {
      catalogResult = await ensureCatalog();
      if (catalogResult.catalog.activeProducts < PRODUCTS) fail(`Only ${catalogResult.catalog.activeProducts} ACTIVE products (need ${PRODUCTS}). Is product-service up?`);
      Object.assign(m, { catalog: catalogResult.catalog, contention: catalogResult.contention, seller: catalogResult.seller });
    }
  }
  if (STEPS.has('buyers') || !previous.buyersDetail) {
    const { buyers, verified } = await ensureBuyers();
    m.buyersDetail = buyers;
    m.buyers = { prefix: PREFIX, domain: DOMAIN, count: buyers.length, verified, pools: POOLS };
  }
  if (STEPS.has('history')) m.orderHistory = await buildHistory(m.buyersDetail, m.catalog);
  if (STEPS.has('carts')) m.carts = await buildCarts(m.buyersDetail, catalogResult);
  if (STEPS.has('deliveries')) m.deliveries = await buildDeliveries(m.buyersDetail, m.catalog, previous.deliveries || []);
  if (STEPS.has('images')) m.images = await buildImages(m.catalog);

  Object.assign(m, { runId: `perf-${startedAt.toISOString().replace(/[:.]/g, '-')}`, createdAt: startedAt.toISOString(), baseUrl: API, gitSha: gitSha() });
  mkdirSync(dirname(OUT), { recursive: true });
  writeFileSync(OUT, JSON.stringify(m, null, 2));
  const c = m.catalog || {};
  console.log(`\nManifest ${OUT}`);
  console.log(`  run ${m.runId}  sha ${(m.gitSha || '').slice(0, 7)}  buyers ${m.buyers?.verified}/${m.buyers?.count} verified`);
  console.log(`  catalog ${c.activeProducts || 0} ACTIVE, ${(c.categories || []).length} categories; contention ${(m.contention || []).length}; history ${(m.orderHistory || []).length}; carts ${(m.carts || []).length}; deliveries ${(m.deliveries || []).length}; images ${(m.images || []).length}`);
  console.log(`  ${stats.requests} requests, ${stats.waits429} rate-limit waits, ${Math.round((Date.now() - startedAt) / 1000)} s`);
}

main().catch((error) => fail(error.stack || error.message));
