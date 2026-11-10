#!/usr/bin/env node
// Seed du lieu demo Da Nang qua API that cua VMarket (gateway), de su kien/outbox/chi muc
// van chay dung nhu khi nguoi dung thao tac. Chay lai an toan: thu nao da co thi bo qua.
//
//   node scripts/seed-demo-data/seed.mjs            # seed vao http://localhost:8080
//   node scripts/seed-demo-data/seed.mjs --dry-run  # chi in ke hoach, khong goi mang
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
const CHECK_IMAGES = !args.has("--skip-image-check");
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

function otpFromAuthLogs(email) {
  // SEED_AUTH_LOG_FILE: read the log of an auth-service started with `spring-boot:run`
  // (e.g. .logs/auth-service.log) instead of the docker compose container logs.
  const logs = process.env.SEED_AUTH_LOG_FILE
    ? readFileSync(process.env.SEED_AUTH_LOG_FILE, "utf8")
    : execFileSync("docker", ["compose", "logs", "--since", "15m", "auth-service"], {
        cwd: repoRoot,
        encoding: "utf8",
        maxBuffer: 32 * 1024 * 1024,
      });
  const entries = logs
    .split(`[DEV EMAIL] to=${email} | subject=`)
    .slice(1)
    .reverse();
  for (const entry of entries) {
    // Encoding-agnostic: the first standalone 6-digit number right after the header line.
    const match = entry.slice(0, 400).match(/\b(\d{6})\b/);
    if (match) return match[1];
  }
  throw new NotYet("OTP not in log yet");
}
const isNotYet = (error) => error instanceof NotYet;
const isGatewayHiccup = (error) => error.status === 502 || error.status === 503;

// ---------- images ----------
const imageCache = new Map();
async function imageUrl(keyword, seed) {
  const lock = hash(seed) % 10000;
  const source = `https://loremflickr.com/600/600/${encodeURIComponent(keyword)}?lock=${lock}`;
  const fallback = `https://picsum.photos/seed/${hash(seed)}/600/600`;
  if (!CHECK_IMAGES || DRY_RUN) return source;
  if (imageCache.has(source)) return imageCache.get(source);
  let url = fallback;
  try {
    const response = await fetch(source, { redirect: "follow" });
    if (
      response.ok &&
      response.headers.get("content-type")?.startsWith("image/")
    ) {
      url = response.url; // the resolved CDN file, stable across runs
    }
  } catch {
    // keep the fallback image
  }
  imageCache.set(source, url);
  return url;
}

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
        coverUrl: await imageUrl(shop.cover, `cover-${shop.key}`),
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
  if (mine.status === "PENDING") {
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

function variantsFor(shop, index, price, setKey) {
  const stock = 20 + (hash(`${shop.key}-${index}`) % 180);
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
    stock: Math.max(5, Math.round(stock / set.values.length)),
  }));
}

async function seedProducts(shop, shopId, token, categoryId) {
  const listed = await api("GET", `/api/products?shopId=${shopId}&size=100`);
  const names = new Set(listed.content.map((product) => product.name));
  for (const [
    index,
    [name, price, keyword, variantSet],
  ] of shop.products.entries()) {
    if (names.has(name)) {
      summary.skipped++;
      continue;
    }
    const body = {
      shopId,
      name,
      description: `${name} từ ${shop.name} (${shop.district}, ${CITY}). ${shop.description}`,
      imageUrls: [await imageUrl(keyword, `${shop.key}-${index}`)],
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
  if (!ADMIN_PASSWORD) {
    throw new Error(
      "Thiếu DEV_ADMIN_PASSWORD (mật khẩu tài khoản ADMIN dev của auth-service).",
    );
  }
  if (!SELLER_PASSWORD) {
    throw new Error(
      "Thiếu SEED_PASSWORD (mật khẩu cho các tài khoản người bán demo).",
    );
  }
  const admin = await login(ADMIN_EMAIL, ADMIN_PASSWORD);
  if (!admin.roles?.includes("ADMIN"))
    throw new Error(`${ADMIN_EMAIL} không có quyền ADMIN`);
  const categoryIds = await seedCategories(admin.accessToken);
  for (const shop of dataset.shops) {
    console.log(`• ${shop.name}`);
    const seeded = await seedShop(shop, admin.accessToken);
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
