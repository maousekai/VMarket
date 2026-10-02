# Product Catalog Service (PBL6-15)

Quản lý danh mục sản phẩm, thương hiệu, sản phẩm và tồn kho. Service chạy cổng
`8084`, lưu dữ liệu trong MongoDB `vmarket_product` và trao đổi sự kiện qua RabbitMQ.

## Công nghệ

- Java 17, Spring Boot 4.x, Spring MVC, Validation, Spring Data MongoDB
- MongoDB replica set — cần cho transaction khi cập nhật sản phẩm và tồn kho
- RabbitMQ + transactional outbox cho sự kiện catalog
- springdoc-openapi 3.x (Swagger UI), Spring Boot Actuator
- Maven Wrapper dùng chung đặt trong `services/`

## Chạy local

Khởi động MongoDB và RabbitMQ từ thư mục gốc repo:

```bash
docker compose up -d mongo rabbitmq
```

MongoDB trong Compose tự khởi tạo replica set `rs0`; cổng host là `27018`.
Sau đó chạy service:

```bash
cd services/product-service
..\mvnw.cmd spring-boot:run       # Windows
# ../mvnw spring-boot:run         # macOS/Linux
```

Service chạy tại `http://localhost:8084`. Profile `dev` được dùng mặc định và
đọc cấu hình dev mặc định từ `src/main/resources/application.yml`.

## API

Swagger UI: <http://localhost:8084/swagger-ui.html> · spec: `/v3/api-docs`

| Method | Đường dẫn | Ai | Việc |
| --- | --- | --- | --- |
| GET | `/api/products/health` | Mọi người | Health nghiệp vụ |
| GET | `/api/products` | Mọi người | Tìm kiếm, lọc, sắp xếp và phân trang sản phẩm |
| GET | `/api/products/{id}` | Mọi người | Chi tiết và tối đa 6 sản phẩm tương tự |
| POST | `/api/products` | SELLER | Tạo sản phẩm; cần `Idempotency-Key` |
| PUT | `/api/products/{id}` | Chủ sản phẩm | Cập nhật toàn bộ; cần `Idempotency-Key` |
| DELETE | `/api/products/{id}` | Chủ sản phẩm | Ẩn sản phẩm (xóa mềm); cần `Idempotency-Key` |
| POST | `/api/products/seller/query` | SELLER | Sản phẩm của người bán hiện tại |
| POST | `/api/products/admin/query` | ADMIN | Danh sách sản phẩm chưa xóa |
| PATCH | `/api/products/{id}/moderation` | ADMIN | Gỡ / khôi phục sản phẩm qua kiểm duyệt; cần `Idempotency-Key` |
| POST | `/api/products/{id}/moderation/resubmit` | SELLER sở hữu | Gửi sản phẩm đã sửa để kiểm duyệt lại; cần `Idempotency-Key` |
| GET | `/api/products/categories` | Mọi người | Cây danh mục đang hiển thị |
| POST | `/api/products/categories/admin/query` | ADMIN | Cây gồm cả danh mục ẩn |
| POST | `/api/products/categories` | ADMIN | Tạo danh mục |
| PUT | `/api/products/categories/{id}` | ADMIN | Cập nhật danh mục |
| PATCH | `/api/products/categories/{id}/visibility` | ADMIN | Ẩn / hiện danh mục |
| DELETE | `/api/products/categories/{id}` | ADMIN | Xóa danh mục nếu không còn được dùng |
| GET | `/api/products/brands` | Mọi người | Danh sách thương hiệu đang hiển thị |
| POST | `/api/products/brands/admin/query` | ADMIN | Danh sách gồm cả thương hiệu ẩn |
| POST | `/api/products/brands` | ADMIN | Tạo thương hiệu |
| PUT | `/api/products/brands/{id}` | ADMIN | Cập nhật thương hiệu |
| DELETE | `/api/products/brands/{id}` | ADMIN | Xóa thương hiệu nếu không còn được dùng |

Các bộ lọc của `GET /api/products`: `q`, `categoryId`, `shopId`, `minPrice`,
`maxPrice`, `minRating`, `sort`, `page`, `size`. Mặc định sắp xếp `NEWEST`,
`page=0`, `size=20`; kích thước tối đa 100.

Body tạo / cập nhật sản phẩm gồm `shopId`, `name`, `description`, `imageUrls`,
`categoryId`, `brandId`, `status`, `currency`, `variants`. Tiền tệ hiện chỉ nhận
`VND`; mỗi biến thể có SKU, thuộc tính, giá dương và tồn kho không âm. Khi PUT,
các biến thể không gửi sẽ bị xóa khỏi catalog; biến thể đã bán được giữ dưới dạng
ẩn để vẫn xử lý được hủy đơn / hoàn hàng, còn biến thể đang giữ chỗ không thể xóa.

Body lỗi dùng envelope chung của repo:
`{ "error": { "code": "...", "message": "..." } }` (có thêm `timestamp`,
`status`, `path`, và `fields` khi có lỗi validation). Lỗi thường gặp gồm
`PRODUCT_NOT_FOUND` (404), `SHOP_NOT_READY` / `SHOP_SUSPENDED` (409),
`INSUFFICIENT_STOCK` (409), `CONCURRENT_MODIFICATION` (409), và
`VALIDATION_FAILED` (400).

## API nội bộ

Order Service gọi các thao tác giữ / chốt / trả tồn kho bằng header
`X-Internal-Api-Key` khớp `INTERNAL_API_KEY`:

| Method | Đường dẫn | Việc |
| --- | --- | --- |
| POST | `/api/products/inventory/reservations` | Giữ tồn kho theo `orderId` và danh sách sản phẩm / biến thể / số lượng |
| POST | `/api/products/inventory/reservations/{orderId}/confirm` | Chốt đơn, trừ tồn kho |
| DELETE | `/api/products/inventory/reservations/{orderId}` | Hủy giữ chỗ hoặc hoàn tồn kho |
| POST | `/api/products/internal/shop-access/reconcile` | Đồng bộ snapshot trạng thái gian hàng theo lô (tối đa 500) |

`INTERNAL_API_KEY` phải giống khóa mà Order Service dùng khi gọi API tồn kho.
Gateway không nên công khai các endpoint nội bộ này.

## Sự kiện Event Bus

Consumer nhận `OrderPlaced`, `OrderStatusChanged`, `PaymentSucceeded`,
`ReturnResolved`, `ShopApproved`, `ShopSuspended`, `ReviewCreated` trên queue
`product.events.v2`. Sự kiện sản phẩm phát lên exchange `vmarket.events` qua
transactional outbox. Outbox thử lại tới `OUTBOX_MAX_ATTEMPTS` lần rồi giữ event
ở trạng thái `DEAD` để xử lý thủ công; xem [docs/event-bus.md](../../docs/event-bus.md).

Khi triển khai consumer mới, dùng quy trình chuyển queue trong tài liệu Event Bus
để drain queue `product.events` cũ trước khi xóa. Consumer AI Search cũng dùng
queue versioned `ai-search.events.v2`.

## Cấu hình

| Biến môi trường | Mặc định dev | Ý nghĩa |
| --- | --- | --- |
| `SPRING_PROFILES_ACTIVE` | `dev` | Profile cấu hình; production dùng `prod` |
| `SERVER_PORT` | `8084` | Cổng HTTP |
| `MONGO_HOST` / `MONGO_PORT` | `localhost` / `27018` | Mongo host / cổng publish trên máy dev |
| `DB_NAME` | `vmarket_product` | Tên database |
| `MONGO_REPLICA_SET` | `rs0` | Replica set dùng cho transaction |
| `MONGO_DIRECT_CONNECTION` | `true` | Kết nối Mongo local; production mặc định `false` |
| `RABBITMQ_HOST` / `RABBITMQ_PORT` | `localhost` / `5672` | RabbitMQ |
| `RABBITMQ_USERNAME` / `RABBITMQ_PASSWORD` | `guest` / `guest` | Tài khoản RabbitMQ dev |
| `INTERNAL_API_KEY` | Giá trị dev trong `.env.example` | Khóa gọi API nội bộ; production bắt buộc set |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:5173,http://localhost:5174,http://localhost:3000` | Các origin frontend được phép |
| `EVENTS_LISTEN` | `true` | Bật consumer RabbitMQ |
| `EVENTS_QUEUE` | `product.events.v2` | Queue nhận sự kiện |
| `OUTBOX_ENABLED` | `true` | Bật dispatcher transactional outbox |
| `OUTBOX_POLL_INTERVAL_MS` | `1000` | Chu kỳ quét outbox |
| `OUTBOX_BATCH_SIZE` | `50` | Số event tối đa mỗi lượt |
| `OUTBOX_MAX_ATTEMPTS` | `10` | Số lần thử trước khi chuyển sang `DEAD` |
| `OUTBOX_CONFIRM_TIMEOUT_SECONDS` | `10` | Thời gian chờ broker confirm |
| `CATALOG_MIGRATION_ENABLED` | `true` | Bật migration / đối soát catalog |
| `CATALOG_RECONCILE_INTERVAL_MS` | `3600000` | Chu kỳ đối soát catalog |

Mẫu biến môi trường riêng cho chạy container / IDE:
`services/product-service/.env.example` và `.env.prod.example`. Hai file này không
được Docker Compose đọc; Compose dùng `.env` ở thư mục gốc.

## Chạy bằng Docker

Build context phải là thư mục gốc repo để Dockerfile dùng parent POM:

```bash
docker build -f services/product-service/Dockerfile -t vmarket-product-service .
docker compose up -d --build product-service
```

## Test

```bash
cd services
./mvnw -pl product-service -am test      # macOS/Linux
.\mvnw.cmd -pl product-service -am test  # Windows: chạy từ services/
```

Unit / web tests dùng mock hoặc Mongo test support; `ProductInfrastructureIntegrationTest`
khởi chạy MongoDB replica set và RabbitMQ bằng Testcontainers.
