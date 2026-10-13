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
1. Pause all catalog writers; keep Product's read-only snapshot API available.
2. Stop all recommendation-service instances.
3. Run python manage.py seed --writers-paused --consumer-stopped.
4. Start the recommendation service, drain queued events, then resume writers.

The seed command checks that recommendation.events has no active consumer. The flags acknowledge the maintenance preconditions; it cannot itself verify that all Product writers are paused. Failed/interrupted imports are safe to rerun; seeding never deletes projection rows and preserves newer revisions. Migrate Product-owned legacy revisions first if the export returns SNAPSHOT_VERSION_MISSING.

Keep the existing durable recommendation.events topology and DLQ arguments. Bindings remain ProductCreated/Updated/Deleted, OrderPlaced, and OrderStatusChanged. Order events are validated and acknowledged without creating user history. No UserBehaviorTracked binding exists in PR-1.

Start the recommendation consumer before enabling catalog writes. Product's outbox can park unroutable events when no consumer queue is bound; re-drive those events after restoring topology.

Messages are validated and deduplicated by eventId. ACK follows the PostgreSQL transaction. Transient failures get two retries (1 then 2 seconds); malformed or exhausted messages go to recommendation.events.dead. Shutdown/disconnect leaves unsettled deliveries for redelivery. Fix the cause before manually replaying DLQ messages with their original envelope/eventId. Never delete a queue with pending messages.

## APIs

See [OpenAPI](../../docs/openapi/recommendation-service.yaml).
- GET /for-you?category_id=clothing&limit=12: popular/category fallback for guests and authenticated users.
- GET /similar/{product_id}?limit=12: category/text similarity, price proximity, then sales.
- GET /health: coarse PostgreSQL/Redis/RabbitMQ state, eligible count and last successful refresh. This is liveness; a refresh timestamp does not certify that every catalog event is synchronized.

Default limit 12; range 1–50. Unknown/repeated query parameters return 400. Arbitrary user_id and X-User-* identity are never trusted. A supplied JWT is independently verified using HS256, auth-service issuer, subject and expiry; invalid tokens return 401.

Responses use public Product field names and integer VND prices. Only catalog-visible, non-deleted, in-stock products are returned. Ineligible similarity seeds return 404; empty results return empty lists.

Vietnamese tokens are case/diacritic normalized. Similarity averages exact category match and token Jaccard overlap; ties use relative minPrice distance, soldCount, then product ID. Missing categories are not a match. This is content similarity, without a trained model.

Redis failure falls back to PostgreSQL. Cache hits recheck current visibility, stock and fields; ranking may lag a catalog update by at most 60 seconds. PostgreSQL failure returns 503. Catalog ranking currently scans the demo catalog in memory; move to bounded candidate retrieval when catalog size requires it.

## Validation and packaging

- python -m unittest discover -s tests/unit -v
- Set RECOMMENDATION_INTEGRATION=true for a dedicated migrated test database/broker queue, then python -m unittest discover -s tests/integration -v. See the test setup at the top of that file.
- python -m pip_audit -r requirements.txt
- From services: mvnw.cmd -pl api-gateway -am test (Windows) or ./mvnw -pl api-gateway -am test.
- From the repo root: scripts/check-env.cmd.
- docker build -f services/recommendation-service/Dockerfile -t vmarket-recommendation:test .
- docker run --env-file services/recommendation-service/.env -p 8101:8101 vmarket-recommendation:test (set dependency hosts to host.docker.internal when dependencies run on the host).

CI runs migrations, unit/infrastructure integration tests, dependency audit and the Docker build. Validation evidence and remaining limitations belong in VERIFICATION.md.
