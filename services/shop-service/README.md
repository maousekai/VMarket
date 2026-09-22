# Shop Service (PBL6-14)

Vòng đời gian hàng: đăng ký (FR-SHOP-01), người bán quản lý gian hàng (FR-SHOP-02),
trang gian hàng công khai (FR-SHOP-03), Admin duyệt / từ chối / đình chỉ và phát sự
kiện `ShopApproved` / `ShopSuspended` (FR-SHOP-04).
Cổng `8083`, CSDL PostgreSQL `vmarket_shop` (bảng `shops`, `shop_status_history`).

## Vòng đời gian hàng

```
  (mới) ──nộp hồ sơ──► PENDING ────APPROVE────► ACTIVE
                        ▲    │                   │   ▲
               RESUBMIT │    │ REJECT    SUSPEND │   │ REINSTATE
                        │    ▼ (lý do)   (lý do) ▼   │
                       REJECTED                SUSPENDED
```

| Hành động | Ai | Từ → tới | Sự kiện Event Bus |
| --- | --- | --- | --- |
| Nộp hồ sơ | Buyer | — → `PENDING` | — |
| Gửi lại | Chủ gian hàng | `REJECTED` → `PENDING` | — |
| Duyệt | Admin | `PENDING` → `ACTIVE` | `ShopApproved` (`reinstated=false`) |
| Từ chối (bắt buộc lý do) | Admin | `PENDING` → `REJECTED` | — |
| Đình chỉ (bắt buộc lý do) | Admin | `ACTIVE` → `SUSPENDED` | `ShopSuspended` |
| Gỡ đình chỉ | Admin | `SUSPENDED` → `ACTIVE` | `ShopApproved` (`reinstated=true`) |

Mỗi hành động chỉ hợp lệ từ **đúng một** trạng thái (`ShopAction`), sai thì
`409 INVALID_STATUS_TRANSITION`. Ví dụ "duyệt" một gian hàng đang bị đình chỉ bị từ
chối — Admin phải chủ động "gỡ đình chỉ". Mọi bước (kể cả nộp hồ sơ) được ghi vào
`shop_status_history`: ai làm, lúc nào, lý do gì.

## API

| Method | Đường dẫn | Ai | Việc |
| --- | --- | --- | --- |
| GET | `/api/shops/health` | Mọi người | Health nghiệp vụ |
| GET | `/api/shops/{shopId}` | Mọi người | Trang gian hàng công khai — chỉ `ACTIVE`, còn lại 404 |
| POST | `/api/shops` | BUYER | Đăng ký gian hàng → `PENDING` |
| GET | `/api/shops/me` | Đã đăng nhập | Gian hàng của tôi (mọi trạng thái, kèm lý do từ chối/đình chỉ) |
| PUT | `/api/shops/me` | Đã đăng nhập | Cập nhật thông tin — **thay thế toàn bộ** |
| POST | `/api/shops/me/resubmit` | Đã đăng nhập | Gửi lại hồ sơ bị từ chối |
| GET | `/api/shops/me/status-history` | Đã đăng nhập | Lịch sử trạng thái của tôi |
| GET | `/api/shops/admin?status=&keyword=&page=&size=` | ADMIN | Danh sách (lọc trạng thái, tên), mới nộp trước |
| GET | `/api/shops/admin/{shopId}` | ADMIN | Chi tiết hồ sơ |
| GET | `/api/shops/admin/{shopId}/status-history` | ADMIN | Lịch sử trạng thái |
| POST | `/api/shops/admin/{shopId}/approve` | ADMIN | Duyệt |
| POST | `/api/shops/admin/{shopId}/reject` | ADMIN | Từ chối — body `{ "reason": "..." }` |
| POST | `/api/shops/admin/{shopId}/suspend` | ADMIN | Đình chỉ — body `{ "reason": "..." }` |
| POST | `/api/shops/admin/{shopId}/reinstate` | ADMIN | Gỡ đình chỉ |

Endpoint Admin nằm dưới `/api/shops/admin` (không phải `/api/admin/shops`) vì API
Gateway định tuyến theo tiền tố `/api/shops/**`.

Body `POST /api/shops` và `PUT /api/shops/me`:

| Trường | Bắt buộc | Ràng buộc |
| --- | :---: | --- |
| `name` | ✓ | 3–100 ký tự; **không trùng** (không phân biệt hoa thường / khoảng trắng thừa) |
| `description` | | ≤ 2000 ký tự |
| `logoUrl`, `coverUrl` | | URL `http`/`https`, ≤ 500 ký tự |
| `policies` | | Chính sách đổi trả / vận chuyển, ≤ 5000 ký tự |
| `contactEmail` | ✓ | Email hợp lệ |
| `contactPhone` | ✓ | Số di động Việt Nam: `0xxxxxxxxx` hoặc `+84xxxxxxxxx` |
| `province`, `district`, `ward`, `streetAddress` | ✓ | Địa chỉ kho / lấy hàng |

Swagger UI: <http://localhost:8083/swagger-ui.html> · spec: `/v3/api-docs`

Body lỗi mọi endpoint: `{ "error": { "code": "...", "message": "...", "details": [...] } }`.

| Mã lỗi | HTTP | Khi nào |
| --- | :---: | --- |
| `SHOP_ALREADY_EXISTS` | 409 | Tài khoản đã có gian hàng (mỗi tài khoản một gian hàng) |
| `SHOP_NAME_TAKEN` | 409 | Tên đã được gian hàng khác dùng |
| `SHOP_NOT_FOUND` | 404 | Chưa đăng ký / không tồn tại / (trang công khai) chưa hoạt động |
| `SHOP_SUSPENDED` | 409 | Người bán sửa gian hàng đang bị đình chỉ |
| `INVALID_STATUS_TRANSITION` | 409 | Hành động không hợp lệ với trạng thái hiện tại |
| `CONCURRENT_MODIFICATION` | 409 | Hai thao tác cùng sửa một gian hàng (vd Admin duyệt đúng lúc người bán lưu) — tải lại rồi thử lại |

### Những điều dễ hiểu nhầm

1. **`PUT /api/shops/me` thay thế toàn bộ hồ sơ.** Trường tuỳ chọn không gửi (mô tả,
   ảnh bìa, chính sách...) sẽ bị **xoá**. FE lấy hồ sơ bằng `GET /api/shops/me`, sửa
   trên object đó rồi `PUT` lại toàn bộ — cùng ngữ nghĩa với `PUT /api/users/me`.
2. **Sửa gian hàng đang hoạt động không phải chờ duyệt lại.** Chỉ hồ sơ mới / gửi lại
   mới qua kiểm duyệt. Gian hàng **bị đình chỉ thì không sửa được**.
3. **`/me/**` không đòi vai trò SELLER** — quyền ở đây là quyền sở hữu (`owner_id = sub`).
   Người vừa nộp hồ sơ chưa có SELLER mà vẫn phải xem / sửa được hồ sơ của mình. Vai trò
   SELLER do auth-service cấp khi nhận `ShopApproved`.
4. **Trang công khai không có thông tin liên hệ** (email, số điện thoại, địa chỉ kho,
   `ownerId`) — chỉ còn tỉnh/thành làm khu vực. Danh sách sản phẩm lấy ở Product Catalog
   (`GET /api/products?shopId=...`), điểm đánh giá ở Review Service.

## Sự kiện Event Bus

Phát lên exchange `vmarket.events` (routing key = tên sự kiện), payload định nghĩa ở
`shared-events` (`com.vmarket.events.ShopApproved` / `ShopSuspended`) — xem
[docs/event-bus.md](../../docs/event-bus.md#5-danh-mục-sự-kiện).

**Phát SAU KHI commit** (`@TransactionalEventListener(AFTER_COMMIT)`), không phát trong
transaction: phát trước commit mà commit thất bại (vd xung đột `@Version`) thì Auth đã cấp
SELLER cho một gian hàng chưa từng được duyệt — sự kiện "ma" không rút lại được.

Đổi lại là giao hàng **tối đa một lần**: RabbitMQ sập đúng lúc đó thì trạng thái đã lưu
nhưng sự kiện mất — có log `ERROR KHÔNG phát được ShopApproved cho gian hàng id=...` để tra
soát; Admin vẫn nhận 200 vì quyết định thật sự đã được lưu. Cùng mức với phần còn lại của
event bus (retry/DLQ để sau, `docs/event-bus.md` §7); muốn chắc chắn giao được thì nâng lên
transactional outbox.

## Xác thực

Tự verify access token HS256 bằng `AUTH_JWT_SECRET` (chung với auth-service), cùng quy tắc
với gateway và user-service: đúng chữ ký, `iss = auth-service`, chưa hết hạn (lệch đồng hồ
≤ 30s). Không tin header `X-User-*` vì cổng 8083 publish ra host.

Phân quyền theo URL ở `SecurityConfig`. **Thứ tự rule có chủ ý**: `GET /api/shops/*` (trang
công khai) cũng khớp `/api/shops/me` và `/api/shops/admin`, nên hai rule đó phải đứng trước —
test `xemGianHangCuaToi_khongCoToken_tra401_khongBiRuleCongKhaiNuot` và
`endpointAdmin_khongPhaiAdmin_tra403_khongCoToken_tra401` khoá lại thứ tự này.

## Chạy

```bash
docker compose up -d postgres rabbitmq
cd services/shop-service
..\mvnw.cmd spring-boot:run          # Windows; macOS/Linux: ../mvnw spring-boot:run
```

Hoặc container: `docker compose up -d --build shop-service` (gateway tự trỏ
`SHOP_SERVICE_URL=http://shop-service:8083`).

| Biến môi trường | Mặc định dev | Ghi chú |
| --- | --- | --- |
| `DB_HOST` / `DB_PORT` / `DB_NAME` | `localhost` / `5433` / `vmarket_shop` | |
| `AUTH_JWT_SECRET` | giá trị dev ở `application-dev.yml` | **Bắt buộc** ở prod, trùng auth-service |
| `RABBITMQ_HOST` / `RABBITMQ_PORT` | `localhost` / `5672` | |
| `RABBITMQ_CONNECTION_TIMEOUT` | `5s` | Giới hạn thời gian request của Admin bị giữ khi RabbitMQ không truy cập được |

Schema do Flyway quản lý (`db/migration`), Hibernate chỉ `validate`.

## Test

```bash
cd services
./mvnw -pl shop-service -am test
```

Chạy trên H2 (MODE=PostgreSQL), không cần PostgreSQL / RabbitMQ: `EventPublisher` được
mock để kiểm tra đúng sự kiện + payload được phát. `FlywayMigrationTest` chạy migration
thật rồi để Hibernate `validate` đối chiếu entity.

## Chưa làm trong PR này

- **FR-SHOP-05 Thống kê gian hàng (ưu tiên TB)** — doanh thu, số đơn theo trạng thái, sản
  phẩm bán chạy đều là dữ liệu của Order Service, hiện mới là skeleton và chưa phát
  `OrderPlaced` / `OrderStatusChanged`. Làm khi Order Service có dữ liệu (xem worklog PBL6-14).
- **Cấp vai trò SELLER khi nhận `ShopApproved`** thuộc auth-service (consumer), **ẩn/hiện
  sản phẩm** thuộc product-service — ticket của các service đó.
