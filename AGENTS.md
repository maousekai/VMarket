# AGENTS.md — VMarket

## Architecture

Microservices e-commerce platform. 12 Spring Boot services (Java 17, Maven multi-module) + 3 FastAPI AI services (Python) + 1 shared-events library + React frontend.

- **API Gateway** (`api-gateway`, port 8080) — Spring Cloud Gateway, JWT verification, routing
- **Database-per-service** — each service owns its PostgreSQL/MongoDB/Redis database
- **Event bus** — async via RabbitMQ; shared event definitions in `services/shared-events/`
- **Docker Compose** — infrastructure only (Postgres/Mongo/Redis/RabbitMQ), not business services

| Service | Port | Database | Stack |
|---|---|---|---|
| api-gateway | 8080 | — | Spring Cloud Gateway |
| auth-service | 8081 | PostgreSQL | Spring Boot + Security |
| user-service | 8082 | PostgreSQL | Spring Boot |
| shop-service | 8083 | PostgreSQL | Spring Boot |
| product-service | 8084 | MongoDB | Spring Boot |
| cart-service | 8085 | Redis | Spring Boot |
| order-service | 8086 | PostgreSQL | Spring Boot |
| payment-service | 8087 | PostgreSQL | Spring Boot |
| delivery-service | 8088 | PostgreSQL | Spring Boot |
| review-service | 8089 | PostgreSQL | Spring Boot |
| notification-service | 8090 | MongoDB | Spring Boot |
| ai-search-service | 8100 | Elasticsearch | FastAPI + CNN |
| recommendation-service | 8101 | PostgreSQL + Redis | FastAPI |
| chatbot-service | 8102 | MongoDB | FastAPI + RAG |

## Quick Commands

```bash
# Infrastructure (from repo root)
docker compose up -d                    # Postgres + Mongo + Redis + RabbitMQ (~1.5GB)
docker compose --profile search up -d   # + Elasticsearch for AI Search
docker compose down -v                  # tear down

# Backend (from services/ directory — mvnw is HERE, not at repo root)
cd services
.\mvnw.cmd test                        # test all modules (Windows)
.\mvnw.cmd clean package -DskipTests   # build all (Windows)
# ./mvnw test                          # macOS/Linux
# ./mvnw clean package -DskipTests     # macOS/Linux

# Single service
cd services/<service-name>
..\mvnw.cmd spring-boot:run            # Windows
# ../mvnw spring-boot:run              # macOS/Linux

# Frontend
cd frontend
npm install && npm run dev             # http://localhost:5173

# Convenience scripts (Windows)
scripts\vmarket.cmd core               # gateway + auth + user
scripts\vmarket.cmd service order-service  # any single service
scripts\vmarket.cmd fe                 # frontend
scripts\vmarket.cmd build              # build all backend
scripts\vmarket.cmd stop               # kill Java processes
```

## Key Gotchas

- **Maven Wrapper location**: `mvnw`/`mvnw.cmd` are in `services/`, not repo root. Run all Maven commands from `services/`.
- **Docker build context is repo root**: `docker build -f services/<svc>/Dockerfile .` — Dockerfile needs `services/pom.xml`.
- **Dual .env system**: Root `.env` is for `docker compose up`. Per-service `services/<svc>/.env` is for standalone `docker run` / IDE. They are NOT interchangeable. Run `scripts\check-env.cmd` to verify they stay in sync.
- **Port conflicts**: Postgres publishes to host on 5433 (not 5432), Mongo on 27018 (not 27017) — avoids clashing with local installs.
- **CI is per-service**: Each service has its own workflow file. Editing `services/auth-service/` only runs auth CI. Editing `services/pom.xml` runs ALL service CIs (parent POM change).
- **Frontend pre-commit hook**: Husky runs `lint-staged` (eslint + prettier) on `frontend/` commits automatically.
- **Flyway migrations**: Schema managed by Flyway in `src/main/resources/db/migration/`. Never hand-edit DB schema.
- **shared-events library**: Event records are Java records (no Lombok). Consuming services add it as a Maven dependency.

## Env Config

| Variable | Default | Notes |
|---|---|---|
| `POSTGRES_HOST_PORT` | `5433` | Host-facing port (avoids local conflict) |
| `MONGO_HOST_PORT` | `27018` | Host-facing port |
| `AUTH_JWT_SECRET` | dev placeholder | Must be ≥32 bytes; prod requires real secret |
| `AUTH_EMAIL_PROVIDER` | `log` | `log` = no real email; `brevo` = Brevo API |
| `BACKEND_SERVICES_HOST` | `host.docker.internal` | Change to service names when containerized |

## Testing

- Backend: H2 in-memory (PostgreSQL mode) for unit/integration tests — no real DB needed
- Run single service tests: `cd services && ./mvnw -pl <service> -am test`
- Frontend: `npm run lint` + `npm run format:check`

## Conventions

- **Git**: branch `dev` (default) → `release` → `product`. Ticket branches named `PBL6-<id>-<description>`.
- **Commits**: Conventional Commits — `feat(backend): ...`, `fix(frontend): ...`, `docs(repo): ...`
- **PR**: Title `[PBL6-x] Description`, 1 approval required, CI must be green
- **Frontend style**: No semicolons, single quotes, trailing commas, 100 char width, LF line endings (`.prettierrc`)
- **New services**: Use `scripts\new-service.cmd` to scaffold from templates in `docs/templates/`

## Templates

Adding a new service requires:
1. `scripts\new-service.cmd -Name <svc> -Port <port>` — generates Dockerfile, .env files, CI workflow
2. Add `<module>` to `services/pom.xml`
3. Add service block to `docker-compose.yml`
4. Add `application-dev.yml` / `application-prod.yml` in the service

Template files: `docs/templates/` (Dockerfile, CI workflow, .env examples)
