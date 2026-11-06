# Review Service (PBL6-22)

Đánh giá sản phẩm đã mua, tổng hợp điểm và phản hồi của người bán. Service chạy cổng
`8089`, lưu dữ liệu trong PostgreSQL `vmarket_review` (schema quản lý bằng Flyway) và
phát sự kiện `ReviewCreated` qua RabbitMQ.

## Chạy local

```bash
docker compose up -d postgres rabbitmq     # từ thư mục gốc repo
cd services/review-service
../mvnw spring-boot:run                    # Windows: ..\mvnw.cmd spring-boot:run
```

Profile `dev` có sẵn `AUTH_JWT_SECRET` giả và `REVIEW_IMAGE_ORIGINS=http://localhost:9000`
(MinIO của compose). Profile `prod` bắt buộc `AUTH_JWT_SECRET`, `ORDER_SERVICE_URL`,
`SHOP_SERVICE_URL`, `REVIEW_IMAGE_ORIGINS` (xem `.env.prod.example`).

Swagger UI: `http://localhost:8089/swagger-ui.html`.

## API

| Method | Path | Quyền | Mô tả |
|---|---|---|---|
| POST | `/api/reviews` | BUYER | FR-REV-01: viết đánh giá 1–5 sao, tối đa 5 ảnh |
| GET | `/api/reviews/me?orderId=&page=&size=` | BUYER | Đánh giá của tôi (lọc theo đơn) |
| GET | `/api/reviews/products/{productId}?rating=&page=&size=` | Công khai | Đánh giá của sản phẩm |
| GET | `/api/reviews/products/{productId}/summary` | Công khai | FR-REV-02: điểm TB, số lượt, phân bố sao |
| GET | `/api/reviews/shops/{shopId}` | Công khai | Đánh giá của gian hàng |
| GET | `/api/reviews/shops/{shopId}/summary` | Công khai | Điểm tổng hợp của gian hàng |
| GET | `/api/reviews/seller?status=&rating=` | SELLER | Đánh giá thuộc gian hàng của tôi |
| PUT | `/api/reviews/seller/{reviewId}/reply` | SELLER | FR-REV-03: tạo/sửa phản hồi |
| DELETE | `/api/reviews/seller/{reviewId}/reply` | SELLER | Xoá phản hồi |
| GET | `/api/reviews/admin?status=&productId=&shopId=` | ADMIN | Tìm đánh giá để kiểm duyệt |
| POST | `/api/reviews/admin/{reviewId}/hide` | ADMIN | FR-REV-04: ẩn đánh giá (bắt buộc lý do) |
| POST | `/api/reviews/admin/{reviewId}/restore` | ADMIN | Khôi phục đánh giá đã ẩn |
| GET | `/api/reviews/admin/outbox/status` | ADMIN | Số sự kiện đang chờ / DEAD |
| GET | `/api/reviews/admin/outbox/dead` | ADMIN | Danh sách sự kiện DEAD |
| POST | `/api/reviews/admin/outbox/{id}/replay` | ADMIN | Phát lại một sự kiện DEAD (bắt buộc lý do) |
| POST | `/api/reviews/admin/outbox/replay-dead` | ADMIN | Phát lại toàn bộ sự kiện DEAD |

`page` bắt đầu từ 0, `size` tối đa 50. API Gateway mở công khai (không cần token) cho
`GET /api/reviews/products/**` và `GET /api/reviews/shops/**`; các đường còn lại cần đăng nhập.

**Dữ liệu trả về theo người xem:** danh sách công khai không có `buyerId`, `orderId`,
`variantId` (không thu thập được id nội bộ hay liên kết người dùng với hàng đã mua); seller
thấy `orderId` nhưng không có `buyerId`; admin và chính người viết thấy đầy đủ.

## Quy tắc nghiệp vụ

- **Điều kiện đánh giá:** Review Service gọi `GET /api/orders/{orderId}` của Order Service
  bằng chính token người mua — đơn phải thuộc về họ, ở trạng thái `DELIVERED` (Hoàn thành)
  và chứa sản phẩm được đánh giá. `shopId`/`variantId` lấy từ dòng hàng trong đơn, không
  tin dữ liệu client gửi. Quyền sở hữu đơn được kiểm tra trước khi kiểm tra trùng, nên người
  khác đoán đúng `orderId` chỉ nhận 404.
- **"Hoàn thành" = `DELIVERED`:** SRS FR-REV-01 yêu cầu đơn "Hoàn thành", nhưng Order Service
  hiện chưa có trạng thái hoàn thành riêng (hay tự chuyển sau N ngày) nên tạm dùng `DELIVERED`
  (`ReviewService.COMPLETED_STATUS`). Đổi lại khi Order Service bổ sung trạng thái này.
- **Ảnh:** tối đa 5, URL phải thuộc một origin trong `REVIEW_IMAGE_ORIGINS` (khớp chính xác
  scheme + host + port); ngoài localhost bắt buộc https — sai cấu hình thì service không khởi động.
- **Mỗi sản phẩm trong đơn chỉ đánh giá một lần:** ràng buộc `UNIQUE (order_id, product_id)`
  ở DB, request trùng (kể cả đồng thời) nhận `409 REVIEW_ALREADY_EXISTS`.
- **Điểm tổng hợp** tính từ các đánh giá `VISIBLE` bằng `group by rating`, làm tròn 2 chữ số.
  Đánh giá bị admin ẩn không hiển thị và không tính điểm; seller không phản hồi được.
- **Phản hồi của seller:** gian hàng lấy từ `GET /api/shops/me` bằng token seller; đánh giá
  của shop khác trả 404. Chỉ shop `ACTIVE` mới được phản hồi/xoá phản hồi (shop đình chỉ, bị từ
  chối, chờ duyệt vẫn xem được danh sách). Mỗi đánh giá tối đa một phản hồi.
- **Điểm gian hàng:** SRS FR-REV-02 yêu cầu điểm cho cả sản phẩm và gian hàng. Điểm sản phẩm được
  đồng bộ sang Product Service qua `ReviewCreated`; điểm gian hàng **chỉ tính on-the-fly** qua
  `GET /api/reviews/shops/{shopId}/summary` (chưa có sự kiện riêng vì chưa service nào cần lưu).

## Sự kiện

`ReviewCreated(reviewId, productId, ratingAverage, ratingCount)` phát qua outbox
(`review_outbox`) khi có đánh giá mới **và khi admin ẩn/khôi phục** — tên sự kiện giữ nguyên vì
Product Service đã consume; ý nghĩa thực tế là "aggregate điểm của sản phẩm đã đổi". Payload là
giá trị tuyệt đối nên consumer xử lý lặp vẫn đúng.

- **Nhiều replica:** dòng outbox được claim bằng `FOR UPDATE SKIP LOCKED`, sau đó publisher
  giữ khoá dòng `product_rating_locks` của sản phẩm rồi mới tính aggregate và publish. Hai
  replica xử lý cùng sản phẩm vì vậy chạy tuần tự, bản tin publish sau luôn mới hơn.
- **Lỗi phát:** thử lại với backoff luỹ thừa (5s → tối đa 5 phút), `last_error` lưu tên lỗi
  + message; quá 10 lần chuyển `DEAD`. Metric `vmarket.review.outbox.dead` / `.pending` /
  `.oldest.dead.age` xuất qua `/actuator/prometheus`, có dòng DEAD thì log `REVIEW_OUTBOX_DEAD`
  mỗi phút. Admin phát lại qua `/api/reviews/admin/outbox/...`; khi phát lại, aggregate được tính
  mới nên không làm điểm lùi về giá trị cũ.
- Transaction và khoá được giữ trong lúc chờ broker confirm (tuần tự, tối đa 100 dòng/lượt) —
  chấp nhận được với lưu lượng thấp, khoá chỉ chặn publisher khác của cùng sản phẩm.

## Mã lỗi chính

| HTTP | Code | Khi nào |
|---|---|---|
| 404 | `ORDER_NOT_FOUND` | Đơn không tồn tại hoặc không thuộc người mua |
| 422 | `ORDER_NOT_COMPLETED` | Đơn chưa ở trạng thái hoàn thành |
| 422 | `PRODUCT_NOT_IN_ORDER` | Sản phẩm không có trong đơn |
| 409 | `REVIEW_ALREADY_EXISTS` | Sản phẩm trong đơn đã được đánh giá |
| 409 | `REVIEW_HIDDEN` | Seller phản hồi đánh giá đã bị ẩn |
| 403 | `SHOP_NOT_FOUND` | Seller chưa có gian hàng |
| 403 | `SHOP_NOT_ACTIVE` | Gian hàng chưa hoạt động / bị đình chỉ |
| 400 | `IMAGE_URL_NOT_ALLOWED` | URL ảnh không thuộc kho ảnh cho phép |
| 503 | `DEPENDENCY_UNAVAILABLE` | Order/Shop Service không phản hồi |

## Test

```bash
cd services
./mvnw -pl review-service -am test
```

Test chạy trên H2 (PostgreSQL mode) với migration Flyway thật; Order/Shop Service và
RabbitMQ được mock. `ReviewConcurrencyPostgresTest` chạy các case race trên PostgreSQL thật bằng
Testcontainers (tạo đánh giá trùng đồng thời, ẩn đồng thời, hai publisher cùng sản phẩm, skip
locked) và tự bỏ qua khi máy không có Docker. `src/test/resources/docker-java.properties` ghim
`api.version=1.44` vì Docker Engine 29+ từ chối API mặc định của Testcontainers 1.21.
