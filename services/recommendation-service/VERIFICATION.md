# PR-2 validation

Updated: 2026-10-21 (Asia/Bangkok). Branch: PBL6-24-FR-REC-01-03.
Base: origin/dev at d985504, including merged PR #33 (merge 31ab115, 2026-10-14) and PR #34.

| Check | Result |
| --- | --- |
| Python unit tests | 22 passed after PR #35 review fixes, including PR-1 regressions |
| PostgreSQL/Redis/RabbitMQ integration tests | 11 passed after PR #35 review fixes, including PR-1 regressions |
| Flyway upgrade | Isolated vmarket_recommendation_review_test upgraded V2 to V3 |
| Fresh Flyway migration | Isolated vmarket_recommendation_pr2_test migrated V1, V2 and V3 |
| Docker repository-root build | Passed; vmarket-recommendation:pr2 |
| Runtime dependency audit | No known vulnerabilities found; requirements unchanged |
| Whitespace | git diff --check passed |

The 2026-10-21 review fixes remove repeated work without changing scoring or the API contract. Peer discovery first deduplicates the caller's product IDs. On PostgreSQL 17.11, a 3,002-event fixture (1,000 repeated caller views, 1,000 repeated peer views, another peer and an unrelated user) still returns two votes per shared/recommended product. EXPLAIN ANALYZE of the actual Store query reduced total join output rows (Actual Rows times Actual Loops across join nodes) from 1,002,502 to 2,503; measured execution time was 113.81 ms before and 1.48 ms after. The regression checks both scores and a join-work bound, without a fragile timing assertion.

Personalized ranking now tokenizes each eligible candidate once and reuses its terms across all seeds. A fixture with 200 products, 20 seeds and 5,000-character descriptions fell from 4,020 to 220 tokenizations. Three local runs before/after gave median pure ranking times of 3,024.5/110.0 ms and cold ASGI times of 4,260.6/115.7 ms; the top ten results were identical. This API benchmark used in-memory PostgreSQL/Redis doubles with the real ASGI app, not live dependencies. The unit regression asserts tokenization work and ordering; existing hybrid/content-only/popularity regressions still pass. Local timings vary with machine load and do not establish production capacity. No HTTP dependency path changed; the infrastructure suite reuses its existing Product fixture server rather than adding Postman workspace or mock setup. Packaging/dependency checks in the table were completed for the initial PR-2 implementation; review changes are covered again by GitHub CI.

The new integration fixtures publish real RabbitMQ UserBehaviorTracked messages for view, add_to_cart and purchase. They verify history before catalog arrival, catalog synchronization from ProductCreated fixture messages, consumer restart/replay deduplication, one vote per peer despite repeated views, authenticated content-to-hybrid ranking changes, cold-start/category fallback, JWT/guest isolation and no query/header identity overrides. Product snapshot HTTP responses are served by the existing fixture server, not a running Product service.

Redis checks cover user-scoped invalidation, a bounded scan, current purchase exclusion despite stale cached IDs after Redis invalidation fails, Redis read failure, and collaboration propagating after simulated cache expiry (Redis EXPIRE 0 on isolated test keys). SQL checks verify transaction rollback removes the receipt when history insertion fails and the maintenance cleanup preserves old behavior receipts. A pure ranking fixture verifies normalization and the equal hybrid blend, content-only ranking, all-zero fallback, purchase exclusion and popularity fill.

The initial integration fixture incorrectly expected a limit=1 entry to stay warm after a purchase invalidated that user's entries. The fixture now warms the same limit after invalidation before checking cross-user TTL staleness; the complete rerun passed. Latest guest-path demo p95 was 10.2 ms over 30 sequential ASGI requests and two fixture products with local PostgreSQL/Redis. This is not a personalized load test or a 200-user capacity claim.

Scope is FR-REC-01 ranking logic plus FR-REC-03 consumer behavior. FR-REC-02/04 remain regression-verified. Real behavior producers, frontend tracking and FR-REC-05 training/model versions remain absent. Behavior history/receipts are intentionally retained indefinitely pending a coordinated retention policy. Exact personalization scans candidate ranking fields and compares them with eligible history seeds; large-catalog/history scaling remains unverified.

No dependencies, gateway, frontend, Cart/Order producers, shared order schemas or infrastructure definitions changed. Existing recommendation CI discovers the added tests and runs all migrations; event-bus.md now triggers this workflow too. No local gateway/frontend suite, live Product/Gateway end-to-end, production migration or deployment was performed for PR-2. Infrastructure started for verification is restored to its prior stopped state. Publication, CI and review status are tracked in the GitHub pull request; local plans retain the task's requirement limits.

# PR-1 validation (historical)

Updated: 2026-10-14 (Asia/Bangkok).
Branch: PBL6-24-FR-REC-01-02-04.
Scope: FR-REC-01 API/auth/fallback foundation; FR-REC-02/04 backend implementation.

## Completed checks

| Check | Result |
| --- | --- |
| Python unit tests | 18 passed after the second review fixes |
| PostgreSQL/Redis/RabbitMQ integration tests | 7 passed after the second review fixes |
| Gateway Maven tests | 39 passed, zero failures/errors/skips |
| Flyway migration | Existing isolated test database upgraded V1 to V2; fresh vmarket_recommendation_review_test database migrated V1 and V2 |
| Runtime dependency audit | No known vulnerabilities found |
| Repository environment consistency | Passed for 13 dev/prod service examples |
| Docker repository-root build | Passed; vmarket-recommendation:pr1 |
| Container HTTP smoke | Health reported PostgreSQL, Redis and RabbitMQ ready; fallback/similarity responses matched the fixture catalog |
| Container user | UID/GID 10001 |
| Working-tree whitespace | git diff --check passed |

Python commands, from services/recommendation-service:
- .venv/Scripts/python.exe -m unittest discover -s tests/unit -v
- Set DB_NAME=vmarket_recommendation_test and RECOMMENDATION_INTEGRATION=true, then .venv/Scripts/python.exe -m unittest discover -s tests/integration -v.
- .venv/Scripts/python.exe -m pip_audit -r requirements.txt

Maven commands use JAVA_HOME pointing to JDK 17:
- python manage.py migrate with DB_NAME=vmarket_recommendation_test.
- From services: mvnw.cmd -pl api-gateway -am test.

The integration suite verifies duplicates, stale revisions/tombstones, rollback of event receipts, seed reruns with an active consumer, retry success, exhausted retry to DLQ, broker reconnection, DLQ monitoring on the pika thread, cache visibility refresh, Redis outage fallback, and PostgreSQL API/health failure responses. Added regressions exercise ancestor categories, sold-out seeds, seven-field ranking projections, top-50 limits and 30-day receipt cleanup without stale replay overwrites. The second review adds real pool-return checks after ranking failures with retained tracebacks. Unit tests cover concurrent shared misses, held-lock cache hits, bounded miss timeouts, explicit iterator closure, health under saturated synchronous workers, cached catalog statistics with live PostgreSQL checks, Redis backoff, sibling category similarity, clock skew and status-specific framework errors. Request validation consistently returns 400 INVALID_QUERY.

Latest demo latency: p95 9.4 ms across 30 sequential ASGI requests, two fixture products, and local PostgreSQL/Redis. This small sample verifies the demo path; it is not a 200-user or production-capacity test. Gateway tests, dependency audit, environment consistency and container smoke above were completed for the initial implementation; gateway/config/dependencies/packaging were unchanged by the review fixes. The initial 2026-10-14 infrastructure run began before RabbitMQ was ready; all seven tests passed after confirming dependency health and rerunning.

## Isolation and limitations

Integration tests use a dedicated test database, private temporary RabbitMQ exchanges/queues, and prefixed Redis keys in database 15. HTTP catalog snapshots are contract-shaped fixtures served by a local HTTP server. The container smoke also used the test database. Temporary test/container broker topology was removed afterward; infrastructure containers started for review verification are restored to their prior stopped state.

The gateway test exercises real servlet/proxy routing against an HTTP stub, verifying guest GET access, identity stripping, bearer forwarding, protected POSTs, and internal Product path blocking. A live Product-to-Recommendation-to-Gateway end-to-end flow was not run.

Windows sandbox restrictions blocked asyncio local socket pairs and Maven cache access. The successful test/migration runs used approved execution outside the sandbox. The first Docker build exposed an existing allowlist exclusion; the final build passed after allowing recommendation sources. The env checker was adjusted to recognize Java sources rather than treating a migration-only POM as a Spring runtime.

Graphify's AST update completed after running outside the sandbox to correct Windows path resolution. Python/Java symbols were refreshed. Its installed parser lacks tree_sitter_sql, so SQL graph extraction was skipped; SQL migration behavior was verified with Flyway/PostgreSQL. Documentation semantic extraction and community relabeling were not run.

FR-REC-03 consumer work, personalized ranking, upstream behavior producers, and periodic training remain outside PR-1. No production database migration, deployment, or PR merge was performed.

Review decisions: retain documented order bindings but ACK without receipts; retain the writers-paused safeguard required by Product's offset export while allowing an active recommendation consumer; retain the existing response field names and invalid-token rejection, with 30-second leeway and client recovery documented. Migration packaging stays with the repository's explicit Maven/Flyway step. Receipt cleanup is a required deployment checklist item with an owner, daily schedule, staging run and failure/missed-run monitoring; the scheduler and external DLQ alerts are not provisioned here. Exact similarity still scans eligible ranking fields including full descriptions; persisting normalized tokens is the next scaling step. Shared-miss locks cover one process, are skipped on hits and have a dependency-timeout limit on misses. Health runs blocking checks outside the synchronous request worker pool, with live SELECT 1 readiness and a nonblocking 30-second statistics refresh.
