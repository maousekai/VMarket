# Performance tests (k6)

k6 scripts for the 15 performance test cases in the test report (sheet "Performance
Test"): PERF-AUTH-01..05, PERF-CATALOG-01..05, PERF-CART-01..05. Workload rules,
thresholds and profiles follow the "Quy tắc workload hiệu năng" section of sheet "Test Cases".

| Requirement | Threshold | Kind |
|---|---|---|
| NFR-PERF-01 | GET /api/products, /api/products/{id}, /api/cart p95 ≤ 500 ms in the measured phase | R |
| NFR-PERF-03 | 200 active VUs held, with VU count and throughput reported | R |
| Login, PATCH cart | p95 ≤ 1000 ms | Q |
| All | unexpected HTTP failure < 1 %, business checks ≥ 99 %, ≥ 200 samples per endpoint | Q |
| Cart data | money / quantity / owner always match the fixture (no tolerance) | Q |

NFR-PERF-02 (search) and NFR-PERF-04 (home page) are out of scope for this round.

## Layout

```
perf/
  run.mjs                    runs test cases per round, stores evidence, decides the verdict
  fixtures/prepare.mjs       PERF-USERS / PERF-CATALOG / cart fixture + manifest.json
  report/fill-excel.mjs      writes Round N result, test date, tester into the Excel report
  tc/auth.js                 PERF-AUTH-01..05     login -> logout
  tc/catalog.js              PERF-CATALOG-01..05  60 % list, 20 % category, 20 % detail
  tc/cart.js                 PERF-CART-01..03     GET -> PATCH 1/2 -> GET
  tc/mixed.js                PERF-CART-04         120 catalog + 60 cart + 20 auth, soak
  tc/rate-limit-guard.js     PERF-CART-05         limiter ON: request 201 must be 429
  flows/  lib/               workloads, profiles/phases/thresholds, sessions, summary
```

| Test case | Script | Profile |
|---|---|---|
| *-01 | auth / catalog / cart | smoke: 15 s warm-up, then 1 VU × 1 min |
| *-02 | auth / catalog / cart | baseline: warm-up 1 min @10 VU, ramp 30 s to 50, hold 5 min |
| AUTH-03, CATALOG-03, CART-03 | auth / catalog / cart | load: warm-up, ramp 30 s to 200, hold 10 min |
| AUTH-04, CATALOG-04 | auth / catalog | spike: 2 min @50, 50→300 in 10 s, hold 2 min, back to 50, observe 5 min |
| AUTH-05, CATALOG-05, CART-04 | auth / catalog / mixed | soak: warm-up, ramp 30 s to 200, hold 30 min |
| CART-05 | rate-limit-guard | 200 + 1 requests in one rate-limit window |

Every request is tagged with its `endpoint` and its `phase`. Thresholds apply per
endpoint in the measured phase only (`hold`; for spike, the last 3 minutes of the
recovery). Warm-up, ramps and the 300-VU spike stay in the raw metrics as diagnostics.

## 1. Environment (ENV-P)

- A stack used only for this test (not shared with functional QA), one cart-service instance.
- Gateway: capacity runs need `APP_RATE_LIMIT_ENABLED=false`. Restart the gateway and check
  that it took effect. PERF-CART-05 needs the limiter back ON at its default.
- The limiter counts per **connecting address** (`RateLimitFilter.getRemoteAddr`). Behind
  Cloudflare Tunnel or a reverse proxy, all clients share the proxy's address, so
  PERF-CART-05 needs a site with no other traffic.
- Cloudflare: for the duration of the test, add a WAF custom rule "IP Source Address =
  <k6 machine IP>" → Skip (rate limiting, Bot Fight Mode). Remove it afterwards.
- Cloudflare quick tunnel (`*.trycloudflare.com`): the URL changes every time
  `cloudflared` restarts, there is no WAF dashboard, and Cloudflare limits a quick tunnel
  to about 200 in-flight requests. Only `/api` is forwarded (through the frontend proxy),
  so PERF-CART-05 needs `-e HEALTH_URL=http://localhost:8080/actuator/health`. For the
  report, write down whether the run went through the tunnel or straight to the gateway.
- Run k6 on a different machine from the stack. Keep Grafana (`infra/monitoring/`) open and
  record CPU/RAM/GC, the DB pool and Redis for the same time range.
- Record: VPS spec, container/JVM limits, number of instances, build SHA, network path
  (Cloudflare or direct), logging level.

## 2. Fixture (once per environment)

Run it **on the stack host** (it reads OTP codes from the auth-service log), against the
local gateway:

```
DEV_ADMIN_PASSWORD=... SEED_PASSWORD=... PERF_PASSWORD='<Strong1!pass>' node perf/fixtures/prepare.mjs
```

It runs `scripts/seed-demo-data`, then creates:
- 300 verified buyers `perf.buyer.0000@perf.vmarket.test` …. Spike needs 300 distinct accounts.
- ≥ 1000 ACTIVE products in 10 categories, plus PH (HIDDEN) and PD (DRAFT).
- 200 carts with 20 lines each, in two shops. The line the test changes costs 100000.

It writes `perf/fixtures/out/manifest.json` (ids, counts, expected carts, run id, git SHA;
no secrets). Copy that file to the k6 machine, or pass `-e MANIFEST=<path>`.

| Variable / flag | Default | Meaning |
|---|---|---|
| `BASE_URL` | `http://localhost:8080` | gateway (non-local needs `--allow-remote`) |
| `PERF_BUYERS`, `PERF_CARTS`, `PERF_PRODUCTS` | 300, 200, 1000 | fixture sizes |
| `BUYER_PREFIX` | `perf.buyer` | use a new prefix for a fresh set of buyers |
| `OTP_LOG_CMD` | `docker compose logs --since 30m auth-service` | where OTP codes are read |
| `--only=seed,catalog,buyers,carts` | all | e.g. `--only=carts` resets carts to the fixture state |
| `--allow-pending` | off | skip OTP (buyers stay PENDING; note it in the report) |

OTP codes are only in the log with `AUTH_EMAIL_PROVIDER=log`. With `brevo`, every OTP
request sends a real email (to an undeliverable `.test` address and uses quota), so
either switch ENV-P to `log` for the fixture or use `--allow-pending`.

Re-running is safe. The test scripts never create catalog data during a run.

## 3. Run a round

```
BASE_URL=https://<env-p> ALLOW_REMOTE=true PERF_PASSWORD=... \
  node perf/run.mjs --round=1 --tc=PERF-AUTH-01,PERF-CATALOG-01,PERF-CART-01 --tester="<your name>"
node perf/run.mjs --round=1 --tc=all --tester="<your name>"
```

Start with the three smoke cases. `--tc=all` asks before the capacity runs and again
before PERF-CART-05 (limiter ON; the script takes a token, then waits for you to restart
the gateway). Set `K6=<path>` if k6 is not on PATH. Set
`K6_PROMETHEUS_RW_SERVER_URL` to also stream metrics to Prometheus.

Evidence per test case in `perf/results/R<round>/<TC>/` (git-ignored):
`summary.json` (thresholds, p50/p90/p95/p99/max per endpoint and phase), `metrics.csv.gz`
(raw), `timeline.csv` (per-minute p95/RPS/failures), `k6.log`, `run.json` (verdict).

### Verdict

| Situation | Excel |
|---|---|
| Cart money/quantity/owner mismatch, or PH/PD visible | **Failed** (the run stops at the first mismatch) |
| Login returned 401/423 (fixture or password problem) | Pending: aborted (stops at once so accounts are not locked) |
| Unexpected failure > 10 % (safety stop) | Pending: aborted |
| An acceptance threshold crossed | **Failed** |
| PERF-CART-05 window changed between request 1 and 201 | Pending: invalid run |
| Fewer than 200 samples for an endpoint in the measured phase | Pending: inconclusive |
| Everything within thresholds | **Passed** |

The verdict covers what k6 measures. Before you finalise a soak or spike result, check
Grafana for OOM, crashes, or heap/DB/Redis growth that is never explained.

## 4. Fill the Excel report

```
node perf/report/fill-excel.mjs --round=1                     # writes perf/results/VMarket_Test_Report.R1.xlsx
node perf/report/fill-excel.mjs --round=1 --in-place          # updates docs/VMarket_Test_Report.xlsx
```

It sets Round N status, test date (dd/mm/yyyy, Asia/Bangkok) and tester on each test
case's row. The sheet's formulas recalculate when the file is opened. It also writes
`perf/results/R<n>/report.md` and `report.csv` with the actual p95, samples, failure rate,
counters and evidence paths, because the sheet has no Actual / Evidence / Defect column.
The Excel report is a local working file and is not committed.

## Safety

- Only against ENV-P or a local stack. Scripts that change data refuse a non-local
  `BASE_URL` without `ALLOW_REMOTE=true` (`--allow-remote` for the fixture).
- No orders, payments, refunds or payouts are created. The cart test only switches one
  line between quantity 1 and 2.
- Cart lines take `unitPrice` from the request, so the fixture uses each variant's real
  price. Cart results are compared with the manifest, never with the response under test.
