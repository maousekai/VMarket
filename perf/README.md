# Performance tests (k6)

k6 scripts for the 18 performance test cases in the test report (sheet "Performance
Test"). They verify the performance requirements of **SRS v1.1, section 6.1**, plus the
timing and consistency requirements those depend on. The test cases themselves (texts,
priorities, traceability, how each one runs) live in one file,
`perf/report/test-cases.mjs`. The runner and the Excel generator both read it.

| Requirement (SRS v1.1) | Threshold | Test cases |
|---|---|---|
| NFR-PERF-01 ordinary read APIs | p95 ≤ 500 ms, per endpoint, measured phase | PERF-READ-01..03 |
| NFR-PERF-02 search / autocomplete | p95 ≤ 1 s / ≤ 300 ms | PERF-SRCH-01, 02 |
| NFR-PERF-05 image search | p95 ≤ 3 s | PERF-SRCH-03 |
| FR-SRCH-04, UC-11 index sync | new product findable ≤ 60 s (max) | PERF-SRCH-04 |
| NFR-PERF-03 ≥ 200 concurrent users | all thresholds held at 200 VUs; stress breaking point ≥ 200 | PERF-LOAD-01..05 |
| FR-ORD-01, FR-PROD-02, UC-06 stock consistency | no oversell, reserved = active orders, released on cancel | PERF-LOAD-06 |
| NFR-REL-02, UC-03 fallback | core journeys keep thresholds while AI services are down | PERF-LOAD-07 |
| NFR-PERF-04 (+ NFR-USA-03 360 px) home page | LCP and main content p95 ≤ 3 s on a regular network | PERF-WEB-01, 02 |
| NFR-PERF-05 shipper position | send → buyer's WebSocket p95 ≤ 5 s | PERF-SHIP-01, 02 |

Proposed thresholds (Q), only for writes, which the SRS gives no latency for: login,
cart writes, pre-checkout check, cancel p95 ≤ 1 s; place order ≤ 2 s. For all test
cases: unexpected failures < 1 %, business checks ≥ 99 %, ≥ 200 samples on busy
endpoints. Data invariants (money, stock, leaked HIDDEN/DRAFT products) have no tolerance.

The gateway's rate limiting (SRS 5.3) has no performance threshold. Its test belongs
in the API Test sheet.

## Layout

```
perf/
  run.mjs                    runs test cases per round, keeps evidence, decides the verdict
  docker-compose.perf.yml    product, cart, order, delivery services (not in the root compose yet)
  fixtures/prepare.mjs       buyers, shippers, catalog, carts, order history, deliveries, photos + manifest
  report/test-cases.mjs      the 18 test cases (single source of truth)
  report/rebuild-excel.mjs   writes them into the Excel report (sheet layout, formulas, totals)
  report/fill-excel.mjs      writes a round's results (status, date, tester) + report.md/csv
  tc/read.js                 PERF-READ-01..03, and the background load of WEB-02 / SHIP-02
  tc/search.js               PERF-SRCH-01, 02
  tc/image-search.js         PERF-SRCH-03
  tc/index-sync.js           PERF-SRCH-04
  tc/load.js                 PERF-LOAD-01..05, 07 (mixed journeys)
  tc/contention.js           PERF-LOAD-06
  tc/web.js                  PERF-WEB-01, 02 (real Chromium)
  tc/ship.js                 PERF-SHIP-01, 02 (WebSocket)
  flows/                     read, browse, search, cart, checkout, account journeys
  lib/                       profiles and phases, thresholds, endpoints, sessions, probes, summary
```

### Workload of the mixed runs (per 200 VUs)

70 guest browsing (UC-04), 30 search (UC-03), 50 cart (UC-05), 20 COD checkout
(UC-06 A1), 30 account and order tracking (UC-02, UC-07). Stress scales the same mix
up to 400 VUs. Only COD: VNPay, refunds and reconciliation are never called.

### Profiles and phases

| Profile | Shape | Acceptance phase |
|---|---|---|
| smoke | 15 s warm-up, 1 VU per journey × 1 min | hold (no p95 conclusion) |
| baseline | warm-up 1 min @10, ramp 30 s to 50, hold 5 min | hold |
| load / load15 | ramp 30 s to 200, hold 10 / 15 min | hold |
| soak | ramp to 200, hold 60 min (`SOAK_MINUTES`) | hold |
| spike | 2 min @50, 300 in 10 s, 2 min, back to 50, 5 min | last 3 min of recovery |
| stress | 100 → 400 VUs in steps of 50 × 3 min, then 5 min @50 | recovery; the breaking point is reported |

Every request carries `endpoint` and `phase` tags. Thresholds use the acceptance
phase only. Warm-up, ramps, the spike and the stress steps stay in the raw metrics.

## 1. Environment (ENV-P)

- A stack used only for this test, on a machine meeting **SRS 5.2 (≥ 4 vCPU, 8 GB RAM)**.
  k6 runs on a different machine. Record the machine, container/JVM limits, instances
  (one cart-service), build SHA, network path and log level.
- Start the services that the root compose file does not run yet:
  ```
  docker compose -f docker-compose.yml -f perf/docker-compose.perf.yml up -d --build product-service cart-service order-service delivery-service
  ```
  Search, image search and recommendations need ai-search-service and
  recommendation-service. Without them, those test cases end as **Pending: blocked**.
- Capacity runs need `APP_RATE_LIMIT_ENABLED=false` on the gateway: restart it and check
  the setting took effect. The limiter counts per connecting address, so behind a
  tunnel or proxy every user shares one quota (PBL6-81).
- Through Cloudflare or a tunnel: add a rule that skips WAF and rate limiting for the
  k6 IP. Note the network path in the report. Only `/api` goes through the front-end
  proxy. The tracking WebSocket uses `DELIVERY_WS_URL`.
- Keep Grafana open (`infra/monitoring/`) for CPU/RAM/GC, DB pool and Redis.

## 2. Fixture

Run it **on the stack host**, against the local gateway, with `AUTH_EMAIL_PROVIDER=log`
(it reads OTP codes from the auth-service log):

```
DEV_ADMIN_PASSWORD=... SEED_PASSWORD=... PERF_PASSWORD='<Strong1!pass>' node perf/fixtures/prepare.mjs
```

| Step (`--only=`) | Creates |
|---|---|
| seed | demo shops and sellers (`scripts/seed-demo-data`) |
| catalog | ≥ 1000 ACTIVE products, 10 categories, PH (HIDDEN), PD (DRAFT), a 100000-VND product per shop, 5 contention products × 50 units |
| buyers | 850 verified buyers with a default address: shopper 0-399, order 400-799, delivery 800-849 |
| history | 2 cancelled COD orders for shoppers 0-199 |
| carts | 400 carts × 20 lines in two shops (also resets carts to the fixture state) |
| deliveries | 50 shippers; 50 orders handed off, assigned and in transit (UC-23) |
| images | query photos for image search, one 6-9 MB PNG |

It writes `perf/fixtures/out/manifest.json`: ids, counts, expected carts, order
history, contention stock, deliveries, run id and git SHA. It holds no secrets. Copy
it to the k6 machine or pass `-e MANIFEST=<path>`. Re-running is safe.
`BUYER_PREFIX` (default `perf.p73`) picks a fresh set of accounts.

## 3. Run a round

```
BASE_URL=http://<gateway> WEB_URL=http://<front end> ALLOW_REMOTE=true PERF_PASSWORD=... SEED_PASSWORD=... \
  node perf/run.mjs --round=1 --tc=PERF-READ-01,PERF-LOAD-01 --tester="<your name>"
node perf/run.mjs --round=1 --tc=all --tester="<your name>"
```

Start with the smoke cases. PERF-LOAD-07 asks you to stop the AI services when its
hold phase starts. WEB-02 and SHIP-02 start `tc/read.js` (200 VUs) in the background.
`K6=<path>` if k6 is not on PATH. `K6_PROMETHEUS_RW_SERVER_URL` streams to Prometheus.

Evidence in `perf/results/R<round>/<TC>/` (git-ignored): `summary.json`, `metrics.csv.gz`
(raw), `timeline.csv` (per minute), `k6.log`, `run.json`, plus `companion/` for the
background load.

### Verdict

| Situation | Excel |
|---|---|
| Money, stock or leak invariant broken | **Failed** (the run stops at the first one) |
| A precondition not met: service not deployed, fixture missing | Pending: blocked |
| Missing environment variable | Pending: not run |
| Login 401/423, or > 10 % unexpected failures for 60 s | Pending: aborted |
| An acceptance threshold crossed; stress breaking point < 200 VUs | **Failed** |
| LOAD-07 without any fallback (AI not stopped), contention never hit | Pending: invalid run |
| Ran on the UC-03 fallback because ai-search was down | Pending: degraded |
| Fewer samples than required | Pending: inconclusive |
| Everything within thresholds | **Passed** |

Before you mark soak, spike or stress final, also check Grafana for OOM, crashes and
unexplained growth.

## 4. The Excel report

- `node perf/report/rebuild-excel.mjs` rewrites the performance part of
  `docs/VMarket_Test_Report.xlsx` from `test-cases.mjs`: the sheet layout, formulas,
  drop-downs, the Test Cases function table and rules, and the totals. The first run
  keeps a backup next to the file.
- `node perf/report/fill-excel.mjs --round=1 [--in-place]` writes a round's status, date
  and tester. It also writes `perf/results/R1/report.md` and `report.csv` with the
  actual values and evidence paths.

The workbook is a local working file and is not committed.

## Safety

- Only ENV-P or a local stack: scripts that change data refuse a non-local `BASE_URL`
  without `ALLOW_REMOTE=true` (`--allow-remote` for the fixture).
- COD only. Checkout orders are cancelled at once. Contention orders are cancelled in
  teardown, and the reservation must return to 0.
- Cart and order checks compare with the manifest, never with the response under test.
