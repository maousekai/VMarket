# =============================================================================
# __SERVICE_NAME__ — KHUNG MÔI TRƯỜNG PROD (file mẫu, commit vào git)
# =============================================================================
# QUY TRÌNH trên server triển khai:
#   1. cp .env.prod.example .env.prod
#   2. Điền các giá trị đánh dấu <<< ĐIỀN >>>
#   3. chmod 600 .env.prod
#   4. KHÔNG BAO GIỜ commit .env.prod (đã nằm trong .gitignore)
#
# Khác biệt so với dev (xem application-prod.yml của service):
#   - ddl-auto: validate (không để Hibernate tự sửa schema production)
#   - show-sql: false, log INFO (NFR-SEC-06)
#   - Mọi bí mật bắt buộc có giá trị, không có default trong code
# =============================================================================

SPRING_PROFILES_ACTIVE=prod
SERVER_PORT=__SERVICE_PORT__

# ---- PostgreSQL -------------------------------------------------------------
DB_HOST=postgres
DB_PORT=5432
DB_NAME=__DB_NAME__
DB_USERNAME=vmarket
DB_PASSWORD=                      # <<< ĐIỀN: mật khẩu mạnh, khác hẳn dev

# ---- MongoDB ----------------------------------------------------------------
MONGO_HOST=mongo
MONGO_PORT=27017

# ---- Redis ------------------------------------------------------------------
REDIS_HOST=redis
REDIS_PORT=6379

# ---- RabbitMQ ---------------------------------------------------------------
RABBITMQ_HOST=rabbitmq
RABBITMQ_PORT=5672
RABBITMQ_USERNAME=vmarket
RABBITMQ_PASSWORD=                # <<< ĐIỀN (không dùng guest/guest ở prod)

# ---- CORS -------------------------------------------------------------------
CORS_ALLOWED_ORIGINS=             # <<< ĐIỀN: domain thật của FE

# ---- Log --------------------------------------------------------------------
LOG_LEVEL=INFO

# ---- JVM (giới hạn RAM cho VPS nhỏ) -----------------------------------------
JAVA_OPTS=-XX:MaxRAMPercentage=70.0 -XX:+ExitOnOutOfMemoryError

# ---- Bí mật riêng của service ----------------------------------------------
# Thêm bên dưới, mỗi biến kèm chú thích <<< ĐIỀN >>>
