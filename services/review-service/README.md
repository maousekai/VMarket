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

Profile `dev` có sẵn `AUTH_JWT_SECRET` giả. Profile `prod` bắt buộc `AUTH_JWT_SECRET`,
`ORDER_SERVICE_URL`, `SHOP_SERVICE_URL` (xem `.env.prod.example`).

Swagger UI: `http://localhost:8089/swagger-ui.html`.

## API

| Method | Path | Quyền | Mô tả |
|---|---|---|---|
| POST | `/api/reviews` | BUYER | FR-REV-01: viết đánh giá 1–5 sao, tối đa 5 ảnh |
| GET | `/api/reviews/me?orderId=` | BUYER | Đánh giá của tôi (lọc theo đơn) |
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

`page` bắt đầu từ 0, `size` tối đa 50.

## Quy tắc nghiệp vụ

- **Điều kiện đánh giá:** Review Service gọi `GET /api/orders/{orderId}` của Order Service
  bằng chính token người mua — đơn phải thuộc về họ, ở trạng thái `DELIVERED` (Hoàn thành)
  và chứa sản phẩm được đánh giá. `shopId`/`variantId` lấy từ dòng hàng trong đơn, không
  tin dữ liệu client gửi.
- **Mỗi sản phẩm trong đơn chỉ đánh giá một lần:** ràng buộc `UNIQUE (order_id, product_id)`
  ở DB, request trùng (kể cả đồng thời) nhận `409 REVIEW_ALREADY_EXISTS`.
- **Điểm tổng hợp** tính từ các đánh giá `VISIBLE` bằng `group by rating`, làm tròn 2 chữ số.
  Đánh giá bị admin ẩn không hiển thị và không tính điểm; seller không phản hồi được.
- **Phản hồi của seller:** gian hàng lấy từ `GET /api/shops/me` bằng token seller; đánh giá
  của shop khác trả 404. Mỗi đánh giá tối đa một phản hồi.

## Sự kiện

`ReviewCreated(reviewId, productId, ratingAverage, ratingCount)` phát qua outbox
(`review_outbox`) khi có đánh giá mới và khi admin ẩn/khôi phục. Aggregate được tính
**lúc phát** nên bản tin sau cùng luôn phản ánh dữ liệu đã commit mới nhất; payload là
giá trị tuyệt đối nên consumer (Product Service) xử lý lặp vẫn đúng. Phát lỗi được thử lại
với backoff luỹ thừa (tối đa 5 phút), quá 10 lần chuyển `DEAD`.

## Mã lỗi chính

| HTTP | Code | Khi nào |
|---|---|---|
| 404 | `ORDER_NOT_FOUND` | Đơn không tồn tại hoặc không thuộc người mua |
| 422 | `ORDER_NOT_COMPLETED` | Đơn chưa ở trạng thái hoàn thành |
| 422 | `PRODUCT_NOT_IN_ORDER` | Sản phẩm không có trong đơn |
| 409 | `REVIEW_ALREADY_EXISTS` | Sản phẩm trong đơn đã được đánh giá |
| 409 | `REVIEW_HIDDEN` | Seller phản hồi đánh giá đã bị ẩn |
| 403 | `SHOP_NOT_FOUND` | Seller chưa có gian hàng |
| 503 | `DEPENDENCY_UNAVAILABLE` | Order/Shop Service không phản hồi |

## Test

```bash
cd services
./mvnw -pl review-service -am test
```

Test chạy trên H2 (PostgreSQL mode) với migration Flyway thật; Order/Shop Service và
RabbitMQ được mock.
