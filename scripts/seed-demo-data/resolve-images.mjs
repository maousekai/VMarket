#!/usr/bin/env node
// Chon anh that cho du lieu demo tu Wikimedia Commons va ghi vao images.lock.json.
//
//   node scripts/seed-demo-data/resolve-images.mjs            # chi tim tu khoa chua co trong lock
//   node scripts/seed-demo-data/resolve-images.mjs --all      # tim lai tat ca
//   node scripts/seed-demo-data/resolve-images.mjs --pick "air fryer=2"   # doi sang ung vien thu 3
//
// Lock giu file Commons da chon (kem tac gia + giay phep de ghi nguon), nen seed.mjs chay lai
// luon ra cung mot anh. seed.mjs tai anh ve va upload len MinIO qua API seller - khong hotlink.
import { readFileSync, writeFileSync, existsSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const datasetPath = join(here, "dataset.json");
const lockPath = join(here, "images.lock.json");
const API = "https://commons.wikimedia.org/w/api.php";
const USER_AGENT = "VMarketDemoSeed/1.0 (local demo data for a student project)";
const THUMB_WIDTH = 900;
const CANDIDATES = 8;

const args = process.argv.slice(2);
const all = args.includes("--all");
const picks = new Map(
  args
    .filter((_, index) => args[index - 1] === "--pick")
    .map((value) => {
      const at = value.lastIndexOf("=");
      return [value.slice(0, at), Number(value.slice(at + 1))];
    }),
);

const dataset = JSON.parse(readFileSync(datasetPath, "utf8"));
const lock = existsSync(lockPath) ? JSON.parse(readFileSync(lockPath, "utf8")) : {};
const sleep = (ms) => new Promise((done) => setTimeout(done, ms));
const stripTags = (html = "") =>
  html.replace(/<[^>]*>/g, "").replace(/\s+/g, " ").trim();

async function candidates(query) {
  const params = new URLSearchParams({
    action: "query",
    format: "json",
    generator: "search",
    gsrnamespace: "6",
    gsrlimit: String(CANDIDATES * 2),
    gsrsearch: `${query} filetype:bitmap`,
    prop: "imageinfo",
    iiprop: "url|mime|size|extmetadata",
    iiurlwidth: String(THUMB_WIDTH),
  });
  for (let attempt = 0; attempt < 4; attempt++) {
    const response = await fetch(`${API}?${params}`, {
      headers: { "User-Agent": USER_AGENT },
    });
    if (response.ok) {
      const body = await response.json();
      return Object.values(body.query?.pages ?? {})
        .sort((a, b) => a.index - b.index)
        .map((page) => ({ title: page.title, info: page.imageinfo?.[0] }))
        .filter(
          ({ info }) =>
            info &&
            info.mime === "image/jpeg" &&
            info.width >= 600 &&
            info.height >= 450 &&
            info.width / info.height < 2.2 &&
            info.height / info.width < 1.6,
        )
        .slice(0, CANDIDATES);
    }
    const wait = Number(response.headers.get("retry-after")) * 1000 || 5000 * (attempt + 1);
    await sleep(wait);
  }
  return null;
}

function entry({ title, info }, query, index) {
  const meta = info.extmetadata ?? {};
  return {
    query,
    candidate: index,
    file: title,
    url: info.thumburl,
    page: info.descriptionurl,
    artist: stripTags(meta.Artist?.value) || "Unknown",
    license: meta.LicenseShortName?.value || "See file page",
  };
}

const queries = [
  ...dataset.shops.map((shop) => shop.cover),
  ...dataset.shops.flatMap((shop) => shop.products.map((product) => product[2])),
];
const used = new Set(
  Object.values(lock)
    .filter(Boolean)
    .map((item) => item.file),
);
let changed = 0;
for (const query of [...new Set(queries)]) {
  const pick = picks.get(query);
  if (!all && lock[query] && pick === undefined) continue;
  const found = await candidates(query);
  if (!found) {
    console.warn(`! Commons đang giới hạn tốc độ, bỏ qua "${query}" (chạy lại sau)`);
    continue;
  }
  if (!found.length) {
    console.warn(`! không có ảnh phù hợp cho "${query}"`);
    continue;
  }
  if (lock[query]) used.delete(lock[query].file);
  const index =
    pick !== undefined
      ? Math.min(pick, found.length - 1)
      : Math.max(
          0,
          found.findIndex((item) => !used.has(item.title)),
        );
  lock[query] = entry(found[index], query, index);
  used.add(lock[query].file);
  changed++;
  console.log(`+ ${query} -> ${lock[query].file}`);
  await sleep(300);
}

const ordered = Object.fromEntries(
  Object.keys(lock)
    .sort()
    .map((key) => [key, lock[key]]),
);
writeFileSync(lockPath, `${JSON.stringify(ordered, null, 2)}\n`);
console.log(`Đã cập nhật ${changed} ảnh trong images.lock.json`);
