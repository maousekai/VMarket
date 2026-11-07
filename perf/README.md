# Performance tests (k6)

Load tests for the non-functional requirements in SRS §6.1:

| NFR | Requirement | Checked by |
|---|---|---|
| NFR-PERF-01 | Ordinary read APIs ≤ 500 ms (p95) | phases 1, 2, 4, 5 |
| NFR-PERF-02 | Search ≤ 1 s, autocomplete ≤ 300 ms (p95) | phase 3, 5 |
| NFR-PERF-03 | Stable with ≥ 200 concurrent users | phase 5 |

Every script sets k6 `thresholds` from these numbers, so a run **exits non-zero when an
NFR is missed**. It also fails on more than 1 % failed requests or failed checks.

## Layout

```
perf/
  lib/config.js        env vars, load profiles, NFR thresholds, remote-target guard
  lib/api.js           HTTP helpers; every request is tagged with an `endpoint` name
  lib/session.js       per-VU buyer login (once per VU, renewed before the token expires)
  lib/catalog.js       reads the seeded catalog in setup() so VUs hit varied ids
  lib/shops.js         approved shop ids + seed seller accounts
  flows/               user journeys reused by the scenarios
  setup/create-buyers.js
  scenarios/01-account-shop.js   phase 1  login, profile, shop pages
  scenarios/02-catalog.js        phase 2  categories, listings, brands, product detail
  scenarios/03-search.js         phase 3  search + autocomplete
  scenarios/04-cart-order.js     phase 4  cart + COD checkout (test DB only)
  scenarios/05-mixed.js          phase 5  all flows together, the official 200-user run
```

## Prerequisites

1. **k6** v1.0 or newer (`winget install k6`, `choco install k6` or `brew install k6`).
2. **The stack**, depending on the phase:

   | Phase | Needs |
   |---|---|
   | 1 | `docker compose up -d` (auth, user, shop services + api-gateway) |
   | 2 | phase 1 + product-service + seed data |
   | 3 | phase 2 + ai-search-service + Elasticsearch indexed from the seed catalog |
   | 4 | phase 2 + cart-service + order-service |
   | 5 | everything above, on VPS/staging |

   Services not in compose yet run on the host, for example
   `services/mvnw.cmd -f services/pom.xml -pl product-service spring-boot:run`.
   The gateway reaches them through `host.docker.internal`.
3. **Seed data**: `scripts/seed-demo-data/seed.mjs` (12 shops, 96 products, seller
   accounts). Phase 1 can run without product-service as long as the seed sellers exist,
   or you pass `SHOP_IDS`.
4. **Gateway rate limit off, on the test environment only.** The gateway allows 200
   requests per minute per IP, and every VU comes from the k6 machine's IP. Put this in
   `.env`, then run `docker compose up -d api-gateway`:
   ```
   APP_RATE_LIMIT_ENABLED=false
   ```
   Write the setting you used in the report. Set it back to `true` (or delete the line)
   afterwards. The scripts warn when they get HTTP 429.
5. **Test buyers**, created once per environment:
   ```
   k6 run -e PERF_PASSWORD='<Strong1!pass>' -e BUYER_COUNT=200 perf/setup/create-buyers.js
   ```
   This creates `perf.buyer.0000@perf.vmarket.test` … `perf.buyer.0199@…`, each with a
   delivery address. It is safe to re-run. The `.test` domain never receives mail.
   The accounts stay PENDING (email not verified), which login, cart and orders accept.

## Running

Start with a **smoke** run to check that a script works, then use the other profiles:

```
k6 run -e PERF_PASSWORD=... -e SEED_PASSWORD=... perf/scenarios/01-account-shop.js
k6 run -e PROFILE=load perf/scenarios/02-catalog.js
k6 run -e PROFILE=load -e PERF_PASSWORD=... perf/scenarios/04-cart-order.js
```

Save results with `--summary-export`:

```
k6 run -e PROFILE=load --summary-export perf/results/02-catalog-load.json perf/scenarios/02-catalog.js
```

`perf/results/` is git-ignored.

### Profiles (`PROFILE`)

| Profile | Shape | Use |
|---|---|---|
| `smoke` (default) | 1–5 VUs for `DURATION` (1m) | check the script and environment |
| `load` | ramp to `VUS` over `RAMP` (3m), hold for `HOLD` (5m), ramp down | NFR check; use `HOLD=15m` for phase 5 |
| `stress` | steps of 0.5×, 1×, 1.5×, 2×, 2.5× `VUS` | find the breaking point |
| `soak` | `VUS`/2 for `SOAK_DURATION` (1h) | memory and connection leaks |

### Environment variables

| Variable | Default | Meaning |
|---|---|---|
| `BASE_URL` | `http://localhost:8080` | API gateway |
| `PROFILE` | `smoke` | see above |
| `VUS` | `200` | target virtual users |
| `RAMP`, `HOLD`, `DURATION`, `SOAK_DURATION` | `3m`, `5m`, `1m`, `1h` | profile timings |
| `PERF_PASSWORD` | _(required for 01, 04, 05, setup)_ | password of the test buyers |
| `BUYER_COUNT` | `200` | number of test buyers; must be ≥ `VUS` for 04 and 05 |
| `SEED_PASSWORD` | _(optional)_ | seed seller password: enables the seller "my shop" flow and supplies shop ids |
| `SHOP_IDS` | _(optional)_ | comma-separated shop ids, overrides the other sources |
| `MAX_PRODUCTS` | `2000` | how many products setup() reads from the catalog |
| `THINK_MIN`, `THINK_MAX` | `1`, `3` | think time between actions, in seconds |
| `ORDER_RATIO` | `0.3` | share of phase-4 iterations that place an order |
| `CANCEL_AFTER_ORDER` | `true` | cancel each order straight away so its stock is released |
| `MIX` | `browse:60,account:20,search:10,checkout:10` | phase 5 flow weights |
| `INCLUDE_SEARCH` | `true` | `false` drops search from phase 5 while ai-search is not deployed |
| `ALLOW_REMOTE` | `false` | required to run data-creating scripts against a non-local gateway |

## Safety (phase 4 and 5)

- **Test environment and test database only.** Scripts that create data (setup, 04, 05)
  refuse a non-local `BASE_URL` unless you pass `-e ALLOW_REMOTE=true`.
- **COD only.** The scripts never use PayOS and never call payment, refund or
  reconciliation endpoints. Keep `PAYOS_ENABLED=false` on the target.
- Each order uses a unique `Idempotency-Key`. The script sends it a second time and
  requires the same order back (threshold `idempotency_replay_same_order == 1`).
- Orders are cancelled while still PENDING so the reserved stock is released and the
  seed inventory lasts. If you set `CANCEL_AFTER_ORDER=false` to measure stock contention,
  re-run the seed (or restock) afterwards.
- **After a run, reconcile:** `orders_created` and `orders_cancelled` in the k6 summary
  should match the orders that the test buyers have in the database (note `perf-test`).
  Once the cancellations are processed (product-service releases the reservation when it
  receives the CANCELLED event), each variant's `reservedStock` should be back to its
  pre-run value.
- **Clean-up:** the test buyers' orders and carts can be deleted from the test database,
  or the environment reset with `docker compose down -v` (this deletes **all** local data).

## Measuring for the report (phase 5)

- Run k6 on a **different machine** from the system under test. Otherwise k6 competes
  with the services for CPU and the numbers are meaningless. Local runs only check that
  the scripts work.
- Watch CPU, memory, JVM heap, DB connection pool and RabbitMQ queues in Grafana
  (`infra/monitoring/`, dashboard "VMarket - Services Overview") during the run.
- Record: VPS spec, commit, data size (products, shops, buyers), k6 machine spec, the
  rate-limit setting, then p95/p99, error rate and throughput per endpoint (from the k6
  summary), each with pass/fail against NFR-PERF-01..03.
