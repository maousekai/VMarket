# AI Search — PBL6-21

FR-SRCH-01–05: Vietnamese keyword/synonym/fuzzy search, product-name suggestions,
versioned event synchronization, and CNN image search. Default: CPU PyTorch
MobileNetV3 Small + an optional configured cloud LLM. Elasticsearch 8.19 and
MinIO-compatible storage are infrastructure. No recommendation service is required.

Contracts: [public search](../../docs/openapi/ai-search-service.yaml),
[Product snapshots](../../docs/openapi/product-search-internal.yaml).
Implementation/verification limits: [VERIFICATION.md](VERIFICATION.md).

## Quick checks without catalog files

Use Python 3.12 (a real installation, not the Windows Store placeholder). Commands
below run in this directory; Maven lives in `../`, Compose in the repository root.

```powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements-dev.txt
.\.venv\Scripts\python.exe -m unittest discover -s tests/unit -v
# From repo root; named services avoid starting unrelated business containers:
docker compose --profile search up -d elasticsearch minio rabbitmq
# Back here:
.\.venv\Scripts\python.exe -m unittest discover -s tests/integration -v
.\.venv\Scripts\python.exe -m pip install -r requirements-local.txt
.\.venv\Scripts\python.exe manage.py weights
.\.venv\Scripts\python.exe -m unittest discover -s tests/model -v
.\.venv\Scripts\python.exe -m unittest discover -s tests/system -v
# Optional planned load check: same isolated resources, 5 users, 65/25/10 mix.
$env:SEARCH_LOAD_SECONDS='60'
.\.venv\Scripts\python.exe -m unittest discover -s tests/system -v
Remove-Item Env:SEARCH_LOAD_SECONDS
# From services/:
.\mvnw.cmd -pl product-service,api-gateway -am verify
```

Unit checks use fake providers/vectors, with no downloads or network access during
discovery. The separate ES checks use unique disposable indexes and real analysis,
ranking, nested kNN, revisions, image-job recovery and alias rebuilds. System checks
generate drawings, create restricted test MinIO users/buckets, use a local authenticated
HTTP snapshot stub and real Rabbit/ES/PyTorch. They mutate only their unique test
resources. They require Docker CLI access and the existing MinIO root credentials
from root `.env` (development defaults when absent); `MINIO_TEST_CONTAINER` overrides
the default `vmarket-minio-1` container name. The system test's synthetic calibration
is confined to its disposable index. These drawings do not qualify real-product accuracy.

## Run with your catalog

1. Copy `.env.example` to `.env`, set matching Product `INTERNAL_API_KEY`, MinIO
   **read-only** key, and selected LLM URL/model/key. `Settings` and `manage.py` load
   the service file; existing environment variables take priority. Root `.env` is
   Compose configuration. Do not copy seed/admin credentials into the runtime file.
2. Set up MinIO below, run Product Catalog and the gateway using their existing
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
.\.venv\Scripts\python.exe -m uvicorn main:app --host 127.0.0.1 --port 8100 --workers 1 --env-file .env --no-access-log
```

7. Resume catalog writers. `manage.py smoke --base-url http://localhost:8080` checks
   recorded products/image uploads. `manage.py llm-probe` separately requires a valid,
   useful expansion from your actual selected provider within the 500 ms budget.

First rebuild/model changes require calibration: 10 related + 10 unrelated queries
in each of two disjoint sets, distinct image contents, plus exact-image top-one checks.
Choose the calibration cutoff by F1, then require held-out recall@5 >=80% and unrelated
false acceptance <=10%. Failed quality/count/version/image checks leave the old alias
unchanged and retain the candidate for diagnosis. Retry with a fresh target. Later
rebuilds can omit `--calibration` only when the existing validated encoder fingerprint
matches. Never roll an alias back after new events have been acknowledged; rebuild.
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
is a maintained compatible MinIO fork, pinned by digest. Remaining image findings are
documented in verification notes and must be reviewed before deployment. Do not use
this demo stack as a clean-scan production assertion.

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

One call at a time, 500 ms total, no retries, up to three validated alternative terms,
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
ACKs the dead delivery. Invalid messages remain on the DLQ; no shared rebroadcast or purge.
Health exposes coarse text/image/synchronization/LLM states; metadata logs expose lag
without payloads. Image lag includes pending/failed work, and DLQ presence degrades sync.

Build from repository root: `docker build -f services/ai-search-service/Dockerfile -t vmarket-ai-search .`.
For a container use `--memory 512m --memory-swap 512m --read-only`, a writable
`--tmpfs /app/tmp:rw,noexec,nosuid,size=16m,uid=10001,gid=10001`, model directory mounted
read-only at `/app/models`, and a private `--env-file`. Configure service DNS for
container dependencies, one worker, no reload/CUDA. Model weights are installed
explicitly from the official URL with checksum verification; requests never download them.

Keep the **entire local project** below 7,000,000,000 bytes, including other Java/AI
services and Docker/WSL overhead. Search's 512 MiB and MinIO's 256 MiB caps are tested
trial allocations; the existing ES allowance is 1 GiB/512 MiB heap. Record whole-project
memory and preserve reservations for services not in focused tests. These checks do
not qualify the SRS 200-user target. Add replicas/new encoders/provider adapters only
after measurement; model changes require recalibration and a maintenance rebuild.
