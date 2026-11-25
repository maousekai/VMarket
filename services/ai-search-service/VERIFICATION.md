# PBL6-21 implementation verification

## Main Compose integration — 2026-11-09

Elasticsearch, `ai-search-service`, its private `ai-search-text` component and the
one-shot `ai-search-init` now belong to **`docker-compose.yml`**, without a Search
profile or overlay. The obsolete `docker-compose.search.yml` was removed. Gateway
routing uses Compose DNS, `http://ai-search-service:8100`. Model mounts remain
read-only; resource/no-swap limits are retained. The private encoder has no host port.
Initial catch-up creates an index/alias only on an empty ES volume, binds durable
events before exporting Product snapshots, and never supplies synthetic calibration.
Existing index schema and calibration are preserved on later catch-up runs.

Started only the main Compose services needed for Search: Elasticsearch, RabbitMQ,
MongoDB/replica initialization, MinIO, Product, Search init/API/text and gateway.
Existing named volumes were reused; stale container network references were repaired
by recreating those containers, without deleting stored data. No temporary replacement
Elasticsearch, broker or Product HTTP stub was used. Search has a separate scoped
MinIO `s3:GetObject` credential in its ignored service `.env`; no root credential is
passed to Search. The existing 26 Product snapshots were synchronized successfully.

Fresh checks on this integration:

- **35 offline tests passed**, including main-Compose wiring, empty-volume initialization
  without invented calibration, and preservation of existing index/model metadata.
- **17 Elasticsearch integration tests passed against the main Compose ES container**,
  using disposable indexes and preserving the active catalog alias.
- **Real main-stack workflow passed inside the existing Search container:** reserved
  one existing product unit via Product's authenticated inventory API, observed the
  actual Mongo transactional outbox/RabbitMQ update in Search and through guest gateway
  search, then released the reservation in `finally` and verified original stock restored.
  Product revisions advanced **2 → 3 → 4**; synchronization took approximately
  **601 ms** on reserve and **1,019 ms** on release. Unchanged text vectors were preserved.
  No order, seller account, shop or fake product was created. A released reservation
  remains as the normal audit record.
- Native text probing returned a valid 384-dimensional vector. Internal Product snapshot
  and embedding routes remained blocked at the gateway. The media reader was denied
  bucket listing. The real catalog is available through keyword Search; missing validated
  image/text calibration is reported honestly as unavailable.

Reproduce the workflow from PowerShell at the repository root:

```powershell
docker compose up -d --build ai-search-service api-gateway
Get-Content -Raw services/ai-search-service/tests/system/test_main_compose.py | docker compose exec -T -e SEARCH_MAIN_COMPOSE_MUTATION=true ai-search-service python -
```

This integration does not override the semantic quality, image calibration or 200-user
capacity gates recorded below. Main services were left running for use; unrelated
services and monitoring remained stopped.

## FR-SRCH-06 implementation — 2026-11-09

FR-SRCH-06 is implemented inside AI Search, on the existing public Search endpoint.
The private native text companion uses the same repository/image and has no public
gateway route. **Functional integration passes; deployment acceptance does not.**

| Check | Result |
| --- | --- |
| Offline Python suite | 32 passed in Docker with networking disabled and a 512 MiB/no-swap cap. Includes malformed/overflowing vectors, fingerprints, cloud opt-in, provider failure/cache/timeout, private endpoint auth/input bounds, expired requests, HTTP disconnects retaining the native slot, partial ES results, and post-calibration revision checks. |
| Elasticsearch 8.19 integration | 17 passed. Legacy strict writes and explicit schema-1-to-schema-2 rebuild, semantic/lexical union, exact totals, all sorts/pagination, real-variant price gaps/category descendants, tombstones, durable retries, overload without spending attempts, interleaved metadata/image/text completions and failed rebuild alias preservation. |
| Gateway Maven tests | 64 passed, zero skipped: `services/mvnw.cmd -pl api-gateway -am test`. No Java source changes. |
| Focused live system smoke | Passed with real native E5 inference, Elasticsearch, RabbitMQ, Search API and cached gateway image. An authenticated HTTP Product snapshot stub emits existing Product events; actual Product/Mongo mutations are not covered by this smoke. Validates asynchronous vector indexing, text update/hide, public search/filtering, image upload, guest gateway routing and lexical fallback on invalid semantic calibration. |
| Maximum image alongside text | Passed: 5000×5000 RGBA PNG padded to exactly 10,000,000 bytes through the real API alongside ten native text inferences. No MinIO needed because disposable catalog snapshots have no images. Existing image integration covers vector retrieval separately. |
| Local Postman collection | 10 assertions passed through the gateway on the final packaged image: guest routing, fixture hybrid union, price gap, pagination, shared validation envelope and blocked internal routes. Run `tests/postman/semantic-search.postman_collection.json` with `--reporters cli --no-report-events`; no account or cloud publication required. |
| Build/configuration | Final Search image built successfully; Compose overlay validation and generated Search contract check passed. Code graph refreshed with `graphify update . --no-cluster`. |

Only explicitly named disposable test containers were started: ES, RabbitMQ, native
text, Search API, gateway, plus short-lived test runners. PostgreSQL, MongoDB, Redis,
MinIO, Ollama, frontend, recommendation and unrelated business services stayed off.
Model files were downloaded only by the explicit maintenance command, pinned to
revision `614241f622f53c4eeff9890bdc4f31cfecc418b3`, checked against Hugging Face
artifact hashes, and mounted read-only for runtime. The native container warmed
inference before readiness and ran with offline model loading, one CPU and 1 GiB/no swap.

The first disposable ES startup at the existing 1 GiB cap exited 137. Repeating at
the **same cap**, with a 384 MiB heap, one declared processor, ML disabled and GeoIP
downloads disabled, passed the suite. These settings are in the opt-in Search overlay
and Search CI. ES still runs close to its cap; no extra allocation was authorized.
On the final packaged-image rerun, measured native peak was **762,793,984 bytes** (71.0% of 1 GiB),
Search API peak **518,574,080 bytes**, and both reported zero OOM/OOM-kill events.
The maximum image leaves little API memory headroom. Focused service consumption
does not demonstrate the entire project's <7,000,000,000-byte limit including WSL overhead.

The supplied small Vietnamese fixture set **failed real-model quality gates**.
Calibration selected threshold 0.8286672 without using held-out outcomes. Held-out
hybrid Recall@5 was **0.70** (required ≥0.80), recall on lexical-miss queries **0.5714**
(required ≥0.80), and unrelated false acceptance **0.70** (required ≤0.10).
The held-out lexical baseline Recall@5 was 0.30. Hybrid improved retrieval but did
not meet acceptance. The lexical branch can itself admit unrelated fuzzy matches;
the hybrid union preserves existing keyword behavior. Synthetic calibration was used
only in disposable integration indexes and must never be copied into a deployment.
`text-quality` fails and maintenance rebuild preserves the active alias when quality fails.

Two short **200-user** diagnostic probes used ten fixture products, all text-search
requests, one-second think time and a catalog text mutation every two seconds.
Each phase launched requests for five seconds and waited for in-flight requests:

| Query-cache phase | Requests / HTTP 200 | p95 | Maximum | Completed semantic searches |
| --- | --- | --- | --- | --- |
| Bypassed with distinct, semantically equivalent spacing | 313 / 313 | 7.163 s | 8.121 s | 297/313 = 94.89% |
| Warm | 369 / 369 | 6.539 s | 7.336 s | 369/369 = 100% |

These diagnostic probes preceded the final deadline/disconnect and metadata-race fixes;
they establish a capacity failure, not a final-image capacity pass. Latency includes client/gateway queuing; HTTPX per-operation timeouts are not an
end-to-end load deadline. Both phases **failed** the ≤1-second requirement; uncached
semantic execution also fell below 95%. Successful lexical fallbacks do not qualify
semantic capacity. This is a focused short probe, not sustained full-stack/endpoint-mix
qualification. Resource caps, model, quality gates and provider selection were not
automatically relaxed. Full production catalog/200-user qualification and real Product
mutation end-to-end checks remain outstanding; deployment stays gated.

The older evidence below concerns FR-SRCH-01–05 and is retained as historical context.

Recorded 2026-10-02 on Windows 11 / Docker Desktop, branch
`PBL6-21-ai-search-service`. Implemented FR-SRCH-01–05 only. These are local
verification results; remote CI and PR review are separate acceptance checks.
Run instructions and configuration are in [README.md](README.md).

## PR review fixes — 2026-10-06

- 22 offline unit checks passed, including malformed-head DLQ replay, bounded
  quarantine/replay with original bytes/properties, confirmation failure without ACK,
  and health degradation for backlog, deliveries awaiting ACK, outstanding lag,
  stale broker checks, disconnects and dead/quarantine queues.
- Generated system smoke passed against real ES/Rabbit/MinIO/CPU PyTorch:
  malformed head quarantined, following valid event indexed, original message ID
  and bytes retained, TTL saved in a header and cleared to retain quarantine evidence.
- OpenAPI YAML parsing, generated-contract consistency and diff whitespace checks passed.
- Earlier verification below remains historical; no new real-photo quality,
  whole-stack memory or cloud-provider acceptance claim.

## Executed checks

| Check | Observed result |
| --- | --- |
| Python offline unit checks | 18 passed; includes fresh configuration/file/environment precedence, configurable ranking/Rabbit timeouts, required MinIO credentials before client construction, secret-safe diagnostic context, identical health/image/rebuild calibration readiness, malformed multipart/numeric-header limits, cancellation retains the image slot, LLM fallback, projected resize limits, revisions/tombstones and MinIO streamed-body cleanup |
| Real Elasticsearch 8.19 checks | 6 passed; Vietnamese/fuzzy/synonyms, suggestions, actual variant-price gaps, min-price sort, popularity ranking, nested kNN/prefilters, stale updates, durable image recovery and atomic maintenance rebuild/failure |
| Product Maven `verify` | 85 passed, none skipped; includes 7 real Mongo/Rabbit Testcontainers checks, native mixed-type `_id` export ordering, maintenance migration preserves valid revisions, snapshot authentication/version/tombstone projection and existing catalog suite |
| Gateway Maven `verify` | 38 passed, none skipped; includes a real HTTP servlet/proxy test for guest image upload, case-insensitive identity stripping, internal snapshot denial and chunked-body 413, plus a shared escaped rate-limit envelope for Search and other routes |
| Shared-events Maven `verify` | 10 passed, none skipped; no event-version rollout |
| Generated system smoke | Passed with real ES/Rabbit/MinIO/CPU PyTorch; nine image vectors, read-only S3 policy denies write/list, events/current authenticated snapshot stub, text/image results, hidden/deleted exclusion, malformed-event DLQ and confirmed targeted replay |
| Docker Search build | Passed; nonroot, pinned Python base, OS security upgrades, CPU wheels, no keys or model weights in layers |
| Environment synchronization / YAML | `scripts/check-env.cmd -Quiet`, generated Search contract `--check`, and YAML parse passed; an empty MinIO root password is explicitly rejected by Compose |
| Nginx runtime syntax check | `nginx -t` passed with the repository's nginx.conf and rendered upload-limit template, using the frontend's existing `nginx:1.28-alpine` base. Initial registry timeout resolved on one retry. |
| Dependency audit | Pinned runtime requirements and CPU torch 2.14.1 / torchvision 0.29.1: no known vulnerabilities reported by pip-audit at check time |

Affected Java modules, Python unit/model/ES and generated system smoke were rerun
after the configuration/duplication refactor. The earlier ES attempt failed because
the server was stopped; the rerun passed after starting the local infrastructure.
Test discovery
does not start infrastructure/download weights/call paid APIs. The optional model and
system commands explicitly opt into those cached weights/local dependencies.

## Generated-data load check

Executed the optional `SEARCH_LOAD_SECONDS=60` system check: 5 concurrent users,
1-second think time, target 65% keyword / 25% suggestions / 10% image; current
snapshot revisions change every 5 seconds and publish real Rabbit notifications.
294 requests completed, with zero non-200 responses. The final observed metadata lag
was 115 ms; ordinary smoke updates also completed well within 60 seconds.

| Endpoint | Requests | p95 | Maximum |
| --- | ---: | ---: | ---: |
| Keyword | 191 | 16 ms | 2,078 ms |
| Suggestions | 73 | 16 ms | 2,078 ms |
| Image | 30 | 32 ms | 78 ms |

p95 met the quick-test targets; the keyword/suggestion maxima exceeded them. This
is an ASGI TestClient check with real dependencies, one generated product, nine
identical drawing images, an authenticated Product snapshot stub and **LLM disabled**.
It does not qualify actual catalog scale, browser/gateway end-to-end performance,
cloud-provider latency, real-photo retrieval quality or the separate SRS 200-user target.
The generated index's synthetic threshold is disposable and never written to the
runtime alias. Report: gitignored `.local/generated-load-report.json`.

## Resource boundary checks

The original Search image ran model/API checks with `--memory 512m --memory-swap 512m`,
network disabled, read-only root/models and a 16 MiB service-owned tmpfs. Official
weights were already cached and verified. CPU only; no CUDA libraries or GPU needed.

- Cold model load: approximately 2.03 seconds in that original boundary run.
- 576-dimensional normalized embeddings: 256px JPEG ~11 ms; 5000x5000 JPEG ~119 ms.
- A **25 MP RGBA PNG padded to exactly 10,000,000 bytes** passed the full image handler.
  The same file plus one byte returned 413. Mock ES response isolates upload/decode
  memory; the separate system test uses real ES.
- That combined maximum test reached cgroup peak **536,870,912 bytes**, the entire
  512 MiB cap, without OOM or swap. Process high-water RSS was 642,863,104 bytes;
  RSS includes shared mappings and is not the cgroup charge. Do not describe this
  result as spare memory or claim RSS remained under 512 MiB.
- Focused running infrastructure sample: MinIO ~53.7 MiB / 256 MiB cap, Rabbit
  ~154.9 MiB / 512 MiB cap, ES ~1014 MiB / 1 GiB cap. ES and maximum Search images
  have little headroom. These observations do not authorize increasing allocations.

After the refactor, the native Windows model/full-handler suite passed. Repeating
its combined fixture-generation/model/API sequence inside the rebuilt 512 MiB
container stalled under memory pressure and exited unsuccessfully; automatic
container removal prevented confirming whether that run was OOM-killed. A separate
run prepared the fixture outside the service container, then loaded the real CPU
encoder and submitted the same 25 MP / 10 MB RGBA upload through the full handler.
It returned 200 in the rebuilt offline, read-only 512 MiB container, exited 0 with
`OOMKilled=false`, and again reached the entire **536,870,912-byte cgroup cap**.
This verifies an isolated maximum request, not spare capacity or the combined
test's success. There is no headroom claim for concurrent background image work.

The **whole project <=7,000,000,000-byte ceiling is not yet demonstrated**: other
Java/AI services and Docker/WSL overhead were not all running. Preserve their
reservations, measure the complete stack, and qualify Search with simultaneous
background image work before calling this deployment ready. Optional Ollama remains
off; it must fit the same total ceiling and pass a separate provider probe.

## Image vulnerability findings: release review outstanding

The original planned `coollabsio/minio` 2025 image reported 27 critical / 88 high
findings and was rejected. Compose now pins the compatible prebuilt
[Silo August 6 release](https://github.com/pgsty/silo/releases/tag/RELEASE.2026-08-06T00-00-00Z)
by digest, without building MinIO from source. Its saved critical/high scan has
**33 result entries across 30 unique CVEs (4 critical, 26 high)**. Included libraries
cover AMQP, Go runtime/crypto and other packages; turning off unused notifications
does not establish that every finding is unreachable. This image is for the local
demo pending a patched compatible image or an explicitly reviewed exception.

The Search image's earlier dependency-identical scan has **2 high / 0 critical**
findings after available OS updates:

| Finding | Current assessment |
| --- | --- |
| [CVE-2026-85091](https://security-tracker.debian.org/tracker/CVE-2026-85091) — zlib | Reported gzip writing path is not explicitly used by the service; image decoding still links native libraries. Reachability/exception review pending. |
| [CVE-2026-102010](https://security-tracker.debian.org/tracker/CVE-2026-102010) — libstdc++ | Reported PBDS heap operation is not explicitly called by Python code; PyTorch native dependencies require review. No approved exception. |

Saved local reports: `.local/minio-scan.sarif`, `.local/silo-scan.sarif`,
`.local/search-image-scan.sarif`. The final rebuild changed Python application code
only and reused the same base/package layers. A fresh Docker Scout rescan was
**rejected by automatic approval review** because it may export private image
package/SBOM metadata to Docker's service; that rescan requires explicit approval.
Existing results remain recorded, and no scan or reviewed-exception claim is inferred.

Do not treat passing functional checks as release approval. Reachable critical/high
findings need a fix or a documented reviewer-approved exception before release.

## Remaining acceptance evidence

The user currently has no product-photo fixtures; implementation continued with
generated isolated data. The following remain explicit acceptance work:

1. Supply licensed real product photos, approved test shop/category IDs and private
   test seller/MinIO write credentials, then run the documented seed/reindex/smoke
   path through actual Product mutations. Test those mutations end-to-end; the
   independent Product and generated pipeline tests are not a claim that this ran.
2. Calibrate the real encoder with disjoint related/unrelated queries and pass the
   held-out/exact-image gates. First runtime image bootstrap intentionally refuses
   to bypass calibration. If MobileNet quality fails, record the result before
   selecting a better model/rebuilding; do not assume a 0.70 cutoff.
3. Configure the chosen cloud model/key/text permission and run `manage.py llm-probe`.
   Mock-provider tests validate the protocol and fallback only. No paid API call
   or real cloud expansion occurred. Ollama was not selected or started.
4. Measure the full project memory and representative catalog/provider load within
   7 GB; complete image security review and teammate PR review/merge into `dev`.

Until those pass, this is a tested local implementation, not a claim that the
ticket's complete real-data/PR-merge DoD has been met.
