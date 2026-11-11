# Payment Service — PBL6-19

FR-PAY-01..05: thanh toán online qua VNPay Sandbox (chuyển hướng sang trang VNPay, chữ ký
HMAC-SHA512, IPN + return URL), ghi nhận COD, lịch sử giao dịch, hạn thanh toán 15 phút và
hoàn tiền do Admin xác nhận thủ công. Cổng 8087, PostgreSQL.

## Cấu hình

Sao chép `.env.example` thành `.env` (file này đã bị gitignore, không commit). Khi chạy từ thư mục
service (`scripts\vmarket.cmd service payment-service`, hoặc `..\mvnw.cmd spring-boot:run`),
`application.yml` tự nạp `services/payment-service/.env` nhờ
`spring.config.import: optional:file:.env[.properties]`. File không có thì bỏ qua, và profile test
không dùng file này. Khi chạy bằng Docker thì dùng `--env-file`.

`AUTH_JWT_SECRET` phải trùng với auth-service. `ORDER_SERVICE_URL` là địa chỉ nội bộ của
order-service; JWT của người mua được chuyển tiếp sang API kiểm tra quyền sở hữu đơn.

| Biến | Mặc định | Ý nghĩa |
|---|---|---|
| `VNPAY_ENABLED` | `false` | Bật cổng VNPay. Tắt thì tạo thanh toán trả `503 VNPAY_NOT_CONFIGURED` |
| `VNPAY_TMN_CODE` | rỗng | Mã website (TmnCode) VNPay gửi qua email |
| `VNPAY_HASH_SECRET` | rỗng | Chuỗi bí mật ký HMAC-SHA512 |
| `VNPAY_PAY_URL` | `https://sandbox.vnpayment.vn/paymentv2/vpcpay.html` | Trang thanh toán |
| `VNPAY_RETURN_URL` | `http://localhost:5173/payment/vnpay-return` | Trang frontend VNPay trả người mua về |

Bật `VNPAY_ENABLED=true` mà thiếu `VNPAY_TMN_CODE` hoặc `VNPAY_HASH_SECRET` thì service không
khởi động. Không commit khóa thật.

### Đăng ký sandbox và thẻ test

1. Đăng ký merchant test miễn phí tại https://pay.vnpaytest.vn/dev-registration ; VNPay gửi TmnCode
   và HashSecret qua email.
2. Xem giao dịch sandbox ở https://sandbox.vnpayment.vn/merchantv2/ .
3. Thẻ test (ngân hàng NCB): số thẻ `9704198526191432198`, tên `NGUYEN VAN A`,
   ngày phát hành `07/15`, OTP `123456`.

Các thời gian cấu hình bằng Spring property (kiểu `15m`, `2m`, `30s`):

| Service/property | Mặc định | Ý nghĩa |
|---|---|---|
| Order: `app.payment.ttl` | `15m` | Hạn thanh toán của đơn, tính bằng Clock được inject |
| Order: `app.payment.fallback-delay` | `15m` | Chờ thêm trước khi job an toàn của Order hủy đơn chưa có kết quả thanh toán |
| Payment: `app.payment.link-expiry-buffer` | `1m` | URL VNPay hết hạn (`vnp_ExpireDate`) sớm hơn hạn của đơn |
| Payment: `app.payment.settlement-grace` | `2m` | Chờ IPN đến trễ trước khi phát EXPIRED |
| Payment: `app.payment.transaction-zone` | `Asia/Ho_Chi_Minh` | Múi giờ để đọc `vnp_PayDate` |
| Payment: `app.payment.operation-lease` | `30s` | Lease lưu DB cho một thao tác với cổng, tối thiểu 15s |
| Payment: `app.payment.max-cancel-attempts` | `8` | Số lần thử hủy tối đa trước khi chuyển sang đối soát |

Fallback delay của Order phải dài hơn settlement grace của Payment. Hạn link và hạn đối soát được
lưu khi tạo payment nên đổi cấu hình không làm dịch hạn của giao dịch cũ.

## API và luồng thanh toán

Mọi API cần JWT của auth-service, trừ health, docs và IPN (xác thực bằng chữ ký VNPay).

1. `POST /api/orders` với `addressId`, `note` (tùy chọn), `paymentMethod: "VNPAY"`. Không gửi
   phương thức thì mặc định COD. Đơn VNPAY chờ ở `WAITING_PAYMENT`.
2. Order phát `OrderPlaced` qua SQL outbox. Product giữ tồn kho và trả `StockReserved`; hết hàng thì
   đơn bị hủy. Chưa giữ kho thì chưa được tạo thanh toán. Bước này bất đồng bộ: frontend poll
   `GET /api/orders/{id}` đến khi `stockReserved=true` (ví dụ mỗi giây, tối đa 30 giây). Gọi sớm
   có thể nhận `409 ORDER_NOT_PAYABLE`; tải lại đơn rồi thử lại, không tạo đơn thứ hai.
3. Người mua gọi `POST /api/payments` chỉ với `{ "orderId": "<ULID>" }`. Số tiền và hạn lấy từ
   Order Service, không nhận từ client. Payment dựng URL thanh toán VNPay đã ký ngay tại service
   (VNPay không có API tạo link, không gọi HTTP ra ngoài). Response gồm `id`, `checkoutUrl`,
   `qrAvailable=false` (VNPay không trả QR), `expiresAt` (hạn của đơn), `linkExpiresAt` (hạn thực
   của URL), `settlementExpiresAt` (hết thời gian chờ IPN). Frontend mở `checkoutUrl`.
   Request đồng thời có thể nhận `CREATING` chưa có URL: poll `GET /api/payments/{id}`.
   Ở `AWAITING_WEBHOOK` thì ngừng hiện link và chờ kết quả cuối.
4. Người mua trả tiền trên trang VNPay (thẻ/QR). VNPay gọi hai đường:
   - **IPN** `GET /api/payments/webhooks/vnpay` (công khai, gateway chỉ mở đúng đường dẫn này).
     Luôn trả HTTP 200 với body `{"RspCode":"..","Message":".."}`: `00` xác nhận thành công
     (cũng là `00` khi kết quả thất bại đã được xử lý), `97` sai chữ ký, `01` không thấy giao dịch,
     `04` sai số tiền, `02` đã xác nhận trước đó, `99` lỗi khác.
   - **Return URL**: VNPay chuyển trình duyệt về `VNPAY_RETURN_URL` kèm các tham số `vnp_*`.
     Frontend gọi `GET /api/payments/vnpay/return?<các tham số vnp_>` (cần JWT người mua). Payment
     kiểm tra chữ ký (`400 INVALID_SIGNATURE` nếu sai), chạy cùng xử lý idempotent như IPN rồi trả
     payment của người mua.
   - **IPN cần URL public** (ví dụ ngrok) và phải khai báo trong cổng merchant. Chạy local thì VNPay
     không gọi được `localhost`, nên return endpoint là nơi xác nhận thanh toán.
5. Xử lý kết quả: số tiền = `vnp_Amount / 100` (phải chia hết và bằng số tiền đã lưu); thành công khi
   `vnp_ResponseCode=00` và `vnp_TransactionStatus=00`; mã tham chiếu là `vnp_TransactionNo`
   (không rỗng, khác `0`, tối đa 128 ký tự); thời điểm trả tiền là `vnp_PayDate`
   (`yyyyMMddHHmmss`, giờ Việt Nam). Kết quả không thành công chuyển payment sang `FAILED` và phát
   `PaymentFailed(RETRYABLE)`; người mua có thể tạo lại URL trong hạn.
6. Một transaction SQL ghi kết quả thành công cùng bản ghi outbox `PaymentSucceeded`. Order tiêu thụ
   và chuyển sang `PENDING` (chờ người bán xác nhận).
7. Payment quyết định việc hết hạn: sau hạn của đơn cộng settlement grace, phát `PaymentFailed(EXPIRED)`
   đúng một lần, Order tiêu thụ và nhả tồn kho. Job của Order chỉ là lưới an toàn (hủy ở hạn + 15 phút,
   kể cả khi người mua chưa tạo Payment). Thanh toán đến trễ trong thời gian grace được xét theo
   `vnp_PayDate` chứ không theo lúc nhận. Giao dịch thực sự sau hạn của đơn thì tạo yêu cầu hoàn tiền
   và làm đơn thất bại. Payment đã hết hạn/hủy không bị "sống lại" bởi callback đến muộn, mà tạo hoàn tiền.

### Mã giao dịch `vnp_TxnRef`

Có dạng `<paymentId>T<epoch giây lúc tạo payment>`, lưu vào `paymentLinkId`. Mã ổn định cho một
payment nhưng không trùng khi dev reset DB (id chạy lại từ đầu) trên cùng một merchant sandbox.
Callback lấy payment id từ phần trước chữ `T` và so khớp toàn bộ mã với `paymentLinkId` đã lưu.

### Hủy link

VNPay không có API hủy: URL tự hết hạn tại `vnp_ExpireDate`, nên "hủy" ở provider là no-op thành công
(job vẫn chạy theo lease để giữ nguyên máy trạng thái). Nếu người mua vẫn trả tiền sau khi link hết hạn
hoặc đơn đã hủy, callback đi nhánh "đến trễ" và tạo yêu cầu hoàn tiền.

`GET /api/payments?page=0&size=20` và `GET /api/payments/{id}` chỉ trả lịch sử của người gọi.
API dành cho Admin:

- `GET /api/payments/admin/refunds`: hàng đợi hoàn tiền đang chờ, có phân trang.
- `GET /api/payments/admin/reconciliations`: hàng đợi đối soát bền vững (ví dụ hết số lần thử hủy).
- `POST /api/payments/admin/{paymentId}/refunds` với `reason`: ghi nhận hoàn tiền thủ công cho giao dịch
  đã thanh toán. Admin phải kiểm tra bằng chứng hủy/trả hàng.
- `PUT /api/payments/admin/refunds/{refundId}/confirm` với `transferReference`: xác nhận đã chuyển
  khoản hoàn tiền, ghi lại admin và thời điểm xác nhận.

COD tạo `COD_PENDING` và không gọi VNPay. Consumer `CodCollected` an toàn khi nhận trùng, ghi số tiền,
người mua, mã thu và thời điểm, kể cả khi người mua chưa từng tạo payment. Delivery kiểm tra shipper và
số tiền trước khi phát. PostgreSQL lock và unique constraint bảo vệ các request đồng thời (callback,
tạo payment, xác nhận hoàn tiền).

## Kiểm thử và giới hạn vận hành

Từ `services/`: `./mvnw -B --no-transfer-progress -pl payment-service,order-service,delivery-service,notification-service,api-gateway -am test`.
Test của Payment chạy Flyway migration thật và Hibernate validate schema trên H2. Gồm: JWT/RBAC,
chữ ký `vnp_SecureHash` (tamper, khoảng trắng mã hóa thành `+`), URL thanh toán (amount x 100, giờ +07),
IPN với các mã 00/01/02/04/97, IPN trùng lặp, return endpoint, thanh toán đến trễ bị hoàn tiền, COD,
hết hạn, retry và outbox. Test dùng Testcontainers (PostgreSQL) sẽ bị bỏ qua nếu máy không có Docker.

Order và Payment bật confirmed publication trong shared-events (ACK có tương quan, return bắt buộc).
Dòng outbox chỉ bị xóa sau khi có ACK và không bị return; nack, timeout hoặc không route được thì giữ lại
để gửi lại. Consumer idempotent vì có thể nhận lại khi service sập sau khi publish nhưng trước khi commit
SQL. Đây là at-least-once, không phải exactly-once. Xem `<service>.events.dead` cho message hết lượt retry.

Hủy qua provider dùng batch theo thời điểm đến hạn và backoff lũy thừa (tối đa 5 phút), tối đa 8 lần.
Hết lượt thử thì đặt `reconciliationRequired=true` và xóa `cancelPending`, không còn vòng lặp vô hạn.

Outbox của Order và Payment lấy từng dòng đến hạn bằng `FOR UPDATE SKIP LOCKED`, commit ACK/xóa riêng
từng dòng, tối đa 50 dòng mỗi lượt. Backoff lỗi bắt đầu 5 giây, tối đa 5 phút; sau 10 lần lỗi dòng thành
DEAD và giữ payload. Event sau của cùng một đơn phải chờ, đơn khác vẫn chạy. Một payment lỗi không làm
dừng cả batch của job hết hạn. Cách khôi phục event DEAD xem
[outbox-recovery.md](../../docs/outbox-recovery.md).

Mã tham chiếu COD và VNPay là duy nhất trong từng phương thức nên mã COD không đụng mã của ngân hàng.
`vnp_TransactionNo` tối đa 128 ký tự cho khớp schema. Product xác nhận PaymentSucceeded đến muộn sau khi
đã hủy/nhả kho mà không xác nhận lại tồn kho.

### Migration

Payment: `V6__vnpay_method.sql` đổi `method` cũ `PAYOS` thành `VNPAY`. Order: `V13__vnpay_method.sql` đổi
`orders.payment_method` tương tự. Delivery vẫn chấp nhận `PAYOS` cho các dòng cũ.

Chưa thực hiện thanh toán thật trên sandbox VNPay trong PR này; việc kiểm tra end-to-end với TmnCode
thật và IPN qua ngrok cần làm thủ công.
