# Auth Service

Service xác thực và phân quyền RBAC của VMarket (Spring Boot, kiến trúc microservices).

## Công nghệ

- Java 17, Spring Boot 4.x
- Spring Web (REST), Spring Data JPA, Spring Security, Validation, Lombok
- Flyway (migration schema PostgreSQL)
- springdoc-openapi 3.x (Swagger UI)
- jjwt 0.12.x (JWT HS256 — dùng từ PBL6-43), ulid-creator (khóa chính ULID)
- Spring AMQP (RabbitMQ — sẵn sàng cho event bus)
- Spring Boot Actuator (health-check)
- PostgreSQL (dev/prod), H2 in-memory (chỉ cho test)
- Maven Wrapper (`mvnw`) đặt ở thư mục `services/` — không cần cài Maven

## Chạy local

Bước 1 — khởi động hạ tầng (từ thư mục **gốc** repo):

```bash
docker compose up -d postgres
```

Lần đầu chạy, PostgreSQL tự tạo CSDL `vmarket_auth`. Container publish ra host ở
cổng **5433** (`POSTGRES_HOST_PORT` — tránh đụng PostgreSQL cài sẵn trên máy).

Bước 2 — chạy service (profile `dev` mặc định → datasource trỏ `localhost:5433`):

```bash
cd services/auth-service
..\mvnw.cmd spring-boot:run     # Windows
# ../mvnw spring-boot:run       # macOS/Linux
```

Service chạy tại cổng **8081**. Lúc khởi động, **Flyway tự chạy** `V1__init_auth_schema.sql`
tạo 4 bảng + seed 5 role (log: `Migrating schema "public" to version "1"`).

> Nếu PostgreSQL nằm ở host/port khác, override khi chạy: `set DB_HOST=... & set DB_PORT=...`
> (Spring đọc `DB_HOST` / `DB_PORT` / `DB_NAME` / `DB_USERNAME` / `DB_PASSWORD`).

## Kiểm tra health-check

| Endpoint                | Mô tả                                    |
| ----------------------- | ---------------------------------------- |
| `GET /api/auth/health`  | Health-check của Auth Service            |
| `GET /actuator/health`  | Health-check Spring Boot Actuator        |

Qua API Gateway (cổng 8080): `curl http://localhost:8080/api/auth/health`

## Data model & migration (PBL6-41)

Schema do **Flyway** quản lý — file trong `src/main/resources/db/migration/`.
`V1__init_auth_schema.sql` tạo 4 bảng lõi và seed 5 vai trò RBAC:

| Bảng             | Vai trò                                                    |
| ---------------- | --------------------------------------------------------- |
| `users`          | Tài khoản (id ULID, email, username, password_hash, ...) |
| `roles`          | 5 vai trò cố định: GUEST / BUYER / SELLER / SHIPPER / ADMIN |
| `user_roles`     | Bảng nối many-to-many User–Role                          |
| `refresh_tokens` | Refresh token đã phát (chỉ lưu hash)                     |

V1 **chỉ chứa cột lõi**. Các cột nghiệp vụ (khóa tài khoản, xác thực email,
provider OAuth, xoay vòng refresh token...) sẽ do migration `V2`, `V3`... của
các subtask sau bổ sung theo expand–contract.

- `ddl-auto`: `validate` ở `dev` (Hibernate chỉ đối chiếu, không sửa DDL), `none` ở `prod`.
- Khóa chính: ULID (Crockford base32, 26 ký tự) sinh ở tầng app (`entity/BaseEntity`).

## Profile và biến môi trường

- `dev` (mặc định): PostgreSQL local, Flyway migrate, `ddl-auto: validate`, bật SQL log.
  Giá trị giả cho `AUTH_JWT_SECRET` nằm ở `application-dev.yml` (chỉ profile này).
- `prod`: cấu hình hoàn toàn qua biến môi trường, `ddl-auto: none` (⚠️ cần xác minh lại
  khi có quyền đọc `application-prod.yml`). `application.yml` base không đặt default cho
  `auth.jwt.secret` → thiếu `AUTH_JWT_SECRET` là fail ngay lúc khởi động.

| Biến                     | Mặc định (dev)   | Ý nghĩa                              |
| ------------------------ | ---------------- | ----------------------------------- |
| `SERVER_PORT`            | `8081`           | Cổng service                        |
| `DB_HOST`                | `localhost`      | Host PostgreSQL                     |
| `DB_PORT`                | `5432`           | Port PostgreSQL (Docker host: 5433) |
| `DB_NAME`                | `vmarket_auth`   | Tên database                        |
| `DB_USERNAME`            | `vmarket`        | User database                       |
| `DB_PASSWORD`            | `vmarket`        | Mật khẩu database                   |
| `RABBITMQ_HOST`          | `localhost`      | Host RabbitMQ                       |
| `AUTH_JWT_SECRET`        | *(giả, ở dev yml)* | Khóa ký JWT HS256 (≥ 32 byte); prod bắt buộc set |
| `AUTH_JWT_ACCESS_TTL`    | `15m`            | Thời hạn access token               |
| `AUTH_JWT_REFRESH_TTL`   | `30d`            | Thời hạn refresh token              |

Chạy với profile khác:

```bash
..\mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=prod
```

## Chạy bằng Docker

Build context là thư mục gốc repo (Dockerfile cần parent POM):

```bash
docker build -f services/auth-service/Dockerfile -t vmarket-auth-service .
docker run -p 8081:8081 -e DB_HOST=host.docker.internal vmarket-auth-service
```

## Chạy test

Test dùng H2 in-memory (MODE PostgreSQL) nên không cần PostgreSQL thật:

```bash
..\mvnw.cmd test
```

Swagger UI: `http://localhost:8081/swagger-ui.html` — spec JSON: `/v3/api-docs`.

## Cấu trúc thư mục (kiến trúc phân lớp)

```
src/main/java/com/vmarket/auth/
├── AuthServiceApplication.java   # Entry point
├── config/       # Cấu hình (Security, CORS, OpenAPI, AuthJwtProperties)
├── controller/   # REST controller (/api/auth/**)
├── service/      # Business logic
├── repository/   # Spring Data repository (User/Role/UserRole/RefreshToken)
├── entity/       # JPA entity + BaseEntity (ULID) + RoleName
├── exception/    # ApiException + GlobalExceptionHandler (body lỗi chuẩn)
└── dto/          # Đối tượng truyền dữ liệu
src/main/resources/db/migration/   # Flyway (V1, V2, ...)
```

## Endpoint

| Method & path            | Mô tả                                                      |
| ------------------------ | -------------------------------------------------------- |
| `POST /api/auth/register`| FR-AUTH-01 — đăng ký (BUYER, PENDING). 201 / 400 (`VALIDATION_ERROR`, `MALFORMED_REQUEST`) / 409 (`EMAIL_ALREADY_EXISTS`, `USERNAME_ALREADY_EXISTS`, `REGISTRATION_CONFLICT`) |
| `POST /api/auth/login`   | FR-AUTH-02 — đăng nhập bằng email. 200 (`TokenResponse`) / 401 `INVALID_CREDENTIALS` / **423 `ACCOUNT_LOCKED`** |
| `POST /api/auth/refresh` | FR-AUTH-02 — làm mới access token (xoay vòng). 200 / 401 (`INVALID_REFRESH_TOKEN`, `REFRESH_TOKEN_EXPIRED`, `REFRESH_TOKEN_REUSED`) |
| `POST /api/auth/otp/request` | FR-AUTH-01 — gửi mã OTP xác thực email. 200 / 400 `VALIDATION_ERROR` / 429 (`OTP_RESEND_TOO_SOON`, `OTP_RATE_LIMITED`) / 502 `EMAIL_SEND_FAILED` |
| `POST /api/auth/otp/verify`  | FR-AUTH-01 — xác nhận OTP → `email_verified=true` + `TokenResponse`. 200 / 400 (`OTP_NOT_FOUND`, `OTP_EXPIRED`, `OTP_INVALID`, `OTP_TOO_MANY_ATTEMPTS`, `OTP_ALREADY_USED`) |
| `GET  /api/auth/health`  | Health-check                                              |

Body lỗi mọi endpoint: `{ "error": { "code": "...", "message": "...", "details": [...] } }`.
Sai method → 405, sai `Content-Type` → 415, path không tồn tại → 404 (đều cùng format trên).

**Đăng ký:** email hợp lệ ≤320 ký tự (lowercase + trim); username 3–50 ký tự
`[a-zA-Z0-9._-]` (phân biệt hoa/thường, có trim); mật khẩu 8–32 ký tự, ≥1 chữ hoa +
≥1 số + ≥1 ký tự đặc biệt (hash BCrypt).

**Đăng nhập (FR-AUTH-02):**
- Access token: JWT HS256, `exp` mặc định 15 phút. Claims: `sub` (user id), `email`,
  `username`, `roles`, `email_verified`, `iss=auth-service`.
- Refresh token: chuỗi ngẫu nhiên mờ (không phải JWT), sống 15 ngày, DB chỉ lưu SHA-256.
  `/refresh` xoay vòng: token cũ bị thu hồi; **dùng lại token đã thu hồi → thu hồi
  toàn bộ phiên của user**.
- Khoá tài khoản: sai mật khẩu **5 lần liên tiếp** → khoá **15 phút** (423). Đăng nhập
  đúng → reset bộ đếm.
- User `email_verified = false` **vẫn đăng nhập được**, response `status = PENDING`
  (client hiển thị màn hình thông báo, chưa cho vào hệ thống bình thường).

**Xác thực email bằng OTP (FR-AUTH-01, migration V4):**
- `otp/request` → mã 6 số (`SecureRandom`), lưu **hash BCrypt** + hạn 5 phút vào
  bảng `email_otp`; gửi qua `EmailSender` (`auth.email.provider` = `brevo` | `log`).
- Chặn gửi lại trong **60 giây**; tối đa **5 mã / giờ / email**.
- `otp/verify` → sai **5 lần** thì mã vô hiệu; đúng thì `email_verified=true`
  (tạo tài khoản mới **không mật khẩu** nếu email chưa có user) và trả `TokenResponse`
  (auto-login, dùng chung `TokenIssuer` với `/login`).
- Tài khoản tạo qua OTP có `password_hash = NULL` → không đăng nhập bằng mật khẩu
  được (dùng lại OTP để vào).

## Roadmap nghiệp vụ (theo SRS)

- [x] PBL6-41: Setup & data model (entity, migration V1, cấu hình)
- [x] FR-AUTH-01 (PBL6-42): tạo tài khoản qua form (`POST /api/auth/register`, migration V2)
- [x] FR-AUTH-01 (PBL6-44): **xác thực email bằng OTP** (`/api/auth/otp/*`, migration V4)
- [x] FR-AUTH-02 (PBL6-43): đăng nhập JWT + refresh xoay vòng + khoá sau 5 lần sai (migration V3)
- [ ] FR-AUTH-03 (Google OAuth 2.0) — chuyển backlog (PBL6-44 đổi phạm vi sang OTP)
- [ ] FR-AUTH-04 (PBL6-45): Quên mật khẩu (dùng lại `EmailSender`)
- [ ] FR-AUTH-05/06 (PBL6-46): Phân quyền RBAC + quản lý phiên
- [ ] PBL6-47: Testing, Swagger & PR review
