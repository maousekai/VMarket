#!/usr/bin/env node
// Seed du lieu demo Da Nang qua API that cua VMarket (gateway), de su kien/outbox/chi muc
// van chay dung nhu khi nguoi dung thao tac. Chay lai an toan: thu nao da co thi bo qua.
//
//   node scripts/seed-demo-data/seed.mjs            # seed vao http://localhost:8080
//   node scripts/seed-demo-data/seed.mjs --dry-run  # chi in ke hoach, khong goi mang
//   node scripts/seed-demo-data/seed.mjs --refresh  # cap nhat anh/ton kho cho du lieu da seed
//
// Anh that lay theo images.lock.json (Wikimedia Commons, xem resolve-images.mjs), tai ve roi
// upload len MinIO qua API seller nen URL anh la cua VMarket, khong hotlink.
//
// Xem README.md cung thu muc cho dieu kien tien quyet va bien moi truong.
import { execFileSync } from "node:child_process";
import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(here, "../..");
const dataset = JSON.parse(readFileSync(resolve(here, "dataset.json"), "utf8"));

const args = new Set(process.argv.slice(2));
const DRY_RUN = args.has("--dry-run");
const REFRESH = args.has("--refresh");
const RESET_PASSWORDS = args.has("--reset-passwords");
const imageLock = JSON.parse(
  readFileSync(resolve(here, "images.lock.json"), "utf8"),
);
const USER_AGENT = "VMarketDemoSeed/1.0 (local demo data for a student project)";
const API = (process.env.SEED_API_URL || "http://localhost:8080").replace(
  /\/$/,
  "",
);
const ADMIN_EMAIL = process.env.DEV_ADMIN_EMAIL || "admin@vmarket.local";
const ALLOW_REMOTE = args.has("--allow-remote");
const ADMIN_PASSWORD = process.env.DEV_ADMIN_PASSWORD;
const SELLER_PASSWORD = process.env.SEED_PASSWORD;
const CITY = "Đà Nẵng";

const summary = {
  categories: 0,
  accounts: 0,
  shops: 0,
  approved: 0,
  products: 0,
  refreshed: 0,
  skipped: 0,
};

// ---------- helpers ----------
const sleep = (ms) => new Promise((done) => setTimeout(done, ms));

function hash(text) {
  let value = 2166136261;
  for (const char of text)
    value = Math.imul(value ^ char.codePointAt(0), 16777619) >>> 0;
  return value;
}

class ApiError extends Error {
  constructor(status, body, path) {
    super(
      `${status} ${body?.error?.code || ""} ${body?.error?.message || ""} (${path})`.trim(),
    );
    this.status = status;
    this.code = body?.error?.code;
  }
}

async function api(method, path, { token, body, headers = {} } = {}) {
  const response = await fetch(API + path, {
    method,
    headers: {
      ...(body ? { "Content-Type": "application/json" } : {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...headers,
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await response.text();
  let data = null;
  try {
    data = text ? JSON.parse(text) : null;
  } catch {
    // e.g. an HTML 502 page from a proxy; keep the status so callers can react to it
  }
  if (!response.ok)
    throw new ApiError(response.status, data, `${method} ${path}`);
  return data;
}

class NotYet extends Error {}

async function retry(
  label,
  attempt,
  { tries = 15, delay = 2000, when = () => false } = {},
) {
  for (let i = 1; ; i++) {
    try {
      return await attempt();
    } catch (error) {
      if (i >= tries || !when(error)) throw error;
      if (i === 1) console.log(`  … chờ ${label}`);
      await sleep(delay);
    }
  }
}

function codeFromAuthLogs(email, pattern) {
  const logs = execFileSync(
    "docker",
    ["compose", "logs", "--since", "15m", "auth-service"],
    { cwd: repoRoot, encoding: "utf8", maxBuffer: 32 * 1024 * 1024 },
  );
  const entries = logs
    .split(`[DEV EMAIL] to=${email} | subject=`)
    .slice(1)
    .reverse();
  for (const entry of entries) {
    const match = entry.match(pattern);
    if (match) return match[1];
  }
  throw new NotYet("code not in log yet");
}

/**
 * Chi cho tai khoan seed (@seed.vmarket.test) khi chay --reset-passwords: dat lai mat khau
 * ve SEED_PASSWORD qua luong quen mat khau that (ma lay tu log auth-service, provider=log).
 */
async function resetSeedPassword(email) {
  if (!email.endsWith("@seed.vmarket.test"))
    throw new Error(`Không đặt lại mật khẩu cho ${email}`);
  await api("POST", "/api/auth/password/forgot", { body: { email } });
  const code = await retry(
    "mã đặt lại mật khẩu trong log auth-service",
    async () =>
      codeFromAuthLogs(email, /Mã đặt lại mật khẩu của bạn là:\s*(\d{6})/),
    { when: isNotYet },
  );
  await api("POST", "/api/auth/password/reset", {
    body: { email, code, newPassword: SELLER_PASSWORD },
  });
  console.log(`  ~ đặt lại mật khẩu ${email}`);
}

function otpFromAuthLogs(email) {
  const logs = execFileSync(
    "docker",
    ["compose", "logs", "--since", "15m", "auth-service"],
    {
      cwd: repoRoot,
      encoding: "utf8",
      maxBuffer: 32 * 1024 * 1024,
    },
  );
  const entries = logs
    .split(`[DEV EMAIL] to=${email} | subject=`)
    .slice(1)
    .reverse();
  for (const entry of entries) {
    const match = entry.match(/Mã xác thực email của bạn là:\s*(\d{6})/);
    if (match) return match[1];
  }
  throw new NotYet("OTP not in log yet");
}
const isNotYet = (error) => error instanceof NotYet;
const isGatewayHiccup = (error) => error.status === 502 || error.status === 503;

// ---------- images ----------
// Anh tam (loremflickr / picsum) hoac thieu anh: --refresh se thay bang anh that.
const isPlaceholderImage = (url) =>
  !url || /loremflickr\.com|picsum\.photos|placeholder/i.test(url);

const sourceCache = new Map();
async function downloadLocked(query) {
  const item = imageLock[query];
  if (!item)
    throw new Error(
      `Thiếu ảnh cho "${query}" trong images.lock.json — chạy resolve-images.mjs trước.`,
    );
  if (sourceCache.has(item.url)) return sourceCache.get(item.url);
  const bytes = await retry(
    `ảnh ${item.file}`,
    async () => {
      const response = await fetch(item.url, {
        headers: { "User-Agent": USER_AGENT },
      });
      if (!response.ok) throw Object.assign(new Error(`HTTP ${response.status}`), {
        status: response.status,
      });
      return Buffer.from(await response.arrayBuffer());
    },
    { tries: 6, delay: 4000, when: (error) => error.status === 429 || !error.status },
  );
  sourceCache.set(item.url, bytes);
  return bytes;
}

/** Tai anh da khoa cho `query` va upload len MinIO qua endpoint media cua seller. */
async function uploadImage(query, token, endpoint) {
  const bytes = await downloadLocked(query);
  const form = new FormData();
  form.append("file", new Blob([bytes], { type: "image/jpeg" }), "image.jpg");
  const response = await fetch(API + endpoint, {
    method: "POST",
    headers: { Authorization: `Bearer ${token}` },
    body: form,
  });
  const text = await response.text();
  const data = text ? JSON.parse(text) : null;
  if (!response.ok) throw new ApiError(response.status, data, `POST ${endpoint}`);
  return data.url;
}

const productImage = (query, token) =>
  uploadImage(query, token, "/api/products/seller/media");

// ---------- accounts ----------
async function login(email, password) {
  return api("POST", "/api/auth/login", { body: { email, password } });
}

async function sellerSession(shop) {
  const email = `seller.${shop.key}@seed.vmarket.test`;
  const username = `seed.${shop.key}`.slice(0, 50);
  let session;
  try {
    session = await login(email, SELLER_PASSWORD);
  } catch (error) {
    if (error.code !== "INVALID_CREDENTIALS") throw error;
    try {
      await api("POST", "/api/auth/register", {
        body: { email, username, password: SELLER_PASSWORD },
      });
    } catch (registerError) {
      if (registerError.status === 409 && RESET_PASSWORDS) {
        await resetSeedPassword(email);
        session = await login(email, SELLER_PASSWORD);
        return { email, ...session };
      }
      if (registerError.status === 409) {
        throw new Error(
          `${email} đã tồn tại với mật khẩu khác SEED_PASSWORD hiện tại`,
        );
      }
      throw registerError;
    }
    summary.accounts++;
    session = await login(email, SELLER_PASSWORD);
  }
  if (session.status === "PENDING") {
    await api("POST", "/api/auth/otp/request", { body: { email } });
    const otp = await retry(
      "OTP trong log auth-service",
      async () => otpFromAuthLogs(email),
      {
        when: isNotYet,
      },
    );
    session = await api("POST", "/api/auth/otp/verify", {
      body: { email, otp },
    });
  }
  return { email, ...session };
}

async function sellerWithRole(email) {
  return retry(
    "auth-service cấp quyền SELLER",
    async () => {
      const session = await login(email, SELLER_PASSWORD);
      if (!session.roles?.includes("SELLER"))
        throw new NotYet("role not granted yet");
      return session;
    },
    { tries: 20, when: isNotYet },
  );
}

// ---------- seed steps ----------
async function seedCategories(adminToken) {
  const existing = await api("POST", "/api/products/categories/admin/query", {
    token: adminToken,
  });
  const byName = new Map(
    existing.map((category) => [category.name, category.id]),
  );
  const ids = {};
  for (const category of dataset.categories) {
    if (!byName.has(category.name)) {
      const created = await api("POST", "/api/products/categories", {
        token: adminToken,
        body: {
          name: category.name,
          parentId: null,
          sortOrder: category.sortOrder,
          active: true,
        },
      });
      byName.set(category.name, created.id);
      summary.categories++;
      console.log(`+ danh mục ${category.name}`);
    }
    ids[category.key] = byName.get(category.name);
  }
  return ids;
}

async function seedShop(shop, adminToken) {
  const seller = await sellerSession(shop);
  let mine;
  try {
    mine = await api("GET", "/api/shops/me", { token: seller.accessToken });
  } catch (error) {
    if (error.status !== 404) throw error;
    mine = await api("POST", "/api/shops", {
      token: seller.accessToken,
      body: {
        name: shop.name,
        description: shop.description,
        logoUrl: "",
        coverUrl: await uploadImage(
          shop.cover,
          seller.accessToken,
          "/api/shops/me/cover",
        ),
        policies:
          "Đổi trả trong 7 ngày nếu sản phẩm lỗi hoặc không đúng mô tả.",
        contactEmail: seller.email,
        contactPhone: shop.phone,
        province: CITY,
        district: shop.district,
        ward: shop.ward,
        streetAddress: shop.street,
      },
    });
    summary.shops++;
    console.log(`+ gian hàng ${shop.name}`);
  }
  if (isPlaceholderImage(mine.coverUrl) && mine.status === "ACTIVE") {
    const coverUrl = await uploadImage(
      shop.cover,
      seller.accessToken,
      "/api/shops/me/cover",
    );
    mine = await api("PUT", "/api/shops/me", {
      token: seller.accessToken,
      body: {
        name: mine.name,
        description: mine.description,
        logoUrl: mine.logoUrl || "",
        coverUrl,
        policies: mine.policies,
        contactEmail: mine.contactEmail,
        contactPhone: mine.contactPhone,
        province: mine.province,
        district: mine.district,
        ward: mine.ward,
        streetAddress: mine.streetAddress,
      },
    });
    console.log(`  ~ ảnh bìa ${shop.name}`);
  }
  if (mine.status === "PENDING") {
    if (!adminToken) {
      console.warn(`! bỏ qua ${shop.name}: cần DEV_ADMIN_PASSWORD để duyệt`);
      return null;
    }
    mine = await api("POST", `/api/shops/admin/${mine.id}/approve`, {
      token: adminToken,
    });
    summary.approved++;
  }
  if (mine.status !== "ACTIVE") {
    console.warn(`! bỏ qua ${shop.name}: trạng thái ${mine.status}`);
    return null;
  }
  return { shopId: mine.id, session: await sellerWithRole(seller.email) };
}

/** Ton kho theo muc gia: hang dat it hang, do an / tieu dung nhieu hang hon. */
function stockFor(price, seed) {
  const spread = hash(seed);
  if (price >= 2_000_000) return 4 + (spread % 9); // 4-12
  if (price >= 1_000_000) return 10 + (spread % 21); // 10-30
  if (price >= 300_000) return 25 + (spread % 46); // 25-70
  return 60 + (spread % 141); // 60-200
}

function variantsFor(shop, index, price, setKey) {
  const stock = stockFor(price, `${shop.key}-${index}`);
  const set = setKey && dataset.variantSets[setKey];
  if (!set) {
    return [
      {
        sku: `${shop.key}-${index}`,
        attributes: { "Phân loại": "Tiêu chuẩn" },
        price,
        stock,
      },
    ];
  }
  return set.values.map((value) => ({
    sku: `${shop.key}-${index}-${value}`.toUpperCase(),
    attributes: { [set.label]: value },
    price,
    stock: Math.max(2, Math.round(stock / set.values.length)),
  }));
}

async function refreshProduct(product, shop, index, price, query, token) {
  const stock = stockFor(price, `${shop.key}-${index}`);
  const perVariant = Math.max(2, Math.round(stock / product.variants.length));
  const imageUrls = product.imageUrls.some((url) => !isPlaceholderImage(url))
    ? product.imageUrls.filter((url) => !isPlaceholderImage(url))
    : [await productImage(query, token)];
  await api("PUT", `/api/products/${product.id}`, {
    token,
    headers: { "Idempotency-Key": `seed-refresh-${product.id}-${hash(imageUrls[0])}` },
    body: {
      shopId: product.shopId,
      name: product.name,
      description: product.description,
      imageUrls,
      categoryId: product.categoryId,
      brandId: product.brandId,
      status: product.status,
      currency: product.currency,
      variants: product.variants.map((variant) => ({
        id: variant.id,
        sku: variant.sku,
        attributes: variant.attributes,
        price: variant.price,
        // Khong ha ton kho duoi so dang giu cho don chua xong.
        stock: Math.max(perVariant, variant.reservedStock ?? 0),
      })),
    },
  });
  summary.refreshed++;
}

async function seedProducts(shop, shopId, token, categoryId) {
  const mine = await api("POST", "/api/products/seller/query", { token });
  const byName = new Map(mine.map((product) => [product.name, product]));
  for (const [
    index,
    [name, price, keyword, variantSet],
  ] of shop.products.entries()) {
    const existing = byName.get(name);
    if (existing) {
      const needsImage = existing.imageUrls.every(isPlaceholderImage);
      if (REFRESH && needsImage) {
        await refreshProduct(existing, shop, index, price, keyword, token);
        console.log(`  ~ ${name}`);
      } else {
        summary.skipped++;
      }
      continue;
    }
    const body = {
      shopId,
      name,
      description: `${name} từ ${shop.name} (${shop.district}, ${CITY}). ${shop.description}`,
      imageUrls: [await productImage(keyword, token)],
      categoryId,
      brandId: null,
      status: "ACTIVE",
      currency: "VND",
      variants: variantsFor(shop, index, price, variantSet),
    };
    // Product Service learns about the approved shop from the ShopApproved event; retry until
    // that projection catches up instead of failing the whole run.
    await retry(
      "Product Service nhận ShopApproved",
      () =>
        api("POST", "/api/products", {
          token,
          body,
          headers: { "Idempotency-Key": `seed-${shop.key}-${index}` },
        }),
      // 403 only while the ShopApproved projection lags; anything else fails fast.
      {
        when: (error) => isGatewayHiccup(error) || error.status === 403,
        tries: 10,
      },
    );
    summary.products++;
  }
}

function printPlan() {
  const products = dataset.shops.reduce(
    (total, shop) => total + shop.products.length,
    0,
  );
  console.log(`Kế hoạch seed (${API}):`);
  console.log(
    `  ${dataset.categories.length} danh mục, ${dataset.shops.length} gian hàng, ${products} sản phẩm`,
  );
  for (const shop of dataset.shops) {
    const category = dataset.categories.find(
      (item) => item.key === shop.category,
    );
    if (!category)
      throw new Error(
        `Shop ${shop.key} references unknown category ${shop.category}`,
      );
    console.log(
      `  - ${shop.name} · ${shop.district} · ${category.name} · ${shop.products.length} SP`,
    );
  }
}

async function main() {
  printPlan();
  if (DRY_RUN) return;
  const host = new URL(API).hostname;
  if (!["localhost", "127.0.0.1", "::1"].includes(host) && !ALLOW_REMOTE) {
    throw new Error(
      `Từ chối seed vào ${API}: chỉ dành cho môi trường local (thêm --allow-remote nếu chắc chắn).`,
    );
  }
  if (!ADMIN_PASSWORD && !REFRESH) {
    throw new Error(
      "Thiếu DEV_ADMIN_PASSWORD (mật khẩu tài khoản ADMIN dev của auth-service).",
    );
  }
  if (!SELLER_PASSWORD) {
    throw new Error(
      "Thiếu SEED_PASSWORD (mật khẩu cho các tài khoản người bán demo).",
    );
  }
  let adminToken = null;
  let categoryIds;
  if (ADMIN_PASSWORD) {
    const admin = await login(ADMIN_EMAIL, ADMIN_PASSWORD);
    if (!admin.roles?.includes("ADMIN"))
      throw new Error(`${ADMIN_EMAIL} không có quyền ADMIN`);
    adminToken = admin.accessToken;
    categoryIds = await seedCategories(adminToken);
  } else {
    // --refresh khong can admin: danh muc phai co san, doc tu API cong khai.
    const existing = await api("GET", "/api/products/categories");
    const byName = new Map(existing.map((category) => [category.name, category.id]));
    categoryIds = Object.fromEntries(
      dataset.categories.map((category) => [category.key, byName.get(category.name)]),
    );
  }
  for (const shop of dataset.shops) {
    console.log(`• ${shop.name}`);
    const seeded = await seedShop(shop, adminToken);
    if (seeded) {
      await seedProducts(
        shop,
        seeded.shopId,
        seeded.session.accessToken,
        categoryIds[shop.category],
      );
    }
  }
  console.log("\nXong:", summary);
}

main().catch((error) => {
  console.error(`\nSeed thất bại: ${error.message}`);
  process.exitCode = 1;
});
