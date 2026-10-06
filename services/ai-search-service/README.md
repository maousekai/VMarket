# AI Search — PBL6-21

FR-SRCH-01–05: Vietnamese keyword/synonym/fuzzy search, product-name suggestions,
versioned event synchronization, and CNN image search. Default embedding is CPU
PyTorch MobileNetV3 Small; the optional query-expansion LLM uses an operator-selected
cloud provider. Elasticsearch 8.19, RabbitMQ, Product Catalog and MinIO-compatible
media storage are dependencies. No recommendation service is required. FR-SRCH-06,
text-vector search and a Search frontend are outside this implementation.

Only `mobilenet_v3_small` is implemented for embeddings (576 dimensions, L2 normalized).
LLM providers use OpenAI-compatible Chat Completions; native/container Ollama is optional
for the LLM only. No cloud endpoint/model/key is supplied by default. Keyword search
works without an LLM; image search requires cached verified weights and calibration.

Contracts: [public search](../../docs/openapi/ai-search-service.yaml),
[Product snapshots](../../docs/openapi/product-search-internal.yaml).
Implementation/verification limits: [VERIFICATION.md](VERIFICATION.md).
PR implementation history: [PBL6-21 worklog](../../worklogs/PBL6-21.md).

## API behavior

Public requests go through the gateway at `http://localhost:8080`; the service listens
on port 8100. Guest access is allowed for the exact Search routes. Product internal
snapshots require `X-Internal-Api-Key` and are blocked at the public gateway.

| Method / path | Parameters and behavior |
| --- | --- |
| `GET /api/ai/search` | Required `keyword`; optional `categoryId`, `minPrice`, `maxPrice`, `sort`, `page`, `size`. Defaults: page 0, size 20, relevance sort; size 1–100 and `(page+1)*size <= 10000`. |
| `GET /api/ai/search/suggestions` | Required `keyword`; optional `categoryId`, `limit` (default 10, 1–20). Under two trimmed characters returns an empty list. Never calls the LLM. |
| `POST /api/ai/search/image` | Multipart field `file`, exactly one static JPEG/PNG/WebP; optional `categoryId`, `minPrice`, `maxPrice`, `limit` (default 20, 1–100). File <=10,000,000 bytes; body <=11,000,000 bytes; <=25,000,000 pixels. |
| `GET /api/ai/search/health` | Reports `status`, `text`, `image`, `synchronization`, `llm`. HTTP 200/`UP` means the process responds; individual features may still be unavailable/degraded. |

Unknown/repeated parameters are rejected. Prices are integer VND, with
`minPrice <= maxPrice`; filtering matches an actual active variant, including gaps
between variant prices. Category filters include descendants. Sorts are `RELEVANCE`,
`PRICE_ASC`, `PRICE_DESC`, `BEST_SELLING`, `NEWEST`; both price directions use the
minimum active variant price, and ties use product ID ascending. Hidden/deleted
products are excluded. Image results contain one hit per product, with `similarity`
in [-1,1] and `matchedImageUrl`; results below the calibrated threshold are omitted.

Errors use `{timestamp, status, error: {code, message}, path}`, with UTC ISO 8601
timestamps. Common codes: `INVALID_QUERY`/`INVALID_IMAGE` (400), `IMAGE_TOO_LARGE`
(413), `RATE_LIMITED` at the gateway or `SEARCH_BUSY` for the single image slot (429),
`SEARCH_UNAVAILABLE`/`IMAGE_SEARCH_UNAVAILABLE` (503). Nginx, gateway and Python share
the generated upload limit/error contract. Health, image search and calibration reuse
share fingerprint/validated/finite-threshold checks; `llm=configured` is not a provider probe.

## Quick checks without catalog files

Use Python 3.12 (a real installation, not the Windows Store placeholder). Commands
below run in this directory; Maven lives in `../`, Compose in the repository root.
Before infrastructure commands, copy root `.env.example` to root `.env` if needed
and set `MINIO_ROOT_USER`/`MINIO_ROOT_PASSWORD` explicitly. Compose interpolates these
required variables even when the Search profile is not selected. Keep existing credentials.

```powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements-dev.txt
.\.venv\Scripts\python.exe -m unittest discover -s tests/unit -v
# Switch to repo root; named services start only the required infrastructure:
Push-Location ../..
docker compose --profile search up -d elasticsearch minio rabbitmq
Pop-Location
.\.venv\Scripts\python.exe -m unittest discover -s tests/integration -v
.\.venv\Scripts\python.exe -m pip install -r requirements-local.txt
.\.venv\Scripts\python.exe manage.py weights
.\.venv\Scripts\python.exe -m unittest discover -s tests/model -v
.\.venv\Scripts\python.exe -m unittest discover -s tests/system -v
# Optional planned load check: same isolated resources, 5 users, 65/25/10 mix.
$env:SEARCH_LOAD_SECONDS='60'
.\.venv\Scripts\python.exe -m unittest discover -s tests/system -v
Remove-Item Env:SEARCH_LOAD_SECONDS
# Maven wrapper lives in services/:
Push-Location ..
.\mvnw.cmd -pl product-service,api-gateway -am verify
Pop-Location
```

Unit checks use fake providers/vectors, with no downloads or network access during
discovery. The separate ES checks use unique disposable indexes and real analysis,
ranking, nested kNN, revisions, image-job recovery and alias rebuilds. System checks
generate drawings, create restricted test MinIO users/buckets, use a local authenticated
HTTP snapshot stub and real Rabbit/ES/PyTorch. They mutate only their unique test
resources. They require Docker CLI access and explicit nonempty MinIO root credentials
from root `.env`; there is no credential fallback. `MINIO_TEST_CONTAINER` overrides
the default `vmarket-minio-1` container name. The system test's synthetic calibration
is confined to its disposable index. These drawings do not qualify real-product accuracy.
Local dependency ports default to ES 9200, MinIO 9000/console 9001 and Rabbit 5672.
The system test expects these local infrastructure ports. When changing
Compose host ports, adjust the test harness before running it. Model checks are opt-in,
load real CPU weights and do not need a catalog file. The full combined model test
failed under the 512 MiB container cap; an isolated maximum upload passed but filled
the cap. See verification notes before interpreting these as capacity tests.

## Run with your catalog

1. Copy `.env.example` to `.env`, set matching Product `INTERNAL_API_KEY`, MinIO
   **read-only** key, and selected LLM URL/model/key. `Settings` and `manage.py` load
   the service file; existing environment variables take priority. Root `.env` is
   Compose configuration. Do not copy seed/admin credentials into the runtime file.
   `Settings.load()` reads the file for each new application/tool invocation without
   changing process environment; environment values override file values. `Settings()`
   constructs defaults for tests. Restart the service after changing its configuration.
   There is no default cloud endpoint: set `LLM_BASE_URL`, `LLM_MODEL`, `LLM_API_KEY`
   and `ALLOW_EXTERNAL_QUERY_TEXT=true` to opt in; otherwise keyword search falls back.
2. Install `requirements-local.txt`, cache weights with `manage.py weights`, set up
   MinIO below, run Product Catalog and the gateway using their existing
   scripts, and ensure an approved shop with valid categories exists. Configure
   Product's internal key explicitly; its base YAML default differs from the root
   development key. Seed uses a real seller token through the gateway.
3. Prepare the licensed-photo fixture manifest below and private `.env.seed`.
4. Leave Search stopped; `manage.py seed` binds the durable queue **before** creating
   products, uploads immutable images and records fixture IDs for later smoke checks.
5. Pause **every** catalog writer: public mutations, inventory/review/shop/category
   event consumers, scheduled projection reconciliation, and in-flight transactions.
   Drain the Product outbox while Search's durable queue remains bound. Stop Search
   workers. Keep authenticated snapshot reads available. The maintenance flag is an
   operator assertion; the tool cannot remotely prove that every writer is paused.
6. Bootstrap and calibrate:

```powershell
.\.venv\Scripts\python.exe manage.py seed
$indexSuffix = Get-Date -Format yyyyMMddHHmmss
.\.venv\Scripts\python.exe manage.py reindex --maintenance --target "vmarket-products-v1-$indexSuffix" --calibration .local/search-fixtures.json
.\.venv\Scripts\python.exe -m uvicorn main:app --host 127.0.0.1 --port 8100 --workers 1 --no-access-log
```

7. Resume catalog writers. `manage.py smoke --base-url http://localhost:8080` checks
   recorded products/image uploads. `manage.py llm-probe` separately requires a valid,
   useful expansion from your actual selected provider within the configured budget
   (500 ms by default).

First rebuild/model changes require calibration: 10 related + 10 unrelated queries
in each of two disjoint sets, distinct image contents, plus exact-image top-one checks.
Choose the calibration cutoff by F1, then require held-out recall@5 >=80% and unrelated
false acceptance <=10%. Failed quality/count/version/image checks leave the old alias
unchanged and retain the candidate for diagnosis. Retry with a fresh target. Later
rebuilds can omit `--calibration` only when the existing encoder fingerprint matches,
calibration is explicitly validated, and its threshold is finite and in [-1,1].
Never roll an alias back after new events have been acknowledged; rebuild.
Without valid matching calibration, image search returns 503. There is no arbitrary
0.70 default or automatic catalog bootstrap during requests.

If legacy Mongo products lack `version`, perform a Product-owned maintenance startup
with `--app.search-revision-migration.enabled=true`, while other writers/reconciliation
are paused. It fills only missing/null versions with 0 before other startup runners.
Disable the flag afterward and rebuild. Search never invents product revisions.

## MinIO setup and immutable media

Compose keeps the service named `minio`, ports bound to loopback, a persistent volume
and 256 MiB trial cap. The original planned 2025 image failed scanning; the selected
prebuilt [Silo release](https://github.com/pgsty/silo/releases/tag/RELEASE.2026-08-06T00-00-00Z)
is a compatible MinIO fork, pinned by digest in Compose. Remaining image findings are
documented in verification notes and must be reviewed before deployment. Do not use
this demo stack as a clean-scan production assertion.

Set `MINIO_ROOT_USER` and `MINIO_ROOT_PASSWORD` explicitly in root `.env` before running
Compose; missing/empty values fail configuration validation. `.env.example` leaves
the password empty. Existing local credentials are preserved, with no implicit fallback.
Use the local console at `http://localhost:9001` with root `.env` administrator values.
Create bucket `vmarket-media`, a Search user and a separate fixture-upload user. Example
Search policy (no write, list or admin rights):

```json
{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":["s3:GetObject"],"Resource":["arn:aws:s3:::vmarket-media/products/*"]}]}
```

The uploader policy adds only `s3:PutObject` for the same resource. Use console/CLI
administration to bind policies; never give Search root keys. To display public product
photos, a bucket policy may grant anonymous `s3:GetObject` only for `products/*`; it must
not allow list/write or expose other media prefixes. Keep console/admin ports private.
Production operators choose strong root/user credentials and complete image review.

## Shared contract and operator tuning

[The shared contract](../../docs/search-contract.json) owns Search paths, upload/resize limits, embedding dimensions,
index prefix, pagination bounds, retry attempts, currency and shared error text. Run
`python scripts/generate-search-contract.py` from the repository root after changing it.
Python/Java constants and nginx/gateway boundary configuration are generated; CI runs
`--check` to reject drift. Update OpenAPI and acceptance evidence for contract changes.
Currency VND and 576 dimensions describe the current catalog/model, not arbitrary
runtime options; changing them requires the matching catalog/model and index rebuild.

Runtime configuration is listed in [.env.example](.env.example); use
[.env.prod.example](.env.prod.example) for production values. `Settings.load()` validates
each new application/tool configuration. Changes take effect after restart, with
environment variables overriding the service file; there is no hot reload.

| Group | Variables / defaults |
| --- | --- |
| Dependencies | `ELASTICSEARCH_URL`, `SEARCH_INDEX_ALIAS`, `PRODUCT_SERVICE_URL`, `INTERNAL_API_KEY`, `RABBITMQ_HOST/PORT/USERNAME/PASSWORD`, `EVENT_QUEUE`; optional `EVENT_EXCHANGE` defaults to `vmarket.events` |
| Media / embedding | `MINIO_ENDPOINT`, `MINIO_PUBLIC_ORIGIN`, `MINIO_BUCKET`, explicit `MINIO_ACCESS_KEY`/`MINIO_SECRET_KEY`; `EMBEDDING_MODEL=mobilenet_v3_small`, weights path and optional SHA-256 override |
| LLM | `LLM_ENABLED`, `LLM_PROVIDER`, `LLM_BASE_URL`, `LLM_MODEL`, `LLM_API_KEY`, `ALLOW_EXTERNAL_QUERY_TEXT`; default deadline 500 ms, token budget 128; format/reasoning settings below |
| Ranking | `SEARCH_SYNONYMS` JSON list; `SEARCH_NAME_BOOST=3`, `SEARCH_PHRASE_BOOST=5`, `SEARCH_SYNONYM_BOOST=0.3`, `SEARCH_SALES_WEIGHT=0.1`, `SEARCH_RATING_WEIGHT=0.04`, `SEARCH_POPULARITY_CAP=2` |
| Dependency deadlines | `SEARCH_DEPENDENCY_TIMEOUT_SECONDS=2`, `MINIO_SOCKET_TIMEOUT_SECONDS=1`, `IMAGE_DOWNLOAD_TIMEOUT_SECONDS=5` |
| Rabbit deadlines | `RABBITMQ_HEARTBEAT_SECONDS=30`, `RABBITMQ_BLOCKED_TIMEOUT_SECONDS=30`, `RABBITMQ_SOCKET_TIMEOUT_SECONDS=2`, `RABBITMQ_CONNECT_TIMEOUT_SECONDS=5` |
| Maintenance | `SEARCH_MAINTENANCE_TIMEOUT_SECONDS=5`, `WEIGHTS_DOWNLOAD_TIMEOUT_SECONDS=30` |
| Application | `APP_ENV=dev`, `CORS_ALLOWED_ORIGINS`; production rejects missing/placeholder infrastructure credentials |

Service `.env` exposes `SEARCH_SYNONYMS` (JSON list), name/phrase/synonym boosts,
sales/rating weights and popularity cap. Synonym changes require maintenance rebuild;
ranking changes require restart. Dependency/MinIO/download deadlines, Rabbit heartbeat
and socket/blocked/connection deadlines, maintenance HTTP deadlines and LLM
timeout/token budgets are also configurable; positive validated values are actually
used. Defaults preserve the 500 ms / 128-token LLM budget and current memory profile.
Rabbit's connection deadline must exceed its socket timeout; inconsistent values fail
validation rather than being silently capped. Rebuild calibration reuse uses the same
fingerprint/validated/finite-threshold checks as health and image search.
Increasing budgets requires new latency/resource measurements. Logs include operation,
exception type, upstream status and source location; URLs, credentials and payloads
are deliberately excluded.

Images use immutable `products/<content-sha256>.<jpg|jpeg|png|webp>` keys. A changed image
gets a different key. Runtime URLs must exactly match `MINIO_PUBLIC_ORIGIN`, the bucket
and this prefix; encoded/traversing/query-bearing/userinfo URLs are rejected. Downloads
use S3 `GetObject` on fixed `MINIO_ENDPOINT`, never an event-controlled HTTP host. Read
and query decoding share a single CPU slot, enforce 10 MB/static JPEG-PNG-WebP/25 MP
limits, orient EXIF, and use identical normalization. Before decoding, reject images
whose short-edge resize to 256 would exceed 1,048,576 pixels (approximately a 16:1
aspect ratio). This bounds resize memory for both query and catalog images without
changing accepted embeddings. Query uploads are discarded.

## Fixture input

Create `.local/fixtures-input.json`, extending this shape to about 20 products across
three valid categories. Include hidden products, variant-price gaps and nine-image
products. Use unique `ref` values. All paths are local; record image source/license.
Positive queries list acceptable product refs; unrelated queries use an empty list.

```json
{
  "products": [{
    "ref": "shirt-1", "keyword": "áo thun",
    "images": [{"file": "D:/fixtures/shirt.jpg", "license": "CC0; source or own photograph"}],
    "product": {"shopId": "APPROVED_SHOP_ID", "categoryId": "CATEGORY_ID", "name": "Áo thun cotton",
      "description": "Áo phông", "status": "ACTIVE", "currency": "VND",
      "variants": [{"sku": "SHIRT-M", "attributes": {"size": "M"}, "price": 100000, "stock": 10}]}
  }],
  "calibration": [{"image": "D:/fixtures/query-1.jpg", "acceptableProductRefs": ["shirt-1"]}],
  "heldOut": [{"image": "D:/fixtures/query-2.jpg", "acceptableProductRefs": ["shirt-1"]}],
  "exactImages": [{"image": "D:/fixtures/shirt.jpg", "acceptableProductRefs": ["shirt-1"]}]
}
```

Private `.env.seed` (gitignored): `SEED_API_URL=http://localhost:8080`,
`SEED_SELLER_TOKEN`, `MINIO_WRITE_ACCESS_KEY`, `MINIO_WRITE_SECRET_KEY`. The seed
uses existing authorized Product APIs, deterministic idempotency keys and records
each success in `.local/search-fixtures.json`. It never edits Mongo or creates shop
approval/auth bypasses. Its manifest is fixed for a recorded fixture set; preserve
old IDs before deliberately starting a different set.

## LLM switching and Ollama

Default `LLM_PROVIDER=openai_compatible` uses HTTPX Chat Completions. Change
`LLM_BASE_URL`, `LLM_MODEL`, `LLM_API_KEY`, optional `LLM_REASONING_EFFORT` and
`LLM_RESPONSE_FORMAT=json_object|text`, then restart. Only the validated keyword
goes to the provider when `ALLOW_EXTERNAL_QUERY_TEXT=true`. Validate the provider
using `manage.py llm-probe`; endpoints supporting this wire format may still differ
in supported token/reasoning/JSON settings. No keys/keywords/URLs in application logs.

One call at a time, 500 ms total by default, no retries, up to three validated alternative terms,
128-entry/5-minute memory cache. Missing key, disabled permission, busy provider,
timeout or bad output falls back to original text/static synonyms. Autocomplete and
indexing never call the LLM. Unready cloud configuration never starts Ollama automatically.

Optional native Ollama: run `ollama serve`, operator-pull `qwen3:0.6b`, select
`LLM_PROVIDER=ollama`, `LLM_BASE_URL=http://127.0.0.1:11434/v1/`, model name,
empty key and supported thinking-disabled request settings. For the optional container,
set `OLLAMA_IMAGE` to a reviewed official `ollama/ollama` digest and run
`docker compose -f docker-compose.yml -f docker-compose.ollama.yml --profile ollama up -d ollama`.
The overlay is absent from normal startup; it bounds parallel/model/queue/context and
RAM. Container-to-container URL is `http://ollama:11434/v1/`. Container Search reaching
a native daemon requires explicit private host reachability; never expose its admin API
through the gateway. Test Vietnamese output and total memory first. Use one daemon.

## Events, failure recovery and memory

Every Product create/update/delete notification fetches its current authenticated
snapshot. Version 0 inserts; only newer versions overwrite. Durable metadata includes
pending image intent before ACK. Pika owns ACK/NACK/heartbeats; one metadata executor
does I/O with bounded retries. Hidden/deleted versions clear vectors and cancel work;
duplicates preserve retry/progress. A delayed image completion checks version, URL,
fingerprint and ES optimistic concurrency before writing. Missing model pauses durable
image work. After three image attempts, manually reset a current visible failed job:

```powershell
.\.venv\Scripts\python.exe manage.py retry-images --product-id PRODUCT_ID
.\.venv\Scripts\python.exe manage.py replay-dead --limit 1
```

Inspect/fix failure causes before replay. Replay validates the original envelope, publishes
only to Search's own queue via the default exchange, waits for broker confirmation, then
ACKs the dead delivery. Invalid JSON/envelopes move to the durable
`EVENT_QUEUE.quarantine` queue before ACK, retaining original bytes/message properties
and adding `x-search-quarantine-reason=invalid_event`. Replay continues past them;
an original message TTL is saved as `x-search-original-expiration` and cleared from
quarantine so evidence does not expire.
`--limit` counts both replayed and quarantined deliveries. A failed/unconfirmed publish
leaves the original unacknowledged for redelivery. Inspect quarantine through restricted
broker tooling, fix the cause, and explicitly recover valid events; replay never consumes
quarantine automatically. No shared rebroadcast or purge.
Health exposes coarse text/image/synchronization/LLM states. Sync is ready only after a
broker check within five seconds observes no metadata backlog or delivery awaiting ACK,
zero outstanding metadata/image lag, and empty DLQ/quarantine queues. Startup, disconnects,
stale checks and unknown lag are degraded. Metadata completion logs retain delivery latency
without payloads; the outstanding lag resets to zero only after a confirmed drain. The broker
does not expose queued timestamps, so queued lag is unknown until delivery, never inferred
as zero. Image lag includes pending/failed work.

Build from repository root: `docker build -f services/ai-search-service/Dockerfile -t vmarket-ai-search .`.
For a container use `--memory 512m --memory-swap 512m --read-only`, a writable
`--tmpfs /app/tmp:rw,noexec,nosuid,size=16m,uid=10001,gid=10001`, model directory mounted
read-only at `/app/models`, and a private `--env-file`. Configure service DNS for
container dependencies, one worker, no reload/CUDA. Model weights are installed
explicitly from the official URL with checksum verification; requests never download them.

Keep the **entire local project** below 7,000,000,000 bytes, including other Java/AI
services and Docker/WSL overhead. Search's 512 MiB and MinIO's 256 MiB caps are tested
trial allocations with no maximum-upload headroom demonstrated; the existing ES
allowance is 1 GiB/512 MiB heap. Record whole-project
memory and preserve reservations for services not in focused tests. These checks do
not qualify the SRS 200-user target. Add replicas/new encoders/provider adapters only
after measurement; model changes require recalibration and a maintenance rebuild.

## CI and acceptance status

[Search CI](../../.github/workflows/ai-search-service.yml) separates offline unit/audit,
real Elasticsearch integration, and Docker build jobs. The repo env-consistency job
also runs `scripts/generate-search-contract.py --check`. Real model/system/load tests
are local opt-in checks; cloud credentials and fixture photos are not required by CI.

The latest local checks passed 22 unit checks and the generated pipeline (2026-10-06);
the six ES checks passed on the preceding revision.
Affected Java verification passed 85 Product, 38 Gateway and 10 shared-events checks
on the preceding revision.
Detailed evidence and remaining release gates are in [VERIFICATION.md](VERIFICATION.md).
Real-photo calibration, actual Product-mutation end-to-end smoke, the selected LLM
probe, full-stack <=7 GB / 200-user qualification and image vulnerability review remain
outstanding. Generated drawings and successful builds do not complete those gates.
