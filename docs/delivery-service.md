# PBL6-20 — Delivery Service

## Triển khai và phụ thuộc

Delivery chạy cổng 8088, dùng PostgreSQL riêng, Redis (cache LocationPing, vé WebSocket và pub/sub giữa các instance), RabbitMQ (sự kiện nghiệp vụ). Flyway quản lý schema. JWT và `INTERNAL_API_KEY` phải trùng Auth/Order. Không dùng root `.env` thay cho `.env` của service.

**Merge PBL6-19 / PR #29 trước PBL6-20.** Nhánh PBL6-20 đã đồng bộ commit `31fc364` của PBL6-19, gồm bản sửa review về hủy đơn và trạng thái link PayOS. Order giữ các binding Payment/Stock và DeliveryStatusChanged trong một khối cấu hình. Migration Order V5/V6 chuyển nguyên các sự kiện chờ từ `delivery_order_outbox` sang `event_outbox`, bổ sung khóa theo đơn và lịch retry, rồi bỏ bảng/worker cũ. Order dùng trực tiếp các trường thanh toán và `stockReserved` của PBL6-19. Chưa giữ tồn kho, chưa thanh toán, hoặc đơn đã huỷ đều không được giao.

API nội bộ Delivery của Order dùng cấu hình riêng `app.delivery.internal-api-key`, cùng biến môi trường `INTERNAL_API_KEY`; không phụ thuộc cấu hình Cart. Không sửa các migration đã phát hành; Delivery nâng tiếp qua V3/V4, Order qua V5/V6.

## API

Mọi REST API dưới `/api/delivery` dùng `Authorization: Bearer <access token>` do Auth ký. Service tự kiểm tra token khi gọi trực tiếp. Admin được kiểm tra lại quyền tại Auth trước khi thay đổi dữ liệu.

| Quyền | Phương thức và đường dẫn | Nội dung |
|---|---|---|
| Admin | POST `/admin/shippers` | Tạo tài khoản SHIPPER và hồ sơ; header `Idempotency-Key` 8–64 ký tự chữ/số/`_-` |
| Admin | GET `/admin/shippers?province=&district=&page=0&size=20` | Lọc khu vực; số đơn, đơn thành công và đơn đã kết thúc |
| Admin | PUT `/admin/shippers/{id}/lock` | `{"locked":true}` / false; khoá Auth, thu hồi refresh và chặn JWT cũ ở Delivery |
| Admin | GET `/admin/assignments?page=0&size=20` | Danh sách chuyến, gồm đơn chờ phân công |
| Admin | POST `/admin/assignments/{id}/assign` | `shipperId`, `expectedVersion`; `overrideArea:true` kèm `reason` để gán vượt khu vực; đổi người đang giữ hàng luôn cần lý do |
| Admin | POST `/admin/assignments/{id}/return` | `expectedVersion`, `reason`; chuyển FAILED sang RETURNING kể cả khi shipper cũ bị khoá |
| Seller | POST `/seller/orders/{orderId}/handoff` | Shop của Seller phải ACTIVE và có item trong đơn; trả id, orderId, status, attempt, version |
| Shipper | GET `/shipper/assignments?page=0&size=20` | Chỉ các chuyến của shipper đó, có snapshot địa chỉ giao và COD |
| Shipper | PUT `/shipper/assignments/{id}/status` | `status`, `reason` khi FAILED, `codCollected` khi DELIVERED |
| Shipper | POST `/shipper/assignments/{id}/location` | `{"latitude":16.04,"longitude":108.21}` |
| Shipper/Admin | POST `/{shipper\|admin}/assignments/{id}/retry` | Tối đa hai lần giao lại |
| Buyer/Seller/Shipper/Admin | GET `/orders/{orderId}` | Seller chỉ nhận item/pickup của shop mình; không có địa chỉ, liên hệ người mua, tổng COD, snapshot đơn hoặc item của shop khác |
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

Đơn có nhiều shop phải có đủ các lần bàn giao; mỗi shop lưu snapshot địa chỉ lấy hàng riêng. Khi đủ, chọn shipper hoạt động cùng tỉnh/quận có ít chuyến chưa kết thúc nhất. Khóa so khớp địa chỉ chuẩn hoá Unicode, bỏ dấu và tiền tố Tỉnh/TP./Thành phố/Quận/Huyện; ví dụ Đà Nẵng, TP. Đà Nẵng, Da Nang và NFC/NFD cho cùng khóa. Migration áp dụng lại cho dữ liệu cũ; địa chỉ nguyên bản trong snapshot vẫn giữ nguyên. Đây là so khớp văn bản, chưa phải danh mục mã hành chính.

Bàn giao được commit trước bước kiểm tra Auth để tự phân công. Nếu không có người phù hợp hoặc Auth lỗi, giữ WAITING_ASSIGNMENT và bàn giao của Seller. Auth trả 404 cho một hồ sơ shipper được coi là ứng viên không hoạt động. Các lệnh đọc hồ sơ shipper không khóa dòng; HTTP kiểm tra quyền/trạng thái chạy trước transaction cập nhật chuyến.

`WAITING_HANDOFF → WAITING_ASSIGNMENT → ASSIGNED → PICKED_UP → IN_TRANSIT → DELIVERED`

Giao thất bại cần lý do: `IN_TRANSIT → FAILED → retry → IN_TRANSIT`. Lần thất bại thứ ba được lưu vào lịch sử rồi chuyển RETURNING; shipper xác nhận đã trả hàng qua RETURNED. RETURNED cập nhật đơn thành CANCELLED và phát OrderStatusChanged để Payment PBL6-19 hoàn tiền đơn trả trước. Không yêu cầu hoàn tiền chỉ vì một lần giao thất bại.

Admin có thể đổi shipper tại ASSIGNED/PICKED_UP/IN_TRANSIT/FAILED/RETURNING, giữ trạng thái và số lần giao. Người mới phải hoạt động; đổi sau lấy hàng cần lý do và lưu shipper trước/sau trong lịch sử. GPS hiện tại được xóa khi đổi người, lịch sử tuyến vẫn giữ. Shipper cũ mất quyền ngay. Khi FAILED, admin có thể yêu cầu hoàn hàng trước giới hạn ba lần; nếu người cũ bị khóa, phân công người mới để tiếp nhận và xác nhận RETURNED.

Các request chuyển trạng thái, phân công, hoàn hàng và retry phải gửi `expectedVersion` lấy từ assignment mới nhất. Ví dụ retry: `{"expectedVersion":5}`. `version` chỉ tăng cho thao tác nghiệp vụ, không tăng khi ping GPS. Yêu cầu cũ sau một lần giao lại bị từ chối `409 STALE_DELIVERY`; vì vậy replay FAILED cũ không bị tính thành thất bại của lần giao mới.

COD lấy từ số tiền Order trả về, không nhận giá tiền do người gọi cung cấp. DELIVERED phải xác nhận đúng số tiền; đơn PAYOS phải xác nhận 0. `CodCollected(orderId,buyerId,amount,reference)` phát một lần trong giao dịch nghiệp vụ; reference là assignmentId, để Payment chống trùng khi hàng đợi gửi lại.

`DeliveryAssigned` và `DeliveryStatusChanged(orderId,status,version)` được lưu cùng trạng thái trong SQL outbox. Order chỉ áp dụng phiên bản mới hơn, không quay ngược đơn terminal; outbox Order phát OrderStatusChanged sau giao dịch. Chỉ xoá outbox khi RabbitMQ xác nhận ACK và không trả về thông điệp không có route. Consumer phải chấp nhận gửi lại. Nhóm vận hành theo dõi/replay dead-letter khi có lỗi kéo dài.

Delivery khai báo queue durable `notification-service.delivery-assigned`, binding `DeliveryAssigned` và dead-letter queue, để publisher confirms có route ngay cả khi Notification chưa chạy. Notification vẫn cần triển khai consumer của queue này; khai báo queue không đồng nghĩa đã gửi thông báo tới người dùng.

Outbox Delivery chỉ lấy tối đa 100 bản ghi đã đến hạn. Mỗi bản ghi được khóa bằng `FOR UPDATE SKIP LOCKED` trong transaction gửi/xóa, áp dụng cho cả kênh sự kiện và tracking. Khi lỗi, lùi lần thử từ 5 giây, tăng gấp đôi và giới hạn 5 phút. Sau 10 lỗi chuyển sang `DEAD`, giữ nguyên payload và loại lỗi, ngừng tự retry. Vì vậy cả batch lỗi không chặn sự kiện hợp lệ phía sau. Lịch retry sống qua khởi động lại.

Order dùng một outbox chung, giữ thứ tự theo `created_at` và sequence do DB cấp. Sự kiện lỗi chỉ giữ lại các sự kiện sau của cùng đơn; đơn khác vẫn được xử lý. Dispatcher cũng khóa `SKIP LOCKED`, backoff và chuyển `DEAD` sau 10 lỗi. Không tự bỏ qua sự kiện nghiệp vụ đã chết: vận hành kiểm tra `state='DEAD'` ở `event_outbox`/`delivery_outbox`, sửa nguyên nhân rồi replay từng id bằng cách đặt `state='PENDING', retry_attempts=0, next_attempt_at=CURRENT_TIMESTAMP, last_error=NULL`. Không xóa payload để né lỗi. Khi worker chết sau ACK nhưng trước commit vẫn có thể gửi lại; consumer phải chống trùng theo version/reference.

## WebSocket và GPS

Client lấy vé bằng REST, rồi mở URL **`webSocketUrl` trả về trong phản hồi**. `DELIVERY_WS_URL` cấu hình đường vào riêng tới Delivery; mặc định local là `ws://localhost:8088/api/delivery/ws`, production bắt buộc cấu hình URL WSS qua reverse proxy tới Delivery. Cấu hình `CORS_ALLOWED_ORIGINS` bằng origin frontend cụ thể. Gateway hiện dùng Server Web MVC; REST đi cổng 8080, endpoint WebSocket cần đường ingress riêng tới cổng 8088. Không dùng JWT trên query string. Proxy không ghi giá trị ticket trong access log.

Vé bị tiêu thụ nguyên tử trong Redis, từ chối replay và truy cập ngoài phạm vi. Session hết hạn theo JWT, kiểm tra lại quyền qua DB/Auth mỗi 30 giây; kiểm tra hạn JWT vẫn chạy mỗi giây và trước khi gửi. Thread nhận Redis chỉ kiểm tra grant đã cache, không gọi HTTP/DB. Khóa/đổi shipper thu hồi phiên cũ sau commit tại instance hiện tại và qua outbox/pubsub ở instance khác; thay đổi quyền trực tiếp tại Auth được phát hiện ở lần kiểm tra kế tiếp (tối đa khoảng 31 giây). Socket chỉ nhận cập nhật từ server; GPS được gửi bằng REST đã xác thực.

Gửi GPS mỗi 10–30 giây trong PICKED_UP/IN_TRANSIT/RETURNING. Server chặn ping dưới 10 giây, toạ độ không hữu hạn/ngoài khoảng và ping khi đã kết thúc. Mỗi điểm lưu PostgreSQL với `locationSequence` riêng. SQL outbox đẩy Redis/pubsub mỗi 500ms, không bị RabbitMQ chậm chặn. Payload WebSocket dùng `sequence` (`trackingSequence` của assignment) để sắp cả GPS lẫn trạng thái; `version` vẫn là version nghiệp vụ. Lua và socket bỏ qua sequence cũ. `active:false` yêu cầu client dừng timer; vị trí cuối có `recordedAt`, không được hiểu là vị trí hiện tại sau khi kết thúc. Cache sống 24h; route/history vẫn lưu PostgreSQL.

Mục tiêu nhận vị trí trong 5 giây áp dụng khi mạng/Redis hoạt động. Khi phụ thuộc gián đoạn, outbox giữ dữ liệu và gửi lại, REST vẫn trả vị trí cuối đã lưu với timestamp.

## Kiểm thử

Bản sửa theo [review của nhóm trên PR #31](https://github.com/maousekai/VMarket/pull/31#issuecomment-6147126445), kiểm tra local ngày 17/10/2026: `./mvnw.cmd -pl delivery-service,order-service,auth-service,product-service,payment-service -am clean verify` đạt. Tổng 353 test: 340 đạt, 13 test cần Docker bị bỏ qua (Auth 6, Product 7) vì Docker daemon chưa chạy. Delivery 32, Order 24, Payment 40 và shared-events 17 đều đạt toàn bộ. Chưa kiểm chứng PostgreSQL/RabbitMQ/Redis thật hay CI của bản sửa này.

```powershell
cd services
.\mvnw.cmd -pl delivery-service,order-service,auth-service,product-service -am test
```

DeliveryApiTest kiểm tra JWT/quyền, bàn giao nhiều shop, trạng thái/COD, giới hạn giao lại, route, khoá, phân trang, outbox và WebSocket TCP thật dưới 5 giây. Redis/pubsub và HTTP sang service khác được mock ở ranh giới; kết quả này không khẳng định Redis/RabbitMQ production hay trình duyệt đã chạy. Auth kiểm tra tạo role, OTP pending, idempotency, khoá và internal key. Order kiểm tra internal key, phụ thuộc PBL6-19 và chống sự kiện trùng/đảo thứ tự. Flyway chạy trên H2 PostgreSQL mode; CI build image riêng từng service.

Regression outbox tạo 100 DeliveryAssigned lỗi trước trạng thái/COD hợp lệ, xác nhận sự kiện phía sau vẫn đi qua và bản ghi lỗi còn nguyên. Test retry kiểm tra thời điểm đến hạn, giới hạn 5 phút, DEAD và khởi động lại dispatcher. Test migration giữ payload, route sequence và khu vực khi nâng Delivery V1 lên V4; Order V4 lên V6 giữ cả hai outbox cũ. Test hai worker đồng thời kiểm tra khóa trên H2; vẫn cần kiểm tra PostgreSQL/RabbitMQ/Redis thật trước khi triển khai.

Các test review bổ sung: NFC/NFD/tiền tố địa chỉ, gán vượt khu vực có audit, GPS xen giữa thao tác trạng thái/retry, thu hồi hàng từ shipper bị khóa ở bốn trạng thái, hoàn hàng bởi admin, Auth lỗi sau claim, dữ liệu riêng từng shop, cache quyền WebSocket và hạn JWT. Order kiểm tra tách khóa Delivery khỏi Cart, không kẹt đơn khác khi sự kiện lỗi và giữ thứ tự sự kiện cùng đơn.
