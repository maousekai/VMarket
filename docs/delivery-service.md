# PBL6-20 — Delivery Service

## Triển khai và phụ thuộc

Delivery chạy cổng 8088, dùng PostgreSQL riêng, Redis (cache LocationPing, vé WebSocket và pub/sub giữa các instance), RabbitMQ (sự kiện nghiệp vụ). Flyway quản lý schema. JWT và `INTERNAL_API_KEY` phải trùng Auth/Order. Không dùng root `.env` thay cho `.env` của service.

**Merge PBL6-19 / PR #29 trước PBL6-20.** Nhánh PBL6-20 đã đồng bộ commit `31fc364` của PBL6-19, gồm bản sửa review về hủy đơn và trạng thái link PayOS. Order giữ các binding Payment/Stock và DeliveryStatusChanged trong một khối cấu hình, publisher confirms và hai outbox; migration V3 (payment) và V4 (delivery) không trùng. Order phải có trạng thái thanh toán và `stockReserved`; khi thiếu hợp đồng này, bàn giao trả `503 PAYMENT_INTEGRATION_REQUIRED`. Chưa giữ tồn kho, chưa thanh toán, hoặc đơn đã huỷ đều không được giao. Nhóm review/merge PR #29 riêng trước để phần diff PBL6-20 chỉ còn chức năng giao hàng.

## API

Mọi REST API dưới `/api/delivery` dùng `Authorization: Bearer <access token>` do Auth ký. Service tự kiểm tra token khi gọi trực tiếp. Admin được kiểm tra lại quyền tại Auth trước khi thay đổi dữ liệu.

| Quyền | Phương thức và đường dẫn | Nội dung |
|---|---|---|
| Admin | POST `/admin/shippers` | Tạo tài khoản SHIPPER và hồ sơ; header `Idempotency-Key` 8–64 ký tự chữ/số/`_-` |
| Admin | GET `/admin/shippers?province=&district=&page=0&size=20` | Lọc khu vực; số đơn, đơn thành công và đơn đã kết thúc |
| Admin | PUT `/admin/shippers/{id}/lock` | `{"locked":true}` / false; khoá Auth, thu hồi refresh và chặn JWT cũ ở Delivery |
| Admin | GET `/admin/assignments?page=0&size=20` | Danh sách chuyến, gồm đơn chờ phân công |
| Admin | POST `/admin/assignments/{id}/assign` | `{"shipperId":"..."}`; cùng khu vực, đã kích hoạt, chưa lấy hàng |
| Seller | POST `/seller/orders/{orderId}/handoff` | Shop của Seller phải ACTIVE và có item trong đơn |
| Shipper | GET `/shipper/assignments?page=0&size=20` | Chỉ các chuyến của shipper đó, có snapshot địa chỉ giao và COD |
| Shipper | PUT `/shipper/assignments/{id}/status` | `status`, `reason` khi FAILED, `codCollected` khi DELIVERED |
| Shipper | POST `/shipper/assignments/{id}/location` | `{"latitude":16.04,"longitude":108.21}` |
| Shipper/Admin | POST `/{shipper\|admin}/assignments/{id}/retry` | Tối đa hai lần giao lại |
| Buyer/Seller/Shipper/Admin | GET `/orders/{orderId}` | Quyền theo chủ đơn, shop đã bàn giao, shipper được phân công hoặc Admin |
| Cùng quyền trên | GET `/orders/{orderId}/route?page=0&size=100` | Lịch sử GPS theo thứ tự, tối đa 500 điểm/trang |
| Cùng quyền trên | POST `/orders/{orderId}/tracking-ticket` | Vé WebSocket dùng một lần, sống 30 giây |

Body tạo shipper:

```json
{
  "account": {"email":"shipper@example.com","username":"shipper01","password":"Example123!"},
  "name":"Nguyễn Văn A","phone":"0901234567","province":"Đà Nẵng","district":"Hải Châu"
}
```

Tài khoản mới phải xác thực email qua luồng OTP sẵn có của Auth trước khi nhận chuyến. Không tạo quyền SHIPPER qua API đăng ký công khai. Khi retry tạo tài khoản, giữ cùng key và hồ sơ; không dùng lại key cho người khác.

## Luồng và sự kiện

Đơn có nhiều shop phải có đủ các lần bàn giao; mỗi shop lưu snapshot địa chỉ lấy hàng riêng. Khi đủ, chọn shipper hoạt động cùng tỉnh/quận có ít chuyến chưa kết thúc nhất. Nếu không có người phù hợp, giữ WAITING_ASSIGNMENT để Admin phân công.

`WAITING_HANDOFF → WAITING_ASSIGNMENT → ASSIGNED → PICKED_UP → IN_TRANSIT → DELIVERED`

Giao thất bại cần lý do: `IN_TRANSIT → FAILED → retry → IN_TRANSIT`. Lần thất bại thứ ba được lưu vào lịch sử rồi chuyển RETURNING; shipper xác nhận đã trả hàng qua RETURNED. RETURNED cập nhật đơn thành CANCELLED và phát OrderStatusChanged để Payment PBL6-19 hoàn tiền đơn trả trước. Không yêu cầu hoàn tiền chỉ vì một lần giao thất bại.

Các request chuyển trạng thái, phân công và retry phải gửi `expectedVersion` lấy từ assignment mới nhất. Ví dụ retry: `{"expectedVersion":5}`. Yêu cầu cũ sau một lần giao lại bị từ chối `409 STALE_DELIVERY`; client tải lại chuyến trước khi thao tác. Vì vậy replay FAILED cũ không bị tính thành thất bại của lần giao mới.

COD lấy từ số tiền Order trả về, không nhận giá tiền do người gọi cung cấp. DELIVERED phải xác nhận đúng số tiền; đơn PAYOS phải xác nhận 0. `CodCollected(orderId,buyerId,amount,reference)` phát một lần trong giao dịch nghiệp vụ; reference là assignmentId, để Payment chống trùng khi hàng đợi gửi lại.

`DeliveryAssigned` và `DeliveryStatusChanged(orderId,status,version)` được lưu cùng trạng thái trong SQL outbox. Order chỉ áp dụng phiên bản mới hơn, không quay ngược đơn terminal; outbox Order phát OrderStatusChanged sau giao dịch. Chỉ xoá outbox khi RabbitMQ xác nhận ACK và không trả về thông điệp không có route. Consumer phải chấp nhận gửi lại. Nhóm vận hành theo dõi/replay dead-letter khi có lỗi kéo dài.

## WebSocket và GPS

Client lấy vé bằng REST, rồi mở URL **`webSocketUrl` trả về trong phản hồi**. `DELIVERY_WS_URL` cấu hình đường vào riêng tới Delivery; mặc định local là `ws://localhost:8088/api/delivery/ws`, production bắt buộc cấu hình URL WSS qua reverse proxy tới Delivery. Cấu hình `CORS_ALLOWED_ORIGINS` bằng origin frontend cụ thể. Gateway hiện dùng Server Web MVC; REST đi cổng 8080, endpoint WebSocket cần đường ingress riêng tới cổng 8088. Không dùng JWT trên query string. Proxy không ghi giá trị ticket trong access log.

Vé bị tiêu thụ nguyên tử trong Redis, từ chối replay và truy cập ngoài phạm vi. Session hết hạn theo JWT, kiểm tra lại quyền định kỳ và trước khi phát dữ liệu. Socket chỉ nhận cập nhật từ server; GPS được gửi bằng REST đã xác thực.

Gửi GPS mỗi 10–30 giây trong PICKED_UP/IN_TRANSIT/RETURNING. Server chặn ping dưới 10 giây, toạ độ không hữu hạn/ngoài khoảng và ping khi đã kết thúc. Mỗi điểm lưu PostgreSQL; SQL outbox riêng đẩy Redis/pub/sub mỗi 500ms, không bị RabbitMQ chậm chặn. Lua cập nhật cache nguyên tử theo version, session bỏ qua phiên bản cũ. `active:false` yêu cầu client dừng timer; vị trí cuối có `recordedAt`, không được hiểu là vị trí hiện tại sau khi kết thúc. Cache sống 24h; route/history vẫn lưu PostgreSQL.

Mục tiêu nhận vị trí trong 5 giây áp dụng khi mạng/Redis hoạt động. Khi phụ thuộc gián đoạn, outbox giữ dữ liệu và gửi lại, REST vẫn trả vị trí cuối đã lưu với timestamp.

## Kiểm thử

```powershell
cd services
.\mvnw.cmd -pl delivery-service,order-service,auth-service,product-service -am test
```

DeliveryApiTest kiểm tra JWT/quyền, bàn giao nhiều shop, trạng thái/COD, giới hạn giao lại, route, khoá, phân trang, outbox và WebSocket TCP thật dưới 5 giây. Redis/pubsub và HTTP sang service khác được mock ở ranh giới; kết quả này không khẳng định Redis/RabbitMQ production hay trình duyệt đã chạy. Auth kiểm tra tạo role, OTP pending, idempotency, khoá và internal key. Order kiểm tra internal key, phụ thuộc PBL6-19 và chống sự kiện trùng/đảo thứ tự. Flyway chạy trên H2 PostgreSQL mode; CI build image riêng từng service.
