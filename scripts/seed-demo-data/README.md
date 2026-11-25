# Demo data — Đà Nẵng

Seeds the local stack with demo data so the homepage, search and the performance tests
have realistic content:

- 8 categories
- 12 shops spread across the 6 districts
- 96 products

All of it goes through the **real APIs** behind the gateway: register → OTP → open shop →
admin approval → create products. Events, the outbox, product ratings and the search index
therefore work exactly as they do when real users act.

## Prerequisites

1. The stack is running: `docker compose up -d` (postgres, mongo, redis, rabbitmq, minio,
   auth-, user-, shop-, product-service and api-gateway).
2. auth-service uses the email provider `log` (the default `AUTH_EMAIL_PROVIDER=log`).
   The script reads OTP codes from `docker compose logs auth-service`.
3. Node.js 18 or newer.

## Run

```
DEV_ADMIN_PASSWORD=<dev admin password> SEED_PASSWORD=<seller password> node scripts/seed-demo-data/seed.mjs
```

| Variable | Default | Meaning |
|---|---|---|
| `DEV_ADMIN_PASSWORD` | _(required)_ | Password of the dev ADMIN account (`DevAdminSeeder`) |
| `DEV_ADMIN_EMAIL` | `admin@vmarket.local` | Email of the ADMIN account |
| `SEED_API_URL` | `http://localhost:8080` | API Gateway |
| `SEED_PASSWORD` | _(required)_ | Password of the seed seller accounts (local dev only) |

Options:
- `--dry-run` prints the plan and validates the dataset without calling the network.
- `--refresh` updates data seeded earlier: products and shop covers that still use
  placeholder images (loremflickr / picsum) get the real photo and a stock level that fits
  their price. `DEV_ADMIN_PASSWORD` is optional in this mode.
- `--reset-passwords` (local only, seed accounts `@seed.vmarket.test` only): if a seed seller
  exists with another password, reset it to `SEED_PASSWORD` through the real forgot-password
  flow (code read from the auth-service log).
- `--allow-remote` allows a gateway other than localhost. It refuses by default, because the script creates seller accounts and approves shops.

Re-running is safe. Categories, accounts, shops and products that already exist (matched
by name) are skipped. Each product also has a fixed `Idempotency-Key`
(`seed-<shop>-<index>`).

Seller accounts are named `seller.<shop-key>@seed.vmarket.test`. The `.test` domain is
reserved and never receives mail.

## Data

`dataset.json` holds the categories, the shops (district, ward, street, phone) and the
products in compact form `[name, price in VND, image keyword, variant set]`. `seed.mjs`
expands each one into a full `ProductRequest`:
- a description built from the shop
- the SKU
- stock by price, generated deterministically: from 4–12 for items over 2 million VND up to
  60–200 for everyday goods under 300,000 VND
- variants by clothing size or shoe size

### Images

The third field of each product (and `cover` of each shop) is an English search query.
`resolve-images.mjs` picks a freely licensed JPEG for it on Wikimedia Commons and records the
choice in `images.lock.json` (file, thumbnail URL, author, licence). `seed.mjs` downloads the
locked file and uploads it through the seller media API, so products point at VMarket's own
MinIO, not at Commons.

```
node scripts/seed-demo-data/resolve-images.mjs                     # queries missing from the lock
node scripts/seed-demo-data/resolve-images.mjs --pick "air fryer=3" # use another candidate
```

Authors and licences of every photo are listed in `images.lock.json`; keep that file with the
data. The photos are illustrations, not product photos from real sellers.

Shop and product names are fictional. Don't add names of real stores or brands.
