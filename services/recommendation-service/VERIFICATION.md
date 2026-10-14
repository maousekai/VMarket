# PR-1 validation

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
