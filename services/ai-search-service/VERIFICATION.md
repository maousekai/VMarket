# PBL6-21 implementation verification

Recorded 2026-10-02 on Windows 11 / Docker Desktop, branch
`PBL6-21-ai-search-service`. Implemented FR-SRCH-01–05 only. These are local
verification results; remote CI and PR review are separate acceptance checks.
Run instructions and configuration are in [README.md](README.md).

## Executed checks

| Check | Observed result |
| --- | --- |
| Python offline unit checks | 13 passed; validation, malformed multipart/numeric-header limits, cancellation retains the native image slot, safe errors, LLM wire/cache/timeout fallback including non-object provider messages, pre-decode projected resize limits, revisions/tombstones, MinIO-only URLs and streamed-body cleanup |
| Real Elasticsearch 8.19 checks | 6 passed; Vietnamese/fuzzy/synonyms, suggestions, actual variant-price gaps, min-price sort, popularity ranking, nested kNN/prefilters, stale updates, durable image recovery and atomic maintenance rebuild/failure |
| Product Maven `verify` | 85 passed, none skipped; includes 7 real Mongo/Rabbit Testcontainers checks, native mixed-type `_id` export ordering, maintenance migration preserves valid revisions, snapshot authentication/version/tombstone projection and existing catalog suite |
| Gateway Maven `verify` | 37 passed, none skipped; includes a real HTTP servlet/proxy test for guest image upload, case-insensitive identity stripping, internal snapshot denial and chunked-body 413, plus rate-limit envelope |
| Shared-events Maven `verify` | 10 passed, none skipped; no event-version rollout |
| Generated system smoke | Passed with real ES/Rabbit/MinIO/CPU PyTorch; nine image vectors, read-only S3 policy denies write/list, events/current authenticated snapshot stub, text/image results, hidden/deleted exclusion, malformed-event DLQ and confirmed targeted replay |
| Docker Search build | Passed; nonroot, pinned Python base, OS security upgrades, CPU wheels, no keys or model weights in layers |
| Environment synchronization / YAML | `scripts/check-env.cmd -Quiet` passed; Compose, CI and OpenAPI YAML parsed |
| Nginx runtime syntax check | `nginx -t` passed with the repository's nginx.conf and rendered upload-limit template, using the frontend's existing `nginx:1.28-alpine` base. Initial registry timeout resolved on one retry. |
| Dependency audit | Pinned runtime requirements and CPU torch 2.14.1 / torchvision 0.29.1: no known vulnerabilities reported by pip-audit at check time |

Affected Java modules were verified separately after their final edits. Test discovery
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

The final Search image ran model/API checks with `--memory 512m --memory-swap 512m`,
network disabled, read-only root/models and a 16 MiB service-owned tmpfs. Official
weights were already cached and verified. CPU only; no CUDA libraries or GPU needed.

- Cold model load: approximately 2.03 seconds in the final boundary run.
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
