# =============================================================================
# __SERVICE_NAME__ — MÔI TRƯỜNG DEV (commit được: KHÔNG có bí mật thật)
# =============================================================================
# Khớp với hạ tầng trong docker-compose.yml, dùng khi container chạy TRONG
# network `vmarket-network`:
#
#   docker run --rm --network vmarket-network -p __SERVICE_PORT__:__SERVICE_PORT__ \
#     --env-file services/__SERVICE_NAME__/env/.env.dev \
#     vmarket-__SERVICE_NAME__:local
#
# Chạy bằng mvnw TRÊN MÁY HOST thì đổi: DB_HOST=localhost, DB_PORT=5433,
# MONGO_PORT=27018, RABBITMQ_HOST=localhost.
# =============================================================================

SPRING_PROFILES_ACTIVE=dev
SERVER_PORT=__SERVICE_PORT__

# ---- PostgreSQL -------------------------------------------------------------
DB_HOST=postgres
DB_PORT=5432
DB_NAME=__DB_NAME__
DB_USERNAME=vmarket
DB_PASSWORD=vmarket

# ---- MongoDB ----------------------------------------------------------------
MONGO_HOST=mongo
MONGO_PORT=27017

# ---- Redis ------------------------------------------------------------------
REDIS_HOST=redis
REDIS_PORT=6379

# ---- RabbitMQ ---------------------------------------------------------------
RABBITMQ_HOST=rabbitmq
RABBITMQ_PORT=5672
RABBITMQ_USERNAME=guest
RABBITMQ_PASSWORD=guest

# ---- CORS -------------------------------------------------------------------
CORS_ALLOWED_ORIGINS=http://localhost:5173,http://localhost:5174,http://localhost:3000

# ---- Log --------------------------------------------------------------------
LOG_LEVEL=DEBUG
