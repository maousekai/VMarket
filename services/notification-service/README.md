# notification-service (PBL6-23)

Lắng nghe sự kiện nghiệp vụ trên Event Bus rồi gửi thông báo tới đúng người nhận qua 3 kênh:

| Yêu cầu | Kênh | Ghi chú |
|---|---|---|
| FR-NOTI-01 | Thông báo trong ứng dụng | MongoDB `notifications`, trung tâm thông báo có đánh dấu đã đọc |
| FR-NOTI-02 | Email giao dịch | `log` (dev) / `smtp` / `brevo`, nhật ký ở `email_logs` |
| FR-NOTI-03 | Push di động | FCM HTTP v1, token thiết bị ở `device_tokens` |

Port 8090, gateway định tuyến `/api/notifications/**`.

## Sự kiện → thông báo

| Sự kiện | Người nhận | In-app | Push | Email |
|---|---|:-:|:-:|:-:|
| `OrderPlaced` | Buyer (xác nhận đặt hàng) | ✓ | ✓ | ✓ |
| | Seller — chỉ đơn COD (đơn online chờ `PaymentSucceeded`) | ✓ | ✓ | |
| `PaymentSucceeded` | Buyer | ✓ | ✓ | ✓ |
| | Seller (đơn mới đã thanh toán) | ✓ | ✓ | |
| `PaymentFailed` | Buyer (mã `EXPIRED` / `CANCELLED` / `RETRYABLE` được dịch sang câu tiếng Việt, mã lạ bị ẩn) | ✓ | ✓ | |
| `OrderStatusChanged` | Buyer: PROCESSING / SHIPPED / DELIVERED / CANCELLED | ✓ | ✓ | ✓ |
| | Seller: CANCELLED (push) / DELIVERED (chỉ in-app) | ✓ | ✓/– | |
| `DeliveryAssigned` | Shipper (chuyến mới), Seller (chuẩn bị bàn giao) | ✓ | ✓ | |
| `DeliveryStatusChanged` | Buyer — chỉ `FAILED` (các trạng thái khác đã qua `OrderStatusChanged`) | ✓ | ✓ | |
| `ShopApproved` | Chủ gian hàng (duyệt / gỡ đình chỉ) | ✓ | ✓ | ✓ |
| `ShopSuspended` | Chủ gian hàng (kèm lý do) | ✓ | ✓ | ✓ |
| `ProductModerated` | Seller | ✓ | ✓ | |
| `ReturnResolved` | Buyer | ✓ | ✓ | ✓ |

Email xác thực tài khoản (OTP) vẫn do auth-service gửi đồng bộ — không đi qua Event Bus.

Số tiền hiển thị là `totalAmount` (VND, `BigDecimal`) từ order-service; snapshot chưa có trường currency nên ký hiệu
`₫` đang cố định. Thiếu số tiền thì câu thông báo bỏ phần số tiền thay vì hiện "0 ₫".

`ReviewCreated` chưa được tiêu thụ: payload hiện chỉ có `productId` (không có `shopId` / `sellerId`)
nên không xác định được Seller cần báo. Khi review-service (PBL6-22) bổ sung trường này, thêm consumer
và binding `ReviewCreated`.

### Tìm người nhận

Mọi API nội bộ dùng header `X-Internal-Api-Key` (= `INTERNAL_API_KEY`), timeout 2s kết nối / 5s đọc.

- **Buyer / gian hàng của đơn**: `GET {ORDER_SERVICE_URL}/internal/notification/orders/{id}` — endpoint chỉ đọc,
  **không** `SELECT ... FOR UPDATE` (khác `/internal/delivery/orders/{id}` của Delivery).
- **Chủ gian hàng**: bản chiếu `shop_owners` (shopId → ownerId) dựng từ `ShopApproved` / `ShopSuspended`.
  Chưa có trong bản chiếu (gian hàng duyệt trước khi Notification chạy) → tra
  `GET {SHOP_SERVICE_URL}/internal/shops/{shopId}/owner` rồi lưu cache (`eventTimestamp = 0` để sự kiện sau ghi đè).
- **Email**: `GET {AUTH_SERVICE_URL}/internal/users/{userId}/contact` — chỉ trả `email`, `emailVerified`, `suspended`.
  Không gửi khi tài khoản bị khoá hoặc email chưa xác thực (`EmailLog.status = SKIPPED`).

### Queue

- `notification-service.events` — bind các sự kiện ở bảng trên (trừ `DeliveryAssigned`), DLQ `.dead`.
- `notification-service.delivery-assigned` — queue delivery-service đã khai báo sẵn để giữ `DeliveryAssigned`
  khi Notification chưa chạy; Notification tiêu thụ đúng queue này (khai báo lại cùng tham số).

### Chống trùng (at-least-once)

`dedupKey = eventId:recipientId:type[:shopId]` (unique index) — thông báo theo gian hàng có thêm `shopId` để chủ
của hai gian hàng trong cùng một đơn nhận đủ hai thông báo.

1. In-app đã tồn tại → bỏ qua in-app và push.
2. Email: **claim trước khi gửi** — insert `email_logs` trạng thái `SENDING` (unique `dedupKey`); bản ghi đã có thì chỉ
   giành lại được bằng cập nhật có điều kiện khi `FAILED` hoặc `SENDING` quá 1 phút (tiến trình trước chết giữa chừng).
   Không giành được → không gửi. Lỗi provider → `FAILED` và ném lỗi để event bus retry (2 lần, backoff) rồi DLQ.
3. Mỗi người nhận xử lý độc lập: email buyer lỗi không chặn thông báo cho seller; lỗi đầu tiên được ném ra sau khi
   đã xử lý hết danh sách, lần retry bỏ qua người đã xong.
4. Push là best-effort (mức ưu tiên TB): lỗi không làm hỏng sự kiện. Chỉ xoá token khi FCM trả 404 hoặc
   `errorCode = UNREGISTERED` (400 `INVALID_ARGUMENT` có thể do payload nên giữ token).

Giới hạn: tiến trình chết sau khi provider đã nhận email nhưng trước khi ghi `SENT` thì lần giành lại sau 1 phút
sẽ gửi thêm một lần (ưu tiên không mất email).

Khoá chính của `notifications`, `email_logs`, `device_tokens` là ULID (`ulid-creator`, giống auth-service).

## REST API (cần access token)

| Method | Path | Mô tả |
|---|---|---|
| GET | `/api/notifications?page=0&size=20&unreadOnly=false` | Danh sách thông báo của tôi (mới nhất trước, `size` ≤ 100) |
| GET | `/api/notifications/unread-count` | `{ "count": n }` |
| PATCH | `/api/notifications/{id}/read` | Đánh dấu đã đọc (404 nếu không phải của tôi) |
| PATCH | `/api/notifications/read-all` | `{ "updated": n }` |
| POST | `/api/notifications/devices` | `{ "token": "<FCM token>", "platform": "ANDROID|IOS|WEB" }` → 204 |
| DELETE | `/api/notifications/devices` | `{ "token": "..." }` → 204 (gọi khi đăng xuất) |
| GET | `/api/notifications/health` | Công khai |

## Cấu hình

Xem `.env.example` / `.env.prod.example`. Biến chính:

| Biến | Mặc định | Ghi chú |
|---|---|---|
| `AUTH_JWT_SECRET` | dev placeholder (profile dev) | Trùng auth-service |
| `INTERNAL_API_KEY` | dev placeholder (profile dev) | Trùng order-service, auth-service, shop-service |
| `ORDER_SERVICE_URL` / `AUTH_SERVICE_URL` / `SHOP_SERVICE_URL` | `localhost:8086` / `8081` / `8083` | |
| `NOTIFICATION_EMAIL_PROVIDER` | `log` | `smtp` (dùng `SMTP_*`) hoặc `brevo` (`BREVO_API_KEY`). Profile `prod` không cho phép `log` |
| `NOTIFICATION_PUSH_PROVIDER` | `log` | `fcm` cần `FCM_PROJECT_ID` + `FCM_CREDENTIALS_FILE` (JSON service account, không commit) |

## Chạy local

Giống order / payment / delivery, service chạy bằng Maven (chưa có block trong `docker-compose.yml`; gateway
định tuyến `/api/notifications/**` tới `NOTIFICATION_SERVICE_URL`, mặc định `localhost:8090`).

```bash
docker compose up -d                       # Mongo (27018) + RabbitMQ + Postgres
scripts\vmarket.cmd core                   # gateway + auth + user
scripts\vmarket.cmd service shop-service
scripts\vmarket.cmd service order-service
scripts\vmarket.cmd service notification-service   # hoặc: cd services/notification-service && ..\mvnw.cmd spring-boot:run
```

Profile `dev` có sẵn giá trị dev cho `AUTH_JWT_SECRET` / `INTERNAL_API_KEY`, email và push ở chế độ `log`
(nội dung email/push in ra console). Kiểm tra: `GET http://localhost:8080/api/notifications` với access token.

## Kiểm thử

```bash
cd services && ./mvnw -pl notification-service test
```

`NotificationMongoIntegrationTest` chạy MongoDB thật bằng Testcontainers (unique index, claim email đồng thời,
retry sau lỗi) và tự bỏ qua khi không có Docker. Docker Engine 29+ yêu cầu API ≥ 1.44 trong khi Testcontainers
1.21.3 mặc định dùng API cũ hơn — nếu test bị skip dù Docker đang chạy, đặt `DOCKER_API_VERSION=1.44`.
