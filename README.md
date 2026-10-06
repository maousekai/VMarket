# VMarket (PBL6)

Nền tảng thương mại điện tử đa người bán tích hợp AI — xây dựng theo **kiến trúc microservices** hướng sự kiện (xem [SRS](docs/SRS-VMarket.md)).

## Kiến trúc tổng quan

```
Web end-user (5173) ─┐
Web Admin  (5174)   ─┼──> API Gateway (8080) ──> các service nghiệp vụ (Spring Boot)
Mobile (React Native)┘         │                        │
                               │                    Event Bus (RabbitMQ)
                               ▼                        ▼
                    JWT / Rate limit            AI services (FastAPI)
```

- Mọi request từ client đi qua **API Gateway** (định tuyến, xác thực JWT).
- Các service giao tiếp đồng bộ qua REST, bất đồng bộ qua **RabbitMQ**.
- **Database-per-service**: mỗi service sở hữu CSDL riêng (đọc ghi qua API/sự kiện, không truy cập chéo).

## Cấu trúc repo

```
VMarket/
├── services/                  # Toàn bộ microservice (Maven multi-module)
│   ├── pom.xml                # Parent POM - quản lý version chung (Boot/Cloud/Lombok)
│   ├── mvnw                   # Maven Wrapper dùng chung (build từ đây cho cả 11 module)
│   ├── api-gateway/           # Spring Cloud Gateway (8080)
│   ├── auth-service/          # Xác thực, phân quyền RBAC (8081, PostgreSQL)
│   ├── user-service/          # Hồ sơ, sổ địa chỉ, đổi mật khẩu, Admin quản lý người dùng (8082, PostgreSQL)
│   ├── shop-service/          # Gian hàng (8083, PostgreSQL)
│   ├── product-service/       # Danh mục, sản phẩm, tồn kho (8084, MongoDB)
│   ├── cart-service/          # Giỏ hàng (8085, Redis)
│   ├── order-service/         # Đơn hàng, trả hàng (8086, PostgreSQL)
│   ├── payment-service/       # Thanh toán PayOS/COD (8087, PostgreSQL)
│   ├── delivery-service/      # Giao hàng, định vị shipper (8088, PostgreSQL)
│   ├── review-service/        # Đánh giá sản phẩm (8089, PostgreSQL)
│   ├── notification-service/  # Thông báo, email, FCM (8090, MongoDB)
│   ├── ai-search-service/     # FR-SRCH-01–05 (8100, FastAPI + Elasticsearch + CPU PyTorch)
│   ├── recommendation-service/# Gợi ý sản phẩm (8101, FastAPI + PostgreSQL + Redis)
│   └── chatbot-service/       # Chatbot RAG (8102, FastAPI + MongoDB)
├── frontend/                  # Web end-user (React + Vite, cổng 5173)
├── .github/workflows/         # CI/CD - mỗi service một workflow riêng
│   ├── service-ci.yml         # Reusable workflow: test + build image (dùng chung)
│   └── <ten-service>.yml      # File gọi, trigger theo thư mục của service đó
├── scripts/                   # Script quản lý dự án (vmarket.cmd, new-service.cmd)
├── infra/                     # Cấu hình hạ tầng (init script PostgreSQL, logging/monitoring...)
├── docs/
│   ├── SRS-VMarket.md         # Đặc tả yêu cầu
│   ├── search-contract.json  # Contract Search dùng chung Python/Java/nginx
│   ├── openapi/               # API contract Search và Product snapshot nội bộ
│   └── templates/             # Template Dockerfile + CI/CD + .env cho service mới
├── worklogs/                 # Công việc, kiểm chứng và giới hạn theo ticket/PR
├── docker-compose.yml         # Hạ tầng dùng chung cho dev
└── CONTRIBUTING.md            # Quy ước branch/commit/PR
```

Mỗi service trong `services/` là một đơn vị **độc lập**: có `Dockerfile`,
`.env.example` / `.env.prod.example` và workflow CI riêng — build, test, đóng gói
tách rời với các service khác.

> Web Admin và Mobile (React Native) sẽ được thêm vào ở các sprint tiếp theo, cùng nằm ở thư mục gốc (`admin/`, `mobile/`).

## Yêu cầu môi trường

| Công cụ  | Phiên bản | Ghi chú                                  |
| -------- | --------- | ---------------------------------------- |
| JDK      | 17+       | Eclipse Temurin khuyến nghị              |
| Node.js  | 20+       | Cho frontend                             |
| Docker   | mới nhất  | Docker Desktop (WSL2 backend)            |
| Python   | 3.12+     | Chỉ khi chạy AI service ngoài Docker     |
| Git      | mới nhất  | —                                        |

> Không cần cài Maven (mỗi service có sẵn Maven Wrapper `mvnw`).

## Chạy hạ tầng (tối ưu tài nguyên)

Từ thư mục gốc, tạo `.env` từ `.env.example` nếu chưa có và đặt rõ
`MINIO_ROOT_USER`/`MINIO_ROOT_PASSWORD` trước khi chạy Compose. Password trong
example để trống; Compose từ chối giá trị thiếu/rỗng, kể cả khi chưa chọn profile
Search. Giữ nguyên `.env` đã cấu hình, không ghi đè credentials đang sử dụng.

```bash
# Bộ nhẹ mặc định (~1.5GB RAM): PostgreSQL + MongoDB + Redis + RabbitMQ
docker compose up -d postgres mongo mongo-init-replica redis rabbitmq

# Chỉ định lẻ khi cần
docker compose up -d postgres redis

# AI Search: Elasticsearch + MinIO-compatible storage + RabbitMQ
docker compose --profile search up -d elasticsearch minio rabbitmq

# Loki + Promtail + Prometheus + Grafana (~0.9GB) chỉ bật khi cần xem log/dashboard
docker compose --profile monitoring up -d

# Xoá sạch khi không dùng nữa
docker compose down -v
```

Mỗi container có giới hạn RAM (`mem_limit`). Với cấu hình AI Search, trần toàn
dự án là **7,000,000,000 byte**, gồm các service khác và overhead Docker/WSL;
cap riêng từng container chưa chứng minh toàn bộ stack nằm trong trần này.
Cấu hình WSL2 qua `~/.wslconfig` và áp dụng sau khi `wsl --shutdown` rồi mở lại
Docker Desktop. Xem [giới hạn kiểm chứng Search](services/ai-search-service/VERIFICATION.md).

Sau khi `docker compose up -d` lần đầu, PostgreSQL tự tạo đủ CSDL cho từng service: `vmarket_auth`, `vmarket_user`, `vmarket_shop`, `vmarket_order`, `vmarket_payment`, `vmarket_delivery`, `vmarket_review`, `vmarket_recommendation` (MongoDB/Redis tự khởi tạo khi dùng).

## Logging & Monitoring (xem log / dashboard tập trung)

Thay vì `docker logs` từng container, cả nhóm xem log và metrics của mọi service ở
**một chỗ là Grafana**. Bộ này nằm trong profile `monitoring` (~0.9GB RAM), chỉ bật khi cần:

```bash
docker compose --profile monitoring up -d      # hoặc: scripts\vmarket.cmd infra-monitoring
```

| Công cụ    | Địa chỉ                  | Dùng để                                                        |
| ---------- | ------------------------ | -------------------------------------------------------------- |
| Grafana    | http://localhost:3001    | Dashboard + xem log. Xem không cần đăng nhập (sửa: `admin`/`admin`) |
| Prometheus | http://localhost:9090    | Kiểm tra service nào đang được scrape: trang **Status → Targets** |
| Loki       | http://localhost:3100    | Kho log, chỉ có API — xem qua Grafana                          |

> Ba cổng trên chỉ bind vào `127.0.0.1` (chỉ mở được từ chính máy chạy Docker). Loki không có
> xác thực, Grafana cho xem ẩn danh, mà log dev chứa mã OTP / mã đặt lại mật khẩu
> (`AUTH_EMAIL_PROVIDER=log`) — **không** đổi `MONITORING_BIND_ADDRESS` thành `0.0.0.0` trên máy
> mà người khác truy cập được qua mạng.

```
service Spring Boot ──/actuator/prometheus──> Prometheus ─┐
container stdout (JSON) ──> Promtail ──> Loki ────────────┴─> Grafana
```

### Xem dashboard

Mở http://localhost:3001 — trang chủ chính là dashboard **VMarket - Services Overview**
(cũng nằm ở *Dashboards → VMarket*):

- **Trạng thái UP / DOWN** và **Uptime** của từng service.
- **Request rate** (req/s), **Error rate** (% response 5xx), thời gian phản hồi trung bình, JVM heap.
- **Số dòng log WARN / ERROR** theo thời gian và khung **log tập trung** ở cuối trang.

Chọn service ở ô **Service** phía trên; gõ vào ô **Tìm trong log** để lọc log theo chuỗi
(ví dụ một email, một mã đơn hàng).

### Xem / tìm log

Dùng khung log trong dashboard, hoặc vào **Explore** (biểu tượng la bàn) → chọn datasource
**Loki** rồi gõ truy vấn LogQL:

```logql
{service="auth-service"}                              # toàn bộ log của 1 service
{service="auth-service", level="ERROR"}               # chỉ dòng ERROR
{service=~".+-service", level=~"WARN|ERROR"}          # WARN/ERROR của mọi service
{service="user-service"} |= "NullPointerException"    # lọc theo chuỗi
{service="auth-service"} | json | logger_name=~".*OtpService"   # lọc theo field JSON
```

Nhãn có sẵn: `service` (tên service trong `docker-compose.yml`, kể cả `postgres`,
`rabbitmq`...), `container`, `level` (chỉ có với service Spring Boot). Log giữ 7 ngày.

### Cần biết

- **Log JSON chỉ bật trong container** (biến `LOGGING_STRUCTURED_FORMAT_CONSOLE=logstash`
  trong Dockerfile). Chạy local bằng `mvnw` vẫn là log text dễ đọc như cũ.
- **Log chỉ gom từ container.** Service chạy local bằng `mvnw` không có log trên Loki
  (xem ở cửa sổ console của nó), nhưng **metrics vẫn có**: Prometheus scrape chúng qua
  `host.docker.internal:<port>`. Service chưa chạy sẽ hiện **DOWN** trên dashboard — đúng như thiết kế.
- **Container hoá thêm một service:** thêm block vào `docker-compose.yml` như bình thường
  (log tự được gom), rồi đổi target của nó trong
  [`infra/monitoring/prometheus/prometheus.yml`](infra/monitoring/prometheus/prometheus.yml)
  từ `host.docker.internal:<port>` sang `<tên-service>:<port>`.
- **Service mới** cần 3 thứ để lên dashboard: dependency `micrometer-registry-prometheus`,
  `prometheus` trong `management.endpoints.web.exposure.include`, và mở
  `/actuator/prometheus` trong `SecurityConfig` (nếu service có Spring Security).
- Thêm dashboard: thả file `.json` vào `infra/monitoring/grafana/dashboards/`.

## Chạy một service (ví dụ Auth)

```bash
cd services/auth-service
..\mvnw.cmd spring-boot:run       # Windows (Maven Wrapper đặt ở services/)
# ../mvnw spring-boot:run         # macOS/Linux
```

Mỗi service đọc cấu hình từ biến môi trường (đã có giá trị dev mặc định khớp compose): `DB_HOST`, `DB_PORT`, `DB_NAME`, `RABBITMQ_HOST`...

> **Tối ưu khi dev local:** chỉ chạy **API Gateway + các service bạn đang làm**. Không cần bật hết 14 service.

## AI Search — PBL6-21

[README AI Search](services/ai-search-service/README.md) hướng dẫn setup, API,
calibration, rebuild/recovery và kiểm thử nhanh khi chưa có ảnh sản phẩm thật.
Service chạy ở cổng 8100, public API qua gateway `/api/ai/search`; phụ thuộc
Product Catalog, RabbitMQ, Elasticsearch và MinIO-compatible storage.

Embedding mặc định dùng CPU PyTorch MobileNetV3 Small. LLM mở rộng từ khóa dùng
provider tương thích OpenAI do operator chọn trong `.env`; thiếu URL/model/key
hoặc chưa cho phép gửi từ khóa ra ngoài thì tìm kiếm từ khóa vẫn chạy bằng
Elasticsearch. Ollama native/container là tùy chọn cho LLM. Phạm vi chỉ
**FR-SRCH-01–05**, không cần Recommendation Service hoặc FR-SRCH-06.

Image search cần weights đã xác minh và calibration hợp lệ trước khi chạy.
Giới hạn/error/path dùng [contract chung](docs/search-contract.json); các thay đổi
phải sinh lại constants/config bằng `python scripts/generate-search-contract.py`.
[Worklog PBL6-21](worklogs/PBL6-21.md) ghi nội dung PR và kết quả đã kiểm chứng;
[VERIFICATION.md](services/ai-search-service/VERIFICATION.md) ghi các acceptance gate còn lại.

## Chạy API Gateway

```bash
cd services/api-gateway
..\mvnw.cmd spring-boot:run       # cổng 8080
```

Gateway định tuyến theo prefix: `/api/auth/**` → auth-service, `/api/products/**` → product-service... (xem `application.yml` của gateway; có thể đổi URL đích bằng biến `AUTH_SERVICE_URL`, `PRODUCT_SERVICE_URL`...).

## Build & test toàn bộ backend (multi-module)

```bash
cd services
.\mvnw.cmd test                   # chạy test cả 11 module một lệnh
.\mvnw.cmd clean package -DskipTests
```

Version Spring Boot / Spring Cloud / Lombok quản lý tập trung ở `services/pom.xml` — nâng cấp chỉ cần sửa 1 nơi.

## Scripts tiện lợi (Windows)

```bash
scripts\vmarket.cmd infra                  # bật hạ tầng nhẹ
scripts\vmarket.cmd infra-search           # bật profile Search (Elasticsearch + MinIO)
scripts\vmarket.cmd core                   # chạy gateway + auth + user
scripts\vmarket.cmd service order-service  # chạy 1 service bất kỳ
scripts\vmarket.cmd fe                     # chạy frontend
scripts\vmarket.cmd build                  # build toàn bộ backend
scripts\vmarket.cmd test                   # test toàn bộ backend
scripts\vmarket.cmd stop                   # tắt các process Java
```

## Build Docker cho từng service

Build context là **thư mục gốc repo** (Dockerfile cần parent POM):

```bash
docker build -f services/auth-service/Dockerfile -t vmarket-auth-service .
```

Dockerfile của các service Spring Boot sinh ra từ cùng một template
([`docs/templates/Dockerfile.springboot`](docs/templates/Dockerfile.springboot)):
3 stage — tải dependency (cache riêng theo `pom.xml`) → build jar bằng Maven →
chạy bằng JRE slim với user thường. Sửa code Java rồi build lại chỉ mất ~14 giây
vì layer dependency được dùng lại.

AI Search dùng Dockerfile Python riêng, cũng build từ gốc repo; weights/private
env không nằm trong image. Xem [hướng dẫn container Search](services/ai-search-service/README.md#events-failure-recovery-and-memory).

Chạy container riêng lẻ bằng file `.env` của service:

```bash
cp services/auth-service/.env.example services/auth-service/.env
docker run --env-file services/auth-service/.env -p 8081:8081 vmarket-auth-service
```

## CI/CD

Mỗi service có một workflow riêng trong `.github/workflows/`, **trigger theo đường
dẫn**: sửa `services/auth-service/**` thì chỉ CI của auth-service chạy. Toàn bộ
logic của các service Java nằm ở một *reusable workflow* dùng chung (`service-ci.yml`):

| Job     | Làm gì                                                                    |
| ------- | ------------------------------------------------------------------------- |
| `test`  | `mvnw -pl <service> -am test` — chỉ test module của service đó             |
| `image` | Build Docker image của service đó + smoke test (không cần DB/RabbitMQ)     |

AI Search có [workflow riêng](.github/workflows/ai-search-service.yml): unit/audit,
Elasticsearch integration và Docker build chạy ở các job tách biệt. Workflow
env-consistency kiểm tra cả các file được sinh từ Search contract để chặn drift.

Mặc định pipeline **chỉ build image, chưa push lên registry** nên chưa cần khai báo
secret nào. Khi nhóm chốt registry thì bật bằng cách bỏ comment vài dòng — xem
[`docs/templates/README.md`](docs/templates/README.md).

## Thêm service mới

```powershell
# Sau khi tạo skeleton Spring Boot trong services/<tên-service>:
scripts\new-service.cmd -Name order-service -Port 8086            # PostgreSQL
scripts\new-service.cmd -Name product-service -Port 8084 -Store mongo
scripts\new-service.cmd -Name cart-service -Port 8085 -Store redis
```

Script gắn sẵn Dockerfile, workflow CI và file `.env` dev/prod theo đúng chuẩn
chung. Chi tiết: [`docs/templates/README.md`](docs/templates/README.md).

## Chạy frontend

```bash
cd frontend
npm install
npm run dev                       # http://localhost:5173
```

Dev server tự proxy `/api` sang gateway `http://localhost:8080` (xem
`vite.config.js`), nên không cần `.env`. Chỉ copy `.env.example` thành `.env`
khi muốn đổi đích proxy (`VITE_DEV_API_TARGET`).

Hoặc chạy frontend bằng Docker (image tự build, phục vụ bằng nginx):

```bash
docker compose up -d --build frontend   # http://localhost:5173
```

Trong container, nginx vừa phục vụ file tĩnh (SPA fallback cho react-router)
vừa **reverse proxy `/api/*` sang API Gateway** — browser gọi cùng origin nên
không dính CORS. Đổi đích proxy bằng biến `API_GATEWAY_URL` trong `.env`
(runtime, **không cần build lại image**).

Trang chủ gọi `GET /api/auth/health` **qua gateway** — hiển thị "kết nối API thành công" khi gateway + auth-service đang chạy.

## Kiểm tra cài đặt thành công

| Đối tượng | Cách kiểm                                             | Kết quả mong đợi            |
| --------- | ----------------------------------------------------- | --------------------------- |
| Hạ tầng   | `docker compose ps`                                   | các container healthy       |
| Gateway   | `curl http://localhost:8080/actuator/health`          | `{"status":"UP"}`           |
| Auth      | `curl http://localhost:8081/api/auth/health`          | `{"status":"UP",...}`       |
| User      | `curl http://localhost:8082/api/users/health`         | `{"status":"UP",...}`       |
| User (cần token) | `curl http://localhost:8082/api/users/me`      | `401` khi chưa đăng nhập — đúng như thiết kế |
| Shop      | `curl http://localhost:8083/api/shops/health`         | `{"status":"UP",...}`       |
| Shop (cần token) | `curl http://localhost:8083/api/shops/me`      | `401` khi chưa đăng nhập — đúng như thiết kế |
| Qua gateway | `curl http://localhost:8080/api/auth/health`        | `{"status":"UP",...}`       |
| Frontend  | mở `http://localhost:5173`                            | "Kết nối API thành công"    |
| RabbitMQ  | mở `http://localhost:15672`                           | đăng nhập guest/guest       |
| Monitoring | mở `http://localhost:3001` (profile `monitoring`)   | dashboard hiện service UP  |

## Tài liệu

- [SRS — Đặc tả yêu cầu](docs/SRS-VMarket.md)
- [Quy ước git/commit/PR](CONTRIBUTING.md)
- [Template Dockerfile + CI/CD cho service mới](docs/templates/README.md)
- [README Auth Service](services/auth-service/README.md)
- [README Shop Service](services/shop-service/README.md)
- [README AI Search Service — FR-SRCH-01–05](services/ai-search-service/README.md)
- [OpenAPI AI Search](docs/openapi/ai-search-service.yaml)
- [Worklog PBL6-21 — nội dung PR và kiểm chứng](worklogs/PBL6-21.md)
- [Kết quả và giới hạn kiểm chứng AI Search](services/ai-search-service/VERIFICATION.md)
