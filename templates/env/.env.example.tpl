# =============================================================================
# __SERVICE_NAME__ — DANH MỤC ĐẦY ĐỦ BIẾN MÔI TRƯỜNG (file mẫu, commit vào git)
# =============================================================================
# "Hợp đồng cấu hình" của service: liệt kê MỌI biến service đọc, KHÔNG chứa
# giá trị thật. Thêm biến mới thì thêm vào ĐÂY trước.
# =============================================================================

# ---- Định danh & cổng -------------------------------------------------------
SPRING_PROFILES_ACTIVE=          # dev | prod
SERVER_PORT=                     # __SERVICE_PORT__

# ---- PostgreSQL (bỏ khối này nếu service dùng MongoDB/Redis) ----------------
DB_HOST=                         # trong docker: postgres | local: localhost
DB_PORT=                         # trong docker: 5432 | từ host: 5433
DB_NAME=                         # __DB_NAME__
DB_USERNAME=
DB_PASSWORD=

# ---- MongoDB (product, notification) ----------------------------------------
MONGO_HOST=
MONGO_PORT=

# ---- Redis (cart, delivery, recommendation) ---------------------------------
REDIS_HOST=
REDIS_PORT=

# ---- RabbitMQ (Event Bus — SRS §8.1) ----------------------------------------
RABBITMQ_HOST=
RABBITMQ_PORT=
RABBITMQ_USERNAME=
RABBITMQ_PASSWORD=

# ---- CORS -------------------------------------------------------------------
CORS_ALLOWED_ORIGINS=

# ---- Log (NFR-SEC-06: không log mật khẩu/token) -----------------------------
LOG_LEVEL=

# ---- JVM (để trống = dùng mặc định trong Dockerfile) ------------------------
JAVA_OPTS=

# ---- Bí mật riêng của service (thêm bên dưới) -------------------------------
# Ví dụ: PAYOS_CLIENT_ID, PAYOS_CHECKSUM_KEY (payment-service)
#        FCM_SERVER_KEY, MAIL_PASSWORD (notification-service)
# Quy tắc: giá trị thật CHỈ nằm ở GitHub Secrets hoặc .env.prod trên server.
