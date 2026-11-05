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
| `PaymentFailed` | Buyer | ✓ | ✓ | |
| `OrderStatusChanged` | Buyer: PROCESSING / SHIPPED / DELIVERED / CANCELLED | ✓ | ✓ | ✓ |
| | Seller: CANCELLED (push) / DELIVERED (chỉ in-app) | ✓ | ✓/– | |
| `DeliveryAssigned` | Shipper (chuyến mới), Seller (chuẩn bị bàn giao) | ✓ | ✓ | |
| `DeliveryStatusChanged` | Buyer — chỉ `FAILED` (các trạng thái khác đã qua `OrderStatusChanged`) | ✓ | ✓ | |
| `ShopApproved` | Chủ gian hàng (duyệt / gỡ đình chỉ) | ✓ | ✓ | ✓ |
| `ShopSuspended` | Chủ gian hàng (kèm lý do) | ✓ | ✓ | ✓ |
| `ProductModerated` | Seller | ✓ | ✓ | |
| `ReturnResolved` | Buyer | ✓ | ✓ | ✓ |

Email xác thực tài khoản (OTP) vẫn do auth-service gửi đồng bộ — không đi qua Event Bus.

`ReviewCreated` chưa được tiêu thụ: payload hiện chỉ có `productId` (không có `shopId` / `sellerId`)
nên không xác định được Seller cần báo. Khi review-service (PBL6-22) bổ sung trường này, thêm consumer
và binding `ReviewCreated`.

### Tìm người nhận

- **Buyer / gian hàng của đơn**: `GET {ORDER_SERVICE_URL}/internal/delivery/orders/{id}` (khoá `X-Internal-Api-Key`).
- **Chủ gian hàng**: bản chiếu `shop_owners` (shopId → ownerId) dựng từ `ShopApproved` / `ShopSuspended`.
  Gian hàng được duyệt **trước** khi queue của Notification tồn tại sẽ chưa có trong bản chiếu → Seller
  của gian hàng đó chưa nhận thông báo đơn cho tới lần duyệt / đình chỉ / gỡ đình chỉ kế tiếp (có log cảnh báo).
- **Email**: `GET {AUTH_SERVICE_URL}/internal/users/{userId}/contact` (endpoint mới ở auth-service). Không gửi
  khi tài khoản bị khoá hoặc email chưa xác thực (`EmailLog.status = SKIPPED`).

### Queue

- `notification-service.events` — bind các sự kiện ở bảng trên (trừ `DeliveryAssigned`), DLQ `.dead`.
- `notification-service.delivery-assigned` — queue delivery-service đã khai báo sẵn để giữ `DeliveryAssigned`
  khi Notification chưa chạy; Notification tiêu thụ đúng queue này (khai báo lại cùng tham số).

### Chống trùng (at-least-once)

`dedupKey = eventId:recipientId:type` (unique index):

1. In-app đã tồn tại → bỏ qua in-app và push.
2. Email chống trùng riêng qua `email_logs`: đã `SENT` thì không gửi lại; lỗi provider → `FAILED` và ném lỗi để
   event bus retry (2 lần, backoff) rồi chuyển DLQ. Lần retry chỉ gửi lại email, không tạo thêm in-app / push.
3. Push là best-effort (mức ưu tiên TB): lỗi không làm hỏng sự kiện; token FCM báo không hợp lệ bị xoá.

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
| `INTERNAL_API_KEY` | dev placeholder (profile dev) | Trùng order-service + auth-service |
| `ORDER_SERVICE_URL` / `AUTH_SERVICE_URL` | `localhost:8086` / `localhost:8081` | |
| `NOTIFICATION_EMAIL_PROVIDER` | `log` | `smtp` (dùng `SMTP_*`) hoặc `brevo` (`BREVO_API_KEY`). Profile `prod` không cho phép `log` |
| `NOTIFICATION_PUSH_PROVIDER` | `log` | `fcm` cần `FCM_PROJECT_ID` + `FCM_CREDENTIALS_FILE` (JSON service account, không commit) |

Chạy local: `docker compose up -d` (Mongo + RabbitMQ), rồi `cd services/notification-service && ..\mvnw.cmd spring-boot:run`.
