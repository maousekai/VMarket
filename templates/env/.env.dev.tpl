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
#
# THỨ TỰ ƯU TIÊN: `environment:` trong compose > `env_file` (file này) > default.
# Khối service trong docker-compose.yml đã đặt DB_* / RABBITMQ_* qua
# `<<: *common-environment`, nên KHI CHẠY BẰNG COMPOSE các dòng DB_* /
# RABBITMQ_* dưới đây bị ghi đè; chúng chỉ có tác dụng với `docker run
# --env-file`. Biến RIÊNG của service thì ngược lại — file này là nguồn duy
# nhất, ĐỪNG khai lại chúng trong docker-compose.yml.
#
# TÊN BIẾN phải khớp đúng application.yml của service, nếu không Spring không
# đọc ra (placeholder không có default => container chết lúc khởi động):
#   grep -oh '\${[A-Z_]*' services/__SERVICE_NAME__/src/main/resources/application*.yml
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
