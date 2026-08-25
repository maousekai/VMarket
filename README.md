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
│   ├── user-service/          # Hồ sơ, sổ địa chỉ (8082, PostgreSQL)
│   ├── shop-service/          # Gian hàng (8083, PostgreSQL)
│   ├── product-service/       # Danh mục, sản phẩm, tồn kho (8084, MongoDB)
│   ├── cart-service/          # Giỏ hàng (8085, Redis)
│   ├── order-service/         # Đơn hàng, trả hàng (8086, PostgreSQL)
│   ├── payment-service/       # Thanh toán PayOS/COD (8087, PostgreSQL)
│   ├── delivery-service/      # Giao hàng, định vị shipper (8088, PostgreSQL)
│   ├── review-service/        # Đánh giá sản phẩm (8089, PostgreSQL)
│   ├── notification-service/  # Thông báo, email, FCM (8090, MongoDB)
│   ├── ai-search-service/     # Tìm kiếm AI + tìm bằng ảnh CNN (8100, FastAPI + Elasticsearch)
│   ├── recommendation-service/# Gợi ý sản phẩm (8101, FastAPI + PostgreSQL + Redis)
│   └── chatbot-service/       # Chatbot RAG (8102, FastAPI + MongoDB)
├── frontend/                  # Web end-user (React + Vite, cổng 5173)
├── scripts/                   # Script quản lý dự án (vmarket.cmd)
├── infra/                     # Cấu hình hạ tầng (init script PostgreSQL...)
├── docs/                      # SRS và tài liệu dự án
├── docker-compose.yml         # Hạ tầng dùng chung cho dev
└── CONTRIBUTING.md            # Quy ước branch/commit/PR
```

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

```bash
# Bộ nhẹ mặc định (~1.5GB RAM): PostgreSQL + MongoDB + Redis + RabbitMQ
docker compose up -d

# Chỉ định lẻ khi cần
docker compose up -d postgres redis

# Elasticsearch (~1GB) chỉ bật khi làm AI Search
docker compose --profile search up -d

# Xoá sạch khi không dùng nữa
docker compose down -v
```

Mỗi container đã bị giới hạn RAM (`mem_limit`) để không chiếm hết máy. WSL2 cũng được giới hạn qua `~/.wslconfig` (8GB) — áp dụng sau khi `wsl --shutdown` rồi mở lại Docker Desktop.

Sau khi `docker compose up -d` lần đầu, PostgreSQL tự tạo đủ CSDL cho từng service: `vmarket_auth`, `vmarket_user`, `vmarket_shop`, `vmarket_order`, `vmarket_payment`, `vmarket_delivery`, `vmarket_review`, `vmarket_recommendation` (MongoDB/Redis tự khởi tạo khi dùng).

## Chạy một service (ví dụ Auth)

```bash
cd services/auth-service
..\mvnw.cmd spring-boot:run       # Windows (Maven Wrapper đặt ở services/)
# ../mvnw spring-boot:run         # macOS/Linux
```

Mỗi service đọc cấu hình từ biến môi trường (đã có giá trị dev mặc định khớp compose): `DB_HOST`, `DB_PORT`, `DB_NAME`, `RABBITMQ_HOST`...

> **Tối ưu khi dev local:** chỉ chạy **API Gateway + các service bạn đang làm**. Không cần bật hết 14 service.

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
scripts\vmarket.cmd infra-search           # bật thêm Elasticsearch
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

## Chạy frontend

```bash
cd frontend
npm install
copy .env.example .env            # VITE_API_BASE_URL=http://localhost:8080 (gateway)
npm run dev                       # http://localhost:5173
```

Trang chủ gọi `GET /api/auth/health` **qua gateway** — hiển thị "kết nối API thành công" khi gateway + auth-service đang chạy.

## Kiểm tra cài đặt thành công

| Đối tượng | Cách kiểm                                             | Kết quả mong đợi            |
| --------- | ----------------------------------------------------- | --------------------------- |
| Hạ tầng   | `docker compose ps`                                   | các container healthy       |
| Gateway   | `curl http://localhost:8080/actuator/health`          | `{"status":"UP"}`           |
| Auth      | `curl http://localhost:8081/api/auth/health`          | `{"status":"UP",...}`       |
| Qua gateway | `curl http://localhost:8080/api/auth/health`        | `{"status":"UP",...}`       |
| Frontend  | mở `http://localhost:5173`                            | "Kết nối API thành công"    |
| RabbitMQ  | mở `http://localhost:15672`                           | đăng nhập guest/guest       |

## Tài liệu

- [SRS — Đặc tả yêu cầu](docs/SRS-VMarket.md)
- [Quy ước git/commit/PR](CONTRIBUTING.md)
- [README Auth Service](services/auth-service/README.md)
