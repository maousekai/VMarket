#!/usr/bin/env node
// Fixture for the performance test plan (sheet "Test Cases": PERF-USERS, PERF-CATALOG,
// Cart). Goes through the real APIs behind the gateway, like scripts/seed-demo-data.
//
//   PERF-USERS    PERF_BUYERS buyers (default 300: spike needs 300 distinct VUs),
//                 verified with OTP -> ACTIVE
//   PERF-CATALOG  >= PERF_PRODUCTS ACTIVE products (default 1000) in 10 categories,
//                 plus PH (HIDDEN) and PD (DRAFT) to prove they never leak
//   Cart          PERF_CARTS carts (default 200), 20 lines in two shops each; the line
//                 whose quantity the test changes costs 100000
//
// Writes perf/fixtures/out/manifest.json (ids, record counts, expected carts, run id,
// git SHA). It never stores passwords, tokens or cookies.
//
// Re-running is safe: existing accounts/products are reused, carts are rebuilt to the
// exact fixture state (use --only=carts to reset carts between test cases).
//
// Run it on the machine that runs the stack (it reads OTP codes from the auth-service
// log, see OTP_LOG_CMD). See perf/README.md.
import { execFileSync, execSync } from 'node:child_process';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

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
const BUYERS = Number(process.env.PERF_BUYERS || 300);
const CARTS = Number(process.env.PERF_CARTS || 200);
const PRODUCTS = Number(process.env.PERF_PRODUCTS || 1000);
const PREFIX = process.env.BUYER_PREFIX || 'perf.buyer';
const DOMAIN = 'perf.vmarket.test';
const OTP_LOG_CMD = process.env.OTP_LOG_CMD || 'docker compose logs --since 30m auth-service';
const OUT = resolve(option('out', resolve(here, 'out/manifest.json')));
const STEPS = new Set((option('only', 'seed,catalog,buyers,carts')).split(','));
const TARGET_PRICE = 100000;
const LINES_PER_SHOP = 10;
const EXTRA_CATEGORIES = [
  { key: 'home', name: 'Nhà cửa & Đời sống', sortOrder: 9 },
  { key: 'office', name: 'Văn phòng phẩm', sortOrder: 10 },
];

// ---------- guards ----------
function fail(message) {
  console.error(`\n✗ ${message}`);
  process.exit(1);
}
const local = /^https?:\/\/(localhost|127\.0\.0\.1|\[::1\])(:\d+)?$/.test(API);
if (!local && !flag('allow-remote')) fail(`BASE_URL=${API} is not local. Pass --allow-remote only for the performance environment (ENV-P).`);
const NEEDS_CATALOG = ['seed', 'catalog', 'carts'].some((s) => STEPS.has(s));
if (NEEDS_CATALOG && !ADMIN_PASSWORD) fail('DEV_ADMIN_PASSWORD is required (ADMIN account that creates categories and approves shops).');
if (NEEDS_CATALOG && !SELLER_PASSWORD) fail('SEED_PASSWORD is required (seed seller accounts).');
if (!BUYER_PASSWORD || !/^(?=.*[A-Z])(?=.*\d)(?=.*[^A-Za-z0-9]).{8,32}$/.test(BUYER_PASSWORD)) {
  fail('PERF_PASSWORD is required: 8-32 chars with an uppercase letter, a digit and a special character.');
}
if (CARTS > BUYERS) fail(`PERF_CARTS (${CARTS}) cannot exceed PERF_BUYERS (${BUYERS}).`);

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
        headers: {
          Accept: 'application/json',
          ...(body ? { 'Content-Type': 'application/json' } : {}),
          ...(token ? { Authorization: `Bearer ${token}` } : {}),
          ...headers,
        },
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
    // Gateway limiter (200 req / 60 s / client): wait for the next window.
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

async function allPages(path) {
  const items = [];
  for (let page = 0; ; page++) {
    const sep = path.includes('?') ? '&' : '?';
    const data = await api('GET', `${path}${sep}page=${page}&size=100`);
    items.push(...data.content);
    if (page + 1 >= data.totalPages || data.content.length === 0) return items;
  }
}

// ---------- OTP from the auth-service log (AUTH_EMAIL_PROVIDER=log) ----------
function readOtpLog() {
  return execSync(OTP_LOG_CMD, { cwd: repoRoot, encoding: 'utf8', maxBuffer: 64 * 1024 * 1024, stdio: ['ignore', 'pipe', 'ignore'] });
}

function otpFor(logs, email) {
  const entries = logs.split(`[DEV EMAIL] to=${email} | subject=`).slice(1).reverse();
  for (const entry of entries) {
    const match = entry.match(/Mã xác thực email của bạn là:\s*(\d{6})/);
    if (match) return match[1];
  }
  return null;
}

// ---------- steps ----------
function runSeed() {
  console.log('\n[1/5] Demo seed (shops, sellers, 8 categories, 96 products)');
  execFileSync(process.execPath, ['scripts/seed-demo-data/seed.mjs', '--skip-image-check', ...(local ? [] : ['--allow-remote'])], {
    cwd: repoRoot,
    stdio: 'inherit',
    env: { ...process.env, SEED_API_URL: API },
  });
}

async function ensureCategories(adminToken) {
  const existing = await api('POST', '/api/products/categories/admin/query', { token: adminToken });
  const byName = new Map(existing.map((c) => [c.name, c.id]));
  const ids = {};
  for (const category of [...dataset.categories, ...EXTRA_CATEGORIES]) {
    if (!byName.has(category.name)) {
      const created = await api('POST', '/api/products/categories', {
        token: adminToken,
        body: { name: category.name, parentId: null, sortOrder: category.sortOrder, active: true },
      });
      byName.set(category.name, created.id);
      console.log(`  + category ${category.name}`);
    }
    ids[category.key] = byName.get(category.name);
  }
  return ids;
}

async function sellerShops() {
  const shops = [];
  for (const shop of dataset.shops) {
    const email = `seller.${shop.key}@seed.vmarket.test`;
    const session = await login(email, SELLER_PASSWORD);
    if (!session.roles?.includes('SELLER')) fail(`${email} has no SELLER role yet; run the demo seed first.`);
    const mine = await api('GET', '/api/shops/me', { token: session.accessToken });
    if (mine.status !== 'ACTIVE') fail(`Shop ${shop.name} is ${mine.status}; the demo seed approves it.`);
    shops.push({ ...shop, shopId: mine.id, token: session.accessToken });
  }
  return shops;
}

function variants(shop, index, price, setKey) {
  const set = setKey && dataset.variantSets[setKey];
  const stock = 50 + (hash(`${shop.key}-perf-${index}`) % 150);
  if (!set) return [{ sku: `PERF-${shop.key}-${index}`.toUpperCase(), attributes: { 'Phân loại': 'Tiêu chuẩn' }, price, stock }];
  return set.values.map((value) => ({
    sku: `PERF-${shop.key}-${index}-${value}`.toUpperCase(),
    attributes: { [set.label]: value },
    price,
    stock: Math.max(5, Math.round(stock / set.values.length)),
  }));
}

async function createProduct(shop, key, body) {
  return retryWhile(
    'Product Service learns about the approved shop',
    () => api('POST', '/api/products', { token: shop.token, body: { shopId: shop.shopId, currency: 'VND', brandId: null, ...body }, headers: { 'Idempotency-Key': key } }),
    (error) => error.status === 403,
    { tries: 10 },
  );
}

async function ensureCatalog(categoryIds, shops) {
  console.log(`\n[2/5] Catalog: >= ${PRODUCTS} ACTIVE products, 10 categories, PH/PD`);
  const perShop = Math.ceil(PRODUCTS / shops.length);
  const extraIds = EXTRA_CATEGORIES.map((c) => categoryIds[c.key]);
  let created = 0;
  await pool(shops, 4, async (shop) => {
    const listed = await allPages(`/api/products?shopId=${shop.shopId}`);
    const names = new Set(listed.map((p) => p.name));
    const image = (seed) => [`https://picsum.photos/seed/${hash(seed)}/600/600`];

    // The cart target line: one product per shop at exactly 100000.
    const fixedName = `PERF giá cố định 100000 - ${shop.name}`;
    if (!names.has(fixedName)) {
      await createProduct(shop, `perf-${shop.key}-fixed`, {
        name: fixedName,
        description: 'Sản phẩm kiểm thử hiệu năng, giá cố định 100000 VND.',
        imageUrls: image(`fixed-${shop.key}`),
        categoryId: categoryIds[shop.category],
        status: 'ACTIVE',
        variants: [{ sku: `PERF-${shop.key}-FIXED`.toUpperCase(), attributes: { 'Phân loại': 'Tiêu chuẩn' }, price: TARGET_PRICE, stock: 100000 }],
      });
      created++;
    }
    let have = listed.length + (names.has(fixedName) ? 0 : 1);
    for (let g = 0; have < perShop; g++) {
      const [baseName, price, keyword, setKey] = shop.products[g % shop.products.length];
      const name = `${baseName} - PERF ${String(g + 1).padStart(3, '0')}`;
      if (names.has(name)) continue;
      await createProduct(shop, `perf-${shop.key}-${g}`, {
        name,
        description: `${baseName} (dữ liệu kiểm thử hiệu năng) từ ${shop.name}.`,
        imageUrls: image(`${keyword}-${shop.key}-${g}`),
        categoryId: g % 5 === 4 ? extraIds[g % 2] : categoryIds[shop.category],
        status: 'ACTIVE',
        variants: variants(shop, g, price, setKey),
      });
      created++;
      have++;
    }
  });
  console.log(`  + ${created} products`);

  // PH / PD: never visible in the public catalog.
  const owner = shops[0];
  const hidden = {};
  for (const [alias, status] of [['PH', 'HIDDEN'], ['PD', 'DRAFT']]) {
    const product = await createProduct(owner, `perf-${alias.toLowerCase()}`, {
      name: `PERF ${alias} ${status}`,
      description: `Sản phẩm kiểm thử ${status}: không được xuất hiện trong catalog.`,
      imageUrls: [`https://picsum.photos/seed/${alias}/600/600`],
      categoryId: categoryIds[owner.category],
      status,
      variants: [{ sku: `PERF-${alias}`, attributes: { 'Phân loại': 'Tiêu chuẩn' }, price: 123000, stock: 10 }],
    });
    hidden[alias] = product.id;
  }

  const active = await allPages('/api/products');
  const categories = [];
  for (const [key, id] of Object.entries(categoryIds)) {
    const page = await api('GET', `/api/products?categoryId=${id}&page=0&size=1`);
    categories.push({ key, id, products: page.totalElements });
  }
  return {
    activeProducts: active.length,
    pageSize: 20,
    categories,
    variants: active.reduce((n, p) => n + p.variants.length, 0),
    images: active.reduce((n, p) => n + (p.imageUrls || []).length, 0),
    productIds: active.map((p) => p.id),
    hidden,
    byShop: Object.fromEntries(shops.map((s) => [s.shopId, active.filter((p) => p.shopId === s.shopId)])),
  };
}

const buyerUsername = (i) => `${PREFIX}.${String(i).padStart(4, '0')}`;
const buyerEmail = (i) => `${buyerUsername(i)}@${DOMAIN}`;

async function ensureBuyers() {
  console.log(`\n[3/5] Buyers: ${BUYERS} verified accounts ${buyerEmail(0)} ...`);
  const buyers = new Array(BUYERS);
  const pending = [];
  let registered = 0;
  await pool([...Array(BUYERS).keys()], 8, async (i) => {
    const email = buyerEmail(i);
    let session;
    try {
      session = await login(email, BUYER_PASSWORD);
    } catch (error) {
      if (error.code === 'ACCOUNT_LOCKED' || error.status === 423) fail(`${email} is locked (wrong passwords). Wait 15 min or use another BUYER_PREFIX.`);
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
    if (session.status === 'PENDING') pending.push(i);
  });
  console.log(`  + ${registered} registered, ${pending.length} need OTP verification`);

  // --allow-pending: do not request OTPs at all (with a real email provider such as
  // Brevo every request is a sent email to an undeliverable .test address).
  if (flag('allow-pending') && pending.length) console.log('  --allow-pending: OTP verification skipped; note PENDING buyers in the report.');
  // Verify in batches: request OTPs, read the log once, verify.
  for (let start = 0; start < (flag('allow-pending') ? 0 : pending.length); start += 25) {
    const batch = pending.slice(start, start + 25);
    for (const i of batch) await api('POST', '/api/auth/otp/request', { body: { email: buyers[i].email } });
    let waiting = batch;
    for (let attempt = 0; attempt < 15 && waiting.length; attempt++) {
      await sleep(2000);
      let logs;
      try {
        logs = readOtpLog();
      } catch (error) {
        if (flag('allow-pending')) break;
        fail(`Cannot read OTP codes with "${OTP_LOG_CMD}" (${error.message.split('\n')[0]}). Run this script on the stack host, set OTP_LOG_CMD, or pass --allow-pending.`);
      }
      const still = [];
      for (const i of waiting) {
        const otp = otpFor(logs, buyers[i].email);
        if (!otp) {
          still.push(i);
          continue;
        }
        const verified = await api('POST', '/api/auth/otp/verify', { body: { email: buyers[i].email, otp } });
        buyers[i].status = verified.status;
      }
      waiting = still;
    }
    if (waiting.length && !flag('allow-pending')) {
      fail(`No OTP found in the auth-service log for ${waiting.length} buyers (is AUTH_EMAIL_PROVIDER=log on this stack?). Pass --allow-pending to keep them PENDING and note it in the report.`);
    }
    process.stdout.write(`  verified ${Math.min(start + 25, pending.length)}/${pending.length}\r`);
  }
  const verified = buyers.filter((b) => b.status === 'ACTIVE').length;
  console.log(`\n  ${verified}/${BUYERS} buyers ACTIVE`);
  return { buyers, verified };
}

async function buildCarts(buyers, catalog, shops) {
  console.log(`\n[4/5] Carts: ${CARTS} carts x ${LINES_PER_SHOP * 2} lines in two shops`);
  const firstVariant = (p) => [...p.variants].sort((a, b) => a.id.localeCompare(b.id))[0];
  const pools = Object.fromEntries(
    shops.map((s) => {
      const products = catalog.byShop[s.shopId] || [];
      const fixed = products.find((p) => p.name.startsWith('PERF giá cố định'));
      if (!fixed) fail(`Shop ${s.name} has no fixed-price product; run the catalog step.`);
      const others = products.filter((p) => p !== fixed).sort((a, b) => a.id.localeCompare(b.id));
      if (others.length < LINES_PER_SHOP) fail(`Shop ${s.name} has only ${others.length} products for cart lines.`);
      return [s.shopId, { fixed, others }];
    }),
  );
  const carts = new Array(CARTS);
  let done = 0;
  await pool([...Array(CARTS).keys()], 8, async (b) => {
    const shopA = shops[(2 * b) % shops.length];
    const shopB = shops[(2 * b + 1) % shops.length];
    const items = [];
    const line = (product, quantity) => {
      const v = firstVariant(product);
      items.push({ productId: product.id, variantId: v.id, shopId: product.shopId, quantity, unitPrice: v.price });
    };
    const { fixed } = pools[shopA.shopId];
    line(fixed, 1); // the target line: quantity alternates 1/2 during the test
    for (const [shop, count] of [[shopA, LINES_PER_SHOP - 1], [shopB, LINES_PER_SHOP]]) {
      const { others } = pools[shop.shopId];
      const offset = (b * 7) % others.length;
      for (let k = 0; k < count; k++) {
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
      fail(`Cart of ${buyer.email} does not match the fixture after creation (lines ${lines}, total ${cart.totalAmount} != ${expectedTotal}).`);
    }
    carts[b] = { buyer: b, email: buyer.email, userId: buyer.userId, target: { productId: fixed.id, variantId: firstVariant(fixed).id, shopId: fixed.shopId, unitPrice: TARGET_PRICE }, items };
    if (++done % 20 === 0) process.stdout.write(`  ${done}/${CARTS}\r`);
  });
  console.log(`  ${CARTS} carts verified`);
  return carts;
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
  if (STEPS.has('seed') && !flag('skip-seed')) runSeed();

  let catalog = previous.catalog;
  let shops = [];
  if (STEPS.has('catalog') || STEPS.has('carts')) {
    const admin = await login(ADMIN_EMAIL, ADMIN_PASSWORD);
    if (!admin.roles?.includes('ADMIN')) fail(`${ADMIN_EMAIL} is not an ADMIN.`);
    const categoryIds = await ensureCategories(admin.accessToken);
    shops = await sellerShops();
    catalog = await ensureCatalog(categoryIds, shops);
    if (catalog.activeProducts < PRODUCTS) fail(`Only ${catalog.activeProducts} ACTIVE products (need ${PRODUCTS}). Is product-service up?`);
  }
  if (!catalog) {
    console.warn('! No catalog in the manifest yet: catalog/cart test cases need --only=catalog,carts later.');
    catalog = { activeProducts: 0, pageSize: 20, categories: [], variants: 0, images: 0, productIds: [], hidden: {}, byShop: {} };
  }

  let buyerInfo = previous.buyersDetail ? { buyers: previous.buyersDetail, verified: previous.buyers.verified } : null;
  if (STEPS.has('buyers') || !buyerInfo) buyerInfo = await ensureBuyers();
  const carts = STEPS.has('carts') ? await buildCarts(buyerInfo.buyers, catalog, shops) : previous.carts;

  const { byShop, ...catalogOut } = catalog;
  const manifest = {
    runId: `perf-${startedAt.toISOString().replace(/[:.]/g, '-')}`,
    createdAt: startedAt.toISOString(),
    baseUrl: API,
    gitSha: gitSha(),
    buyers: { prefix: PREFIX, domain: DOMAIN, count: buyerInfo.buyers.length, verified: buyerInfo.verified },
    buyersDetail: buyerInfo.buyers,
    catalog: catalogOut,
    carts,
  };
  mkdirSync(dirname(OUT), { recursive: true });
  writeFileSync(OUT, JSON.stringify(manifest, null, 2));
  console.log(`\n[5/5] Manifest ${OUT}`);
  console.log(`  run ${manifest.runId}  sha ${manifest.gitSha?.slice(0, 7)}  buyers ${manifest.buyers.verified}/${manifest.buyers.count} verified`);
  console.log(`  catalog ${catalogOut.activeProducts} ACTIVE, ${catalogOut.categories.length} categories, ${catalogOut.variants} variants, ${catalogOut.images} images; PH=${catalogOut.hidden.PH} PD=${catalogOut.hidden.PD}`);
  console.log(`  carts ${carts?.length || 0}; ${stats.requests} requests, ${stats.waits429} rate-limit waits, ${Math.round((Date.now() - startedAt) / 1000)} s`);
}

main().catch((error) => fail(error.stack || error.message));
