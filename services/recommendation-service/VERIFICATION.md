# PR-1 validation

Date: 2026-10-13 (Asia/Bangkok).
Branch: PBL6-24-FR-REC-01-02-04.
Scope: FR-REC-01 API/auth/fallback foundation; FR-REC-02/04 backend implementation.

## Completed checks

| Check | Result |
| --- | --- |
| Python unit tests | 10 passed |
| PostgreSQL/Redis/RabbitMQ integration tests | 5 passed |
| Gateway Maven tests | 39 passed, zero failures/errors/skips |
| Flyway migration | Fresh isolated vmarket_recommendation_test database migrated to V1 |
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

The integration suite verifies duplicates, stale revisions/tombstones, rollback of event receipts, seed reruns, active-consumer seed rejection, retry success, exhausted retry to DLQ, broker reconnection, cache visibility refresh, Redis outage fallback, and PostgreSQL failure responses.

Latest demo latency: p95 11.1 ms across 30 sequential ASGI requests, two fixture products, and local PostgreSQL/Redis. This small sample verifies the demo path; it is not a 200-user or production-capacity test.

## Isolation and limitations

Integration tests use a dedicated test database, private temporary RabbitMQ exchanges/queues, and prefixed Redis keys in database 15. HTTP catalog snapshots are contract-shaped fixtures served by a local HTTP server. The container smoke also used the test database. Temporary test/container broker topology was removed afterward; existing infrastructure remains running.

The gateway test exercises real servlet/proxy routing against an HTTP stub, verifying guest GET access, identity stripping, bearer forwarding, protected POSTs, and internal Product path blocking. A live Product-to-Recommendation-to-Gateway end-to-end flow was not run.

Windows sandbox restrictions blocked asyncio local socket pairs and Maven cache access. The successful test/migration runs used approved execution outside the sandbox. The first Docker build exposed an existing allowlist exclusion; the final build passed after allowing recommendation sources. The env checker was adjusted to recognize Java sources rather than treating a migration-only POM as a Spring runtime.

Graphify's AST update completed after running outside the sandbox to correct Windows path resolution. Python/Java symbols were refreshed. Its installed parser lacks tree_sitter_sql, so SQL graph extraction was skipped; SQL migration behavior was verified with Flyway/PostgreSQL. Documentation semantic extraction and community relabeling were not run.

FR-REC-03 consumer work, personalized ranking, upstream behavior producers, and periodic training remain outside PR-1. No production database migration, deployment, or PR merge was performed.
