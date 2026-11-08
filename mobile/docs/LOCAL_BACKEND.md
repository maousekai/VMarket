# Chạy backend VMarket ở local (cho app mobile)

Hạ tầng bằng Docker, 11 service Java bằng `mvnw spring-boot:run`:
gateway `:8080`, auth `:18081`, user `:8082`, shop `:8083`, product `:8084`, cart `:8085`, order `:8086`,
payment `:8087`, delivery `:8088`, review `:8089`, notification `:8090`.

## Một lệnh

```powershell
docker compose up -d postgres mongo mongo-init-replica redis rabbitmq     # từ thư mục gốc repo
powershell -ExecutionPolicy Bypass -File scripts\start-backend-local.ps1  # log trong .logs\
```

Script này: tạo các database Postgres còn thiếu, đặt biến môi trường dùng chung (JWT secret, internal key,
DB/Mongo/Redis/RabbitMQ, CORS cho Expo web `:19006`, rate limit gateway 5000/phút chỉ cho dev, log UTF-8)
rồi mở mỗi service trong một cửa sổ thu nhỏ. Dừng: `scripts\vmarket.cmd stop` hoặc tắt các tiến trình Java.

> Auth-service chạy ở **18081** (script đặt `SERVER_PORT` riêng cho nó và `AUTH_SERVICE_URL` cho gateway,
> user, delivery, notification) vì 8081 hay bị Metro/dự án khác chiếm. Đổi bằng `VMARKET_AUTH_PORT`.
> Gateway đọc origin được phép từ **`APP_CORS_ALLOWED_ORIGINS`**; các service khác đọc `CORS_ALLOWED_ORIGINS`.
> Khởi động lại một service: dừng tiến trình đang giữ cổng rồi chạy lại đúng lệnh của nó (script có tham số
> `-Only` nhưng chưa ổn định khi gọi bằng `-File`; hãy chạy toàn bộ script hoặc tự mở service đó).

## Dữ liệu demo (chạy 1 lần, chờ ~1 phút giữa các lần xin OTP nếu bị `429 OTP_RESEND_TOO_SOON`)

```powershell
$env:DEV_ADMIN_PASSWORD = 'Admin@12345'; $env:SEED_PASSWORD = 'Seed@12345'
$env:SEED_AUTH_LOG_FILE = "$PWD\.logs\auth-service.log"   # đọc OTP từ log thay vì docker logs
node scripts\seed-demo-data\seed.mjs --skip-image-check         # 8 danh mục, 12 gian hàng, 96 sản phẩm
node scripts\seed-demo-data\seed-mobile-users.mjs               # buyer.demo + shipper.demo
```

Lưu ý khi seed:
- auth-service **chưa có consumer `ShopApproved`**, nên chủ shop không tự được cấp quyền SELLER. Với dữ liệu seed, cấp trực tiếp trong DB:
  `INSERT INTO user_roles(user_id, role_id) SELECT u.id, r.id FROM users u CROSS JOIN roles r WHERE u.email LIKE 'seller.%@seed.vmarket.test' AND r.name='SELLER' ON CONFLICT DO NOTHING;`
  (chạy lặp lại trong lúc seed chạy, trên database `vmarket_auth`).
- Ảnh seed dùng `loremflickr.com` (hay lỗi). Thay bằng ảnh placeholder trong Mongo nếu cần (`vmarket_product.products.imageUrls`).

## Gửi email thật bằng Brevo (OTP đăng ký / quên mật khẩu)

Mặc định `AUTH_EMAIL_PROVIDER=log`: mã OTP chỉ in ra log auth-service. Để gửi email thật qua Brevo, đặt trong `.env` (gốc repo) hoặc môi trường chạy auth-service:

```
AUTH_EMAIL_PROVIDER=brevo
BREVO_API_KEY=<API key Brevo của bạn>       # không commit key vào repo
AUTH_EMAIL_FROM=<địa chỉ gửi đã xác minh trên Brevo>
```

Brevo chỉ gửi được tới hộp thư thật: các tài khoản demo `*@vmarket.test` / `*@seed.vmarket.test` **không** nhận được email. Dùng `log` cho dữ liệu demo, `brevo` cho thử với email thật. Production bắt buộc dùng `brevo`.

## Đơn hàng thử nghiệm đến shipper

Buyer đặt đơn → seller xác nhận (`POST /api/orders/seller/{id}/confirm`) → seller bàn giao
(`POST /api/delivery/seller/orders/{id}/handoff`; nếu `409` ngay sau khi xác nhận là do giữ tồn kho bất đồng bộ, thử lại sau vài giây)
→ delivery-service tự gán cho shipper cùng tỉnh/quận.

## Gặp sự cố thường gặp

- **Docker Desktop báo `sailor-ingest.sock` / `engine.sock` ... "The file cannot be accessed by the system"**: socket cũ bị kẹt.
  Đóng Docker, đổi tên (không xóa được) `%LOCALAPPDATA%\Docker\run` và `%LOCALAPPDATA%\docker-secrets-engine`, mở lại Docker Desktop.
- **Gateway 404/nginx khi gọi `/api/auth/...`**: cổng auth bị tiến trình khác chiếm; xem `.logs\auth-service.log`.
- **delivery-service lỗi Flyway "no schema history"**: database `vmarket_delivery` có schema cũ không do Flyway tạo; xóa và tạo lại (database dev, không có dữ liệu).
