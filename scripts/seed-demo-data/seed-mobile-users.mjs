#!/usr/bin/env node
// Demo accounts for the mobile app (local dev only).
//   buyer   buyer.demo@vmarket.test    / Buyer@12345   (profile + default Da Nang address)
//   shipper shipper.demo@vmarket.test  / Shipper@12345 (created through the delivery admin API)
//
// Requires the local backend (scripts/start-backend-local.ps1) and the dev ADMIN account.
//   DEV_ADMIN_PASSWORD=... SEED_AUTH_LOG_FILE=.logs/auth-service.log node scripts/seed-demo-data/seed-mobile-users.mjs
// Re-running is safe: existing accounts are reused.
import { readFileSync } from "node:fs";

const API = process.env.SEED_API_URL || "http://localhost:8080";
const LOG = process.env.SEED_AUTH_LOG_FILE || ".logs/auth-service.log";
const ADMIN_EMAIL = process.env.DEV_ADMIN_EMAIL || "admin@vmarket.local";
const ADMIN_PASSWORD = process.env.DEV_ADMIN_PASSWORD;
if (!ADMIN_PASSWORD) throw new Error("DEV_ADMIN_PASSWORD is required");
if (!/^https?:\/\/(localhost|127\.0\.0\.1)(:\d+)?$/.test(API)) throw new Error("Local gateway only");

const BUYER = { email: "buyer.demo@vmarket.test", username: "buyer.demo", password: "Buyer@12345" };
const SHIPPER = { email: "shipper.demo@vmarket.test", username: "shipper.demo", password: "Shipper@12345" };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function call(method, path, { token, body, headers = {} } = {}) {
  const res = await fetch(API + path, {
    method,
    headers: {
      "Content-Type": "application/json",
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...headers,
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  let json;
  try {
    json = text ? JSON.parse(text) : null;
  } catch {
    json = text;
  }
  return { status: res.status, json };
}

function otpFor(email) {
  const logs = readFileSync(LOG, "utf8");
  const entries = logs.split(`[DEV EMAIL] to=${email} | subject=`).slice(1).reverse();
  for (const entry of entries) {
    const m = entry.slice(0, 400).match(/\b(\d{6})\b/);
    if (m) return m[1];
  }
  return null;
}

async function verifyByOtp(email) {
  const req = await call("POST", "/api/auth/otp/request", { body: { email } });
  if (req.status !== 200) throw new Error(`otp/request ${email}: ${req.status} ${JSON.stringify(req.json)}`);
  let code = null;
  for (let i = 0; i < 20 && !code; i++) {
    await sleep(500);
    code = otpFor(email);
  }
  if (!code) throw new Error(`OTP for ${email} not found in ${LOG}`);
  const res = await call("POST", "/api/auth/otp/verify", { body: { email, otp: code } });
  if (res.status !== 200) throw new Error(`otp/verify ${email}: ${res.status} ${JSON.stringify(res.json)}`);
  return res.json;
}

async function login({ email, password }) {
  return call("POST", "/api/auth/login", { body: { email, password } });
}

// ---- buyer
let res = await login(BUYER);
if (res.status === 401) {
  const reg = await call("POST", "/api/auth/register", { body: BUYER });
  if (reg.status !== 201 && reg.status !== 409) throw new Error(`register: ${reg.status} ${JSON.stringify(reg.json)}`);
  await verifyByOtp(BUYER.email);
  res = await login(BUYER);
}
if (res.status !== 200) throw new Error(`buyer login: ${res.status} ${JSON.stringify(res.json)}`);
const buyerToken = res.json.accessToken;
await call("PUT", "/api/users/me", {
  token: buyerToken,
  body: { fullName: "Nguyễn Văn An", phone: "0912345678", avatarUrl: null, dateOfBirth: "2000-05-17", gender: null },
});
const addrs = await call("GET", "/api/users/me/addresses", { token: buyerToken });
if (addrs.status === 200 && addrs.json.length === 0) {
  const a = await call("POST", "/api/users/me/addresses", {
    token: buyerToken,
    headers: { "Idempotency-Key": "demo-buyer-address-1" },
    body: {
      recipientName: "Nguyễn Văn An",
      phone: "0912345678",
      province: "Thành phố Đà Nẵng",
      ward: "Phường Hải Châu",
      streetAddress: "54 Nguyễn Lương Bằng",
      note: "Gọi trước khi giao",
    },
  });
  console.log("buyer address:", a.status);
}
console.log("buyer ready:", BUYER.email, "/", BUYER.password);

// ---- shipper (admin creates account + profile; shipper must verify the email by OTP)
res = await login(SHIPPER);
if (res.status === 401 || res.status === 403) {
  const admin = await login({ email: ADMIN_EMAIL, password: ADMIN_PASSWORD });
  if (admin.status !== 200) throw new Error(`admin login: ${admin.status} ${JSON.stringify(admin.json)}`);
  const created = await call("POST", "/api/delivery/admin/shippers", {
    token: admin.json.accessToken,
    headers: { "Idempotency-Key": "demo-shipper-0001" },
    body: {
      account: SHIPPER,
      name: "Trần Văn Giao",
      phone: "0905123456",
      province: "Đà Nẵng",
      district: "Hải Châu",
    },
  });
  console.log("create shipper:", created.status, created.status >= 300 ? JSON.stringify(created.json) : "");
  await verifyByOtp(SHIPPER.email);
  res = await login(SHIPPER);
}
if (res.status !== 200) throw new Error(`shipper login: ${res.status} ${JSON.stringify(res.json)}`);
console.log("shipper ready:", SHIPPER.email, "/", SHIPPER.password, "roles:", res.json.roles);
