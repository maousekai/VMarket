# VMarket Backend

Service backend mẫu (Spring Boot) làm khung cho các service nghiệp vụ của dự án VMarket.

## Công nghệ

- Java 17, Spring Boot 4.x
- Spring Web (REST), Spring Data JPA, Validation, Lombok
- Spring Boot Actuator (health-check)
- PostgreSQL (dev/prod), H2 in-memory (chỉ cho test)
- Maven Wrapper (`mvnw`) — không cần cài Maven

## Yêu cầu môi trường

- JDK 17+
- Docker Desktop (để chạy PostgreSQL qua `docker compose`) **hoặc** PostgreSQL local
- Không cần cài Maven (dùng `mvnw` kèm theo)

## Chạy local

Bước 1 — khởi động PostgreSQL (từ thư mục **gốc** repo):

```bash
docker compose up -d db
```

> Nếu dùng PostgreSQL local thay vì Docker, tạo database `vmarket` và user `vmarket/vmarket` (hoặc sửa biến môi trường `DB_*` bên dưới).

Bước 2 — chạy service (từ thư mục `backend/`):

```bash
# Windows
.\mvnw.cmd spring-boot:run

# macOS / Linux
./mvnw spring-boot:run
```

Service chạy tại `http://localhost:8080`.

## Kiểm tra health-check

| Endpoint            | Mô tả                                   |
| ------------------- | --------------------------------------- |
| `GET /api/health`   | Health-check của service (qua phân lớp) |
| `GET /actuator/health` | Health-check của Spring Boot Actuator |

```bash
curl http://localhost:8080/api/health
```

## Profile và biến môi trường

- `dev` (mặc định): `application-dev.yml`, PostgreSQL local, `ddl-auto: update`, bật SQL log.
- `prod`: `application-prod.yml`, cấu hình hoàn toàn qua biến môi trường, `ddl-auto: validate`.

| Biến                   | Mặc định (dev)   | Ý nghĩa              |
| ---------------------- | ---------------- | -------------------- |
| `SPRING_PROFILES_ACTIVE` | `dev`          | Profile đang dùng    |
| `DB_HOST`              | `localhost`      | Host PostgreSQL      |
| `DB_PORT`              | `5432`           | Port PostgreSQL      |
| `DB_NAME`              | `vmarket`        | Tên database         |
| `DB_USERNAME`          | `vmarket`        | User database        |
| `DB_PASSWORD`          | `vmarket`        | Mật khẩu database    |

Chạy với profile khác:

```bash
.\mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=prod
```

## Chạy bằng Docker

Dockerfile dùng multi-stage build (Maven → JRE 17):

```bash
docker build -t vmarket-backend .
docker run -p 8080:8080 -e DB_HOST=host.docker.internal vmarket-backend
```

## Chạy test

Test dùng H2 in-memory (MODE PostgreSQL) nên không cần PostgreSQL thật:

```bash
.\mvnw.cmd test
```

## Cấu trúc thư mục (kiến trúc phân lớp)

```
src/main/java/com/vmarket/
├── VmarketBackendApplication.java   # Entry point
├── config/       # Cấu hình (CORS...)
├── controller/   # REST controller
├── service/      # Business logic
├── repository/   # Spring Data JPA repository
└── dto/          # Đối tượng truyền dữ liệu (request/response)
```
