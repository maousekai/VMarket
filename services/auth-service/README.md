# Auth Service

Service xác thực và phân quyền RBAC của VMarket (Spring Boot, kiến trúc microservices).

## Công nghệ

- Java 17, Spring Boot 4.x
- Spring Web (REST), Spring Data JPA, Validation, Lombok
- Spring AMQP (RabbitMQ — sẵn sàng cho event bus)
- Spring Boot Actuator (health-check)
- PostgreSQL (dev/prod), H2 in-memory (chỉ cho test)
- Maven Wrapper (`mvnw`) đặt ở thư mục `services/` — không cần cài Maven

## Chạy local

Bước 1 — khởi động hạ tầng (từ thư mục **gốc** repo):

```bash
docker compose up -d
```

Lần đầu chạy, PostgreSQL tự tạo CSDL `vmarket_auth` cho service này.

Bước 2 — chạy service:

```bash
cd services/auth-service
..\mvnw.cmd spring-boot:run     # Windows
# ../mvnw spring-boot:run       # macOS/Linux
```

Service chạy tại cổng **8081**.

## Kiểm tra health-check

| Endpoint                | Mô tả                                    |
| ----------------------- | ---------------------------------------- |
| `GET /api/auth/health`  | Health-check của Auth Service            |
| `GET /actuator/health`  | Health-check Spring Boot Actuator        |

Qua API Gateway (cổng 8080): `curl http://localhost:8080/api/auth/health`

## Profile và biến môi trường

- `dev` (mặc định): PostgreSQL local, `ddl-auto: update`, bật SQL log.
- `prod`: cấu hình hoàn toàn qua biến môi trường, `ddl-auto: validate`.

| Biến                     | Mặc định (dev)   | Ý nghĩa           |
| ------------------------ | ---------------- | ----------------- |
| `SERVER_PORT`            | `8081`           | Cổng service      |
| `DB_HOST`                | `localhost`      | Host PostgreSQL   |
| `DB_PORT`                | `5432`           | Port PostgreSQL   |
| `DB_NAME`                | `vmarket_auth`   | Tên database      |
| `DB_USERNAME`            | `vmarket`        | User database     |
| `DB_PASSWORD`            | `vmarket`        | Mật khẩu database |
| `RABBITMQ_HOST`          | `localhost`      | Host RabbitMQ     |

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

## Cấu trúc thư mục (kiến trúc phân lớp)

```
src/main/java/com/vmarket/auth/
├── AuthServiceApplication.java   # Entry point
├── config/       # Cấu hình (CORS...)
├── controller/   # REST controller (/api/auth/**)
├── service/      # Business logic
├── repository/   # Spring Data repository
└── dto/          # Đối tượng truyền dữ liệu
```

## Roadmap nghiệp vụ (theo SRS)

- FR-AUTH-01: Đăng ký tài khoản (email + OTP kích hoạt)
- FR-AUTH-02: Đăng nhập JWT access/refresh token
- FR-AUTH-03: Đăng nhập Google OAuth 2.0
- FR-AUTH-04: Quên mật khẩu
- FR-AUTH-05: Phân quyền RBAC (Guest/Buyer/Seller/Shipper/Admin)
- FR-AUTH-06: Quản lý phiên (refresh/thu hồi token)
