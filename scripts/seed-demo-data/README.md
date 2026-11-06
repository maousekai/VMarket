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

1. The stack is running: `docker compose up -d`. You need postgres, mongo, redis,
   rabbitmq, auth-service, user-service, shop-service and api-gateway.
2. product-service is running. It isn't in compose yet, so start it on your machine:
   ```
   services/mvnw.cmd -f services/pom.xml -pl product-service spring-boot:run
   ```
   The gateway reaches it through `host.docker.internal:8084`.
3. auth-service uses the email provider `log` (the default `AUTH_EMAIL_PROVIDER=log`).
   The script reads OTP codes from `docker compose logs auth-service`.
4. Node.js 18 or newer.

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
- `--skip-image-check` skips resolving image URLs, which is faster.
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
- stock between 20 and 200, generated deterministically
- variants by clothing size or shoe size

Images come from loremflickr: Creative Commons photos from Flickr, chosen by keyword with a
fixed `lock`. The script follows the redirect and stores the resolved image URL. If that
fails, it falls back to picsum.photos. These images are for demos only and are not product
photos from real sellers.

Shop and product names are fictional. Don't add names of real stores or brands.
