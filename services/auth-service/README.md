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
| `INTERNAL_API_KEY`       | *(giả, ở dev yml)* | Khoá API nội bộ `/internal/**` (≥ 32 ký tự), trùng user-service; prod bắt buộc set |
| `AUTH_REFRESH_COOKIE_SECURE`   | `false` (dev) | Cookie `refresh_token` chỉ gửi qua HTTPS; `true` bắt buộc ở môi trường dùng HTTPS thật |
| `AUTH_REFRESH_COOKIE_SAMESITE` | `Lax`         | SameSite của cookie `refresh_token` |

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
| `POST /api/auth/login`   | FR-AUTH-02 — đăng nhập bằng email. 200 (`TokenResponse` + cookie `refresh_token`) / 401 `INVALID_CREDENTIALS` / **423 `ACCOUNT_LOCKED`** |
| `POST /api/auth/refresh` | FR-AUTH-02 — làm mới access token bằng cookie `refresh_token` (xoay vòng). 200 / 401 (`REFRESH_TOKEN_MISSING`, `INVALID_REFRESH_TOKEN`, `REFRESH_TOKEN_EXPIRED`, `REFRESH_TOKEN_REUSED`, `REFRESH_TOKEN_ROTATION_CONFLICT`) |
| `POST /api/auth/logout`  | FR-AUTH-06 — thu hồi phiên hiện tại (cookie `refresh_token`) + xoá cookie. 200 (`MessageResponse`, idempotent) |
| `GET /api/auth/sessions` | FR-AUTH-06 — liệt kê phiên/thiết bị đang hoạt động của user gọi. 200 (`SessionSummary[]`) / 401 (`REFRESH_TOKEN_MISSING`, `SESSION_NOT_FOUND`) |
| `DELETE /api/auth/sessions/{id}` | FR-AUTH-06 — thu hồi một phiên cụ thể (ownership-checked). 200 / 401 / 404 `TARGET_SESSION_NOT_FOUND` |
| `POST /api/auth/sessions/revoke-others` | FR-AUTH-06 — thu hồi mọi phiên khác, giữ lại phiên hiện tại. 200 (`MessageResponse`) / 401 |
| `POST /api/auth/otp/request` | FR-AUTH-01 — gửi mã OTP xác thực email. 200 / 400 `VALIDATION_ERROR` / 429 (`OTP_RESEND_TOO_SOON`, `OTP_RATE_LIMITED`) / 502 `EMAIL_SEND_FAILED` |
| `POST /api/auth/otp/verify`  | FR-AUTH-01 — xác nhận OTP → `email_verified=true` + `TokenResponse` (+ cookie `refresh_token`). 200 / 400 (`OTP_NOT_FOUND`, `OTP_EXPIRED`, `OTP_INVALID`, `OTP_TOO_MANY_ATTEMPTS`, `OTP_ALREADY_USED`) |
| `POST /api/auth/password/forgot` | FR-AUTH-04 — gửi mã đặt lại mật khẩu. 200 (không tiết lộ email tồn tại hay không) / 400 `VALIDATION_ERROR` / 429 (`PASSWORD_RESET_TOO_SOON`, `PASSWORD_RESET_RATE_LIMITED`) / 502 `EMAIL_SEND_FAILED` |
| `POST /api/auth/password/reset`  | FR-AUTH-04 — xác nhận mã + đặt mật khẩu mới (không tự đăng nhập). 200 (`MessageResponse`) / 400 (`PASSWORD_RESET_NOT_FOUND`, `PASSWORD_RESET_EXPIRED`, `PASSWORD_RESET_INVALID`, `PASSWORD_RESET_TOO_MANY_ATTEMPTS`, `PASSWORD_RESET_ALREADY_USED`) |
| `GET  /api/auth/health`  | Health-check                                              |

**API nội bộ `/internal/users/**` (PBL6-13)** — chỉ cho service khác gọi (hiện là
user-service), header `X-Internal-Api-Key: <INTERNAL_API_KEY>`; thiếu/sai → 401. Gateway
không định tuyến các path này. Endpoint công khai tương ứng nằm ở user-service
(`PUT /api/users/me/password`).

`InternalApiKeyFilter` và tầng phân quyền dùng **chung một `RequestMatcher`**
(`PathPatternRequestMatcher` cho `/internal/**`, tạo một lần trong `SecurityConfig` rồi
truyền vào filter). Hai bên tự so chuỗi riêng thì sẽ lệch: `getRequestURI()` có cả
context path, còn matcher so trên đường dẫn *trong ứng dụng* — trùng khớp chỉ vì context
path đang rỗng. Đặt `server.servlet.context-path` là filter bỏ qua sạch các lời gọi hợp
lệ và **mọi** request nội bộ trả 401. `InternalApiContextPathTest` khoá lại hành vi này.

| Method & path | Mô tả |
| --- | --- |
| `PUT /internal/users/{id}/password` | FR-USER-03 — đổi mật khẩu `{currentPassword, newPassword}`. 204 / 400 (`VALIDATION_ERROR`, `INVALID_CURRENT_PASSWORD`, `PASSWORD_UNCHANGED`, `PASSWORD_NOT_SET`) / 404 `USER_NOT_FOUND` / 423 `ACCOUNT_LOCKED` |

Body lỗi mọi endpoint: `{ "error": { "code": "...", "message": "...", "details": [...] } }`.
Sai method → 405, sai `Content-Type` → 415, path không tồn tại → 404 (đều cùng format trên).

**Đăng ký:** email hợp lệ ≤320 ký tự (lowercase + trim); username 3–50 ký tự
`[a-zA-Z0-9._-]` (phân biệt hoa/thường, có trim); mật khẩu 8–32 ký tự, ≥1 chữ hoa +
≥1 số + ≥1 ký tự đặc biệt (hash BCrypt).

**Đăng nhập (FR-AUTH-02):**
- Access token: JWT HS256, `exp` mặc định 15 phút. Claims: `sub` (user id), `email`,
  `username`, `roles`, `email_verified`, `jti`, `iss=auth-service`.
- Refresh token: chuỗi ngẫu nhiên mờ (không phải JWT), sống 15 ngày, DB chỉ lưu SHA-256.
  Gắn vào cookie **HttpOnly** `refresh_token` (path `/api/auth`) — **không** nằm
  trong body JSON (PBL6-46), giảm rủi ro bị đánh cắp qua XSS so với lưu ở
  localStorage/biến JS. `/refresh` xoay vòng (đọc cookie, ghi đè cookie mới): token
  cũ bị thu hồi; **dùng lại token đã thu hồi → thu hồi toàn bộ phiên của user**.
  Hai request `/refresh` chạy song song cùng token cũ (2 tab, request trùng lặp) →
  request thua trả `401 REFRESH_TOKEN_ROTATION_CONFLICT` nhưng **không** xoá cookie
  (cookie hiện tại có thể đã là token mới do request thắng cuộc ghi).
- Khoá tài khoản: sai mật khẩu **5 lần liên tiếp** → khoá **15 phút** (423). Đăng nhập
  đúng → reset bộ đếm.
- User `email_verified = false` **vẫn đăng nhập được**, response `status = PENDING`
  (client hiển thị màn hình thông báo, chưa cho vào hệ thống bình thường).

**Quản lý phiên (FR-AUTH-06, migration V6):**
- "Phiên" = một dòng `refresh_tokens` còn hiệu lực (`revoked_at IS NULL`); xoay vòng
  tạo dòng mới nên phiên tồn tại xuyên suốt các lần `/refresh` của cùng thiết bị.
  `user_agent`/`ip_address` là best-effort (ưu tiên `X-Forwarded-For`), chỉ để hiển
  thị, không dùng cho quyết định bảo mật.
- Định danh "phiên gọi" ở các endpoint `logout`/`sessions/*` là **chính cookie
  `refresh_token`** hiện tại — auth-service chưa có JWT verification riêng cho
  route bảo vệ của mình, nên dùng lại refresh token (đã là credential bền của
  phiên) thay vì access token (stateless, không đối chiếu DB).
- `sessions/{id}` chỉ cho thu hồi phiên thuộc đúng user gọi; id lạ hoặc của người
  khác đều trả `404 TARGET_SESSION_NOT_FOUND` (không phân biệt, tránh dò id).

**Xác thực email bằng OTP (FR-AUTH-01, migration V4):**
- `otp/request` → mã 6 số (`SecureRandom`), lưu **hash BCrypt** + hạn 5 phút vào
  bảng `email_otp`; gửi qua `EmailSender` (`auth.email.provider` = `brevo` | `log`).
- Chặn gửi lại trong **60 giây**; tối đa **5 mã / giờ / email**.
- `otp/verify` → sai **5 lần** thì mã vô hiệu; đúng thì `email_verified=true`
  (tạo tài khoản mới **không mật khẩu** nếu email chưa có user) và trả `TokenResponse`
  (auto-login, dùng chung `TokenIssuer` với `/login`).
- Tài khoản tạo qua OTP có `password_hash = NULL` → không đăng nhập bằng mật khẩu
  được (dùng lại OTP để vào).

**Quên mật khẩu (FR-AUTH-04, migration V5):**
- `password/forgot` → mã 6 số (`SecureRandom`), lưu **hash BCrypt** + hạn 5 phút vào
  bảng `password_reset_token` (key theo `user_id`, khác `email_otp` key theo email);
  gửi qua `EmailSender`. Email chưa đăng ký vẫn trả response giống hệt (không tiết lộ).
- Chặn gửi lại trong **60 giây**; tối đa **5 mã / giờ / tài khoản**.
- `password/reset` → sai **5 lần** thì mã vô hiệu; đúng thì băm lại `password_hash`,
  gỡ khoá đăng nhập (`failed_login_attempts`/`locked_until`) và thu hồi toàn bộ
  refresh token hiện có. **Không tự đăng nhập** — phải đăng nhập lại bằng mật khẩu mới.
- Cũng là cách đầu tiên để đặt mật khẩu cho tài khoản tạo qua OTP (vốn
  `password_hash = NULL`).

**Đổi mật khẩu (FR-USER-03):**
- Sai mật khẩu hiện tại **tính chung bộ đếm** với đăng nhập sai (5 lần → khoá 15
  phút). Không dùng chung bộ đếm thì ai cầm được access token (còn sống ≤ 15 phút)
  có thể dò mật khẩu không giới hạn qua endpoint đổi mật khẩu.
- Sai mật khẩu hiện tại trả **400 `INVALID_CURRENT_PASSWORD`**, không phải 401: 401
  khiến frontend tưởng access token hết hạn và đăng xuất người dùng, trong khi họ
  chỉ gõ nhầm mật khẩu cũ.
- Thành công → **thu hồi mọi refresh token**: đổi mật khẩu thường là vì nghi bị lộ,
  các phiên cũ phải đăng nhập lại. Cùng quyết định với đặt lại mật khẩu (PBL6-45).
- Tài khoản tạo qua OTP chưa có mật khẩu → **400 `PASSWORD_NOT_SET`**: không có "mật
  khẩu hiện tại" để xác minh, phải dùng Quên mật khẩu để đặt lần đầu.
- **Không cần migration mới:** FR-USER-03 chỉ ghi `users.password_hash` và bảng
  `refresh_tokens` đã có sẵn từ V1/V3.

**Giới hạn đã biết & đánh đổi có chủ đích (FR-USER-03):**

- **Người giữ access token có thể khoá tài khoản chủ sở hữu.** Dùng chung bộ đếm nghĩa
  là kẻ cầm access token chỉ cần gửi 5 lần sai mật khẩu hiện tại là khoá tài khoản 15
  phút, và `registerFailedAttempt` thu hồi mọi refresh token → chủ tài khoản bị đăng
  xuất và chưa đăng nhập lại được. Đây là **đánh đổi có chủ đích**: không có bộ đếm thì
  kẻ đó dò được mật khẩu, mà mất mật khẩu là thiệt hại không hồi phục được, còn khoá 15
  phút thì có. Nhánh khoá xuất phát từ endpoint đổi mật khẩu được log **WARN riêng**
  (kèm `userId` và ghi rõ nguồn) để còn phát hiện khi bị lạm dụng — khác với log của
  đăng nhập sai.
- **Access token cũ sống thêm ≤ 15 phút sau khi đổi mật khẩu.** Chỉ refresh token bị
  thu hồi; access token là JWT tự chứa nên không vô hiệu hoá được mà không tra CSDL ở
  mỗi request. Khắc phục cần `token_version` trong claim hoặc denylist theo `sub + iat`
  → **ticket riêng**, vì đụng tới mọi service đang verify token.
- **Một khoá nội bộ duy nhất, cấp trọn quyền `/internal/**`.** `ROLE_INTERNAL_SERVICE`
  không phân biệt service gọi, và khoá chỉ có một giá trị nên xoay khoá phải deploy đồng
  thời hai service. Đủ cho một endpoint nội bộ; khi có endpoint thứ hai/thứ ba nên chuyển
  sang danh sách khoá (giữ khoá cũ + mới) hoặc scope theo service.
- **`authenticationEntryPoint` áp cho mọi request chưa xác thực**, không riêng
  `/internal/**` — body 401 của các đường dẫn khác cũng đổi từ rỗng sang hình dạng chuẩn
  `{ "error": { "code", "message" } }`. Đúng theo quy ước lỗi chung của dự án, nhưng là
  **thay đổi hành vi ngoài phạm vi FR-USER-03**: cần đối chiếu khi gộp với RBAC của
  PBL6-46.

## Roadmap nghiệp vụ (theo SRS)

- [x] PBL6-41: Setup & data model (entity, migration V1, cấu hình)
- [x] FR-AUTH-01 (PBL6-42): tạo tài khoản qua form (`POST /api/auth/register`, migration V2)
- [x] FR-AUTH-01 (PBL6-44): **xác thực email bằng OTP** (`/api/auth/otp/*`, migration V4)
- [x] FR-AUTH-02 (PBL6-43): đăng nhập JWT + refresh xoay vòng + khoá sau 5 lần sai (migration V3)
- [ ] FR-AUTH-03 (Google OAuth 2.0) — chuyển backlog (PBL6-44 đổi phạm vi sang OTP)
- [x] FR-AUTH-04 (PBL6-45): **Quên mật khẩu** (`/api/auth/password/*`, migration V5)
- [x] PBL6-13 (FR-USER-03): API nội bộ `/internal/users/{id}/password` cho user-service đổi mật khẩu (không cần migration)
- [x] FR-AUTH-05/06 (PBL6-46): **Phân quyền RBAC (gateway) + quản lý phiên** (`/api/auth/logout`, `/api/auth/sessions/*`, migration V6)
- [ ] PBL6-47: Testing, Swagger & PR review
