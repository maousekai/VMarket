# Recommendation Service — PR-1

FastAPI on port 8101. PostgreSQL owns the catalog projection; Redis caches ranked IDs for 60 seconds. PR-1 completes FR-REC-02/04 and supplies only API/auth/fallback for FR-REC-01. Personalized ranking, UserBehaviorTracked consumption, behavior producers, and FR-REC-05 training are deferred.

## Run locally

From the repository root, start the existing infrastructure: docker compose up -d postgres redis rabbitmq.
The compose initialization creates vmarket_recommendation on a new PostgreSQL volume. For older volumes, have the database owner provision that missing database without resetting the volume.

From this directory:
1. Create a Python 3.12 virtual environment and install requirements-dev.txt.
2. Copy .env.example to .env and match the shared credentials with the root infrastructure configuration. Local PostgreSQL uses host port 5433; containers use 5432.
3. Set JAVA_HOME to a JDK 17 or newer. Run python manage.py migrate. This invokes the existing Maven wrapper and Flyway through the migration-only POM; no Java reactor module is added.
4. Run python -m uvicorn main:app --host 127.0.0.1 --port 8101 --workers 1.

Environment variables override the service .env. Required AUTH_JWT_SECRET (at least 32 UTF-8 bytes) and INTERNAL_API_KEY must match Auth and Product. Production rejects example credentials. CORS origins come from CORS_ALLOWED_ORIGINS.

The gateway already routes /api/ai/recommendations/**. Run it on port 8080 to exercise the public API. Production should expose only the gateway, with service/database traffic on the internal network.

## Catalog initialization and recovery

Product is the source of truth. Each ProductCreated/Updated/Deleted invalidation fetches the current internal snapshot using X-Internal-Api-Key. Its productVersion prevents older updates from replacing newer state or tombstones.

For an existing catalog:
1. Pause all catalog writers; keep Product's read-only snapshot API and the recommendation consumer running.
2. Run python manage.py seed --writers-paused.
3. Drain queued events, then resume writers.

The Product export uses offset pagination and explicitly requires paused writers: version checks prevent stale updates but cannot prevent page omissions caused by concurrent inserts. The flag acknowledges this precondition; it cannot itself verify that all writers are paused. The recommendation consumer may run concurrently because projection upserts preserve newer revisions. Failed/interrupted imports are safe to rerun; seeding never deletes projection rows. Migrate Product-owned legacy revisions first if the export returns SNAPSHOT_VERSION_MISSING.

Keep the existing durable recommendation.events topology and DLQ arguments, as specified in docs/event-bus.md. Bindings remain ProductCreated/Updated/Deleted, OrderPlaced, and OrderStatusChanged. Order events are acknowledged immediately without parsing their unused bodies or writing receipts; they cannot add order-validation noise to the DLQ. No UserBehaviorTracked binding exists in PR-1.

Start the recommendation consumer before enabling catalog writes. Product's outbox can park unroutable events when no consumer queue is bound; re-drive those events after restoring topology.

Product messages are validated and deduplicated by eventId. ACK follows the PostgreSQL transaction. Transient failures get two retries (1 then 2 seconds); malformed or exhausted messages go to recommendation.events.dead. Shutdown/disconnect leaves unsettled deliveries for redelivery. Health exposes rabbitmqDeadLetters, sampled every 30 seconds; a nonzero count also emits recommendation_dead_letters_pending warnings. Alert on either signal. Fix the cause before manually replaying DLQ messages with their original envelope/eventId. Never delete a queue with pending messages.

The repository supplies python manage.py cleanup but does not provision a scheduler. Register it as a daily job before deployment, as required by the checklist below, to delete receipts older than 30 days. Catalog rows/tombstones remain, so replaying older Product events cannot regress a projection. PR-2 must decide its behavior-event replay horizon before sharing this cleanup policy.

## Deployment checklist

- [ ] Apply Flyway migrations through V2 before starting the service.
- [ ] Assign an operations owner and register python manage.py cleanup once daily with the service's database environment and working directory. Record the scheduler/job definition and owner in the deployment record; cleanup is not automatic until this is done.
- [ ] Run the cleanup job once in staging, record its successful exit and removed-receipt count, and alert on subsequent job failures or missed daily executions. Confirm receipt ages remain within the 30-day retention plus daily scheduling interval.
- [ ] Configure alerts for a nonzero rabbitmqDeadLetters count or recommendation_dead_letters_pending warning and document who fixes/replays the DLQ.
- [ ] Verify DOWN/503 when PostgreSQL is unavailable and successful probes while recommendation workers are busy. Catalog statistics may lag 30 seconds; PostgreSQL readiness is checked every probe.

## APIs

See [OpenAPI](../../docs/openapi/recommendation-service.yaml).
- GET /for-you?category_id=clothing&limit=12: popular/category fallback for guests and authenticated users.
- GET /similar/{product_id}?limit=12: category/text similarity, price proximity, then sales.
- GET /health: PostgreSQL readiness (DOWN/503 when unavailable), optional Redis/RabbitMQ state, DLQ count, eligible count and last successful refresh. PostgreSQL readiness uses SELECT 1 on every probe; catalog statistics are cached for 30 seconds. The async route runs blocking checks outside FastAPI's synchronous request worker pool. A refresh timestamp does not certify that every catalog event is synchronized.

Default limit 12; range 1–50. Unknown query parameters are ignored; repeated supported parameters and identity overrides return 400. Arbitrary user_id and X-User-* identity are never trusted. A supplied JWT is independently verified using HS256, auth-service issuer, subject and expiry with the gateway's 30-second clock-skew allowance. Invalid tokens return 401; clients should refresh the token or retry without it for guest access. Framework errors use the documented envelope and status-specific codes; request validation consistently returns 400 INVALID_QUERY.

Responses retain the documented user_id/product_id envelope and public Product field names with integer VND prices. Only catalog-visible, non-deleted, in-stock alternatives are returned. A visible, non-deleted similarity seed may be sold out. Missing (including not yet projected), hidden or deleted seeds return 404; empty results return empty lists.

Vietnamese tokens are case/diacritic normalized, with seed tokens calculated once per ranking. Similarity averages category-path Jaccard overlap and text-token Jaccard overlap; sibling categories share credit for common ancestors. Ties use relative minPrice distance, soldCount, then product ID. Missing categories are not a match. Stopword/long-description tuning remains for PR-2; this is content similarity without a trained model.

Each category/seed caches top 50 IDs, then slices for limit. Cache hits recheck current visibility, stock and fields; ranking may lag a catalog update by at most 60 seconds. Cache hits bypass ranking locks. On a miss, acquire the bucket lock for at most RECOMMENDATION_DEPENDENCY_TIMEOUT_SECONDS, recheck the cache and compute only if still missing. A lock timeout returns 503 RECOMMENDATION_UNAVAILABLE; locks share miss work within the configured single worker. Redis failure falls back to PostgreSQL and suppresses cache attempts for five seconds. PostgreSQL failure returns 503.

Popularity reads use an indexed ORDER BY/LIMIT 50. Exact similarity streams only seven ranking fields in batches of 100 and keeps the best 50 candidates in memory. The iterator is explicitly closed after ranking, including exceptions, returning its cursor/connection without waiting for garbage collection. It still scans eligible candidates and transfers their full descriptions (up to 10KB); the next scaling step is persisting normalized tokens at projection time, followed by indexed candidate retrieval. Distributed cache coordination remains necessary when replica count requires it. Flyway V2 adds category-path, popularity and receipt-age indexes; run migrations before upgrading. Migration remains an explicit pre-start Maven/Flyway step; deployment-specific Flyway containers are outside this ticket.

## Validation and packaging

- python -m unittest discover -s tests/unit -v
- Set RECOMMENDATION_INTEGRATION=true for a dedicated migrated test database/broker queue, then python -m unittest discover -s tests/integration -v. See the test setup at the top of that file.
- python -m pip_audit -r requirements.txt
- From services: mvnw.cmd -pl api-gateway -am test (Windows) or ./mvnw -pl api-gateway -am test.
- From the repo root: scripts/check-env.cmd.
- docker build -f services/recommendation-service/Dockerfile -t vmarket-recommendation:test .
- docker run --env-file services/recommendation-service/.env -p 8101:8101 vmarket-recommendation:test (set dependency hosts to host.docker.internal when dependencies run on the host).

CI runs migrations, unit/infrastructure integration tests, dependency audit and the Docker build. Validation evidence and remaining limitations belong in VERIFICATION.md.
