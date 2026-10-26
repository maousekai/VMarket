# Khôi phục Order outbox

Order giữ thứ tự theo đơn. Một event `DEAD` chặn các event phía sau **cùng đơn**;
đơn khác tiếp tục được xử lý. Không tự bỏ qua event hủy đơn/thanh toán/tồn kho.

## Phát hiện

- `/actuator/prometheus`: `vmarket_order_outbox_dead`, `vmarket_order_outbox_blocked`,
  `vmarket_order_outbox_oldest_dead_age` (giây). Worker giám sát cập nhật mỗi 60 giây.
- Log `ORDER_OUTBOX_DEAD` nêu số event lỗi, số event bị chặn và tuổi event cũ nhất.
- Prometheus nạp `infra/monitoring/prometheus/outbox-alerts.yml`, phát alert
  `OrderOutboxDead` khi có event DEAD liên tục 2 phút. Alert hiện trong Prometheus;
  muốn gửi thông báo ra ngoài cần cấu hình Alertmanager/receiver của môi trường.

## Khôi phục có kiểm soát

Các endpoint dưới đây bắt buộc JWT có role `ADMIN`, đi qua Gateway như API Orders:

1. `GET /api/orders/admin/outbox/status`: số DEAD, số event bị chặn, tuổi event DEAD cũ nhất.
2. `GET /api/orders/admin/outbox/dead?page=0&size=20`: metadata của event DEAD,
   không trả payload chứa thông tin khách hàng.
3. Kiểm tra nguyên nhân (binding/consumer/broker/payload), sửa nguyên nhân và kiểm tra
   consumer chịu được duplicate trước khi replay.
4. `POST /api/orders/admin/outbox/{id}/replay`, body
   `{"reason":"Đã sửa binding và kiểm tra consumer, ticket vận hành ..."}`.
   Thành công trả `204`; event không còn DEAD trả `409`, không tìm thấy trả `404`.
5. Theo dõi ACK/xóa event và giảm số bị chặn. Nếu tiếp tục lỗi, event retry lại theo
   backoff và trở về DEAD sau 10 lần; không tự mở khóa các event phía sau.

Replay khóa dòng, ghi audit và đưa về PENDING trong **cùng transaction**. Giữ nguyên
ID, payload, thời gian tạo và sequence để không đổi thứ tự. Audit ở
`outbox_replay_audit` lưu event/aggregate, loại event, số lần lỗi/mã lỗi trước đó,
Admin, lý do và thời gian; audit tồn tại sau khi event được gửi và xóa khỏi outbox.
Hai Admin cùng replay một event chỉ có một thao tác thành công.

Nếu broker đã nhận nhưng confirm bị mất, hoặc tiến trình chết trước commit xóa,
event có thể được gửi lại. Consumer chống trùng bằng trạng thái/khóa nghiệp vụ của
đơn, thanh toán và reservation; không dựa riêng vào envelope ID mới của mỗi lần gửi.

## Kiểm chứng review Payment / Delivery

- `OrderOutboxTest`: DEAD chặn successor, Admin replay giải phóng theo đúng thứ tự,
  kiểm tra quyền, audit sau ACK, metric và mô phỏng broker nhận nhưng mất confirm.
- `python scripts/test-payment-flow.py --build`: chạy **Order, Payment, Product thật**,
  PostgreSQL, RabbitMQ và MongoDB replica set trong container tách biệt. Cart/User/PayOS
  dùng HTTP stub. Barrier PostgreSQL giữ consumer hủy để ép webhook commit trước;
  barrier thứ hai đợi cancellation commit rồi mới cho webhook chạy. Mỗi trường hợp
  kiểm tra Order=CANCELLED, Payment=SUCCEEDED, tồn kho đã release, đúng một refund,
  8 request tạo payment đồng thời, 4 webhook trùng và event broker gửi lại. Suite
  kiểm tra cả quyền/audit hoàn tiền thủ công và replay DEAD trên PostgreSQL.
- CI `payment-flow-integration` lưu log và `result.json` của suite thành artifact.

Fixture được tạo qua API Orders và HTTP stub, không phải bản sao dữ liệu production.
Suite không chuyển tiền, không xác nhận PayOS/ngân hàng thật và không chứng minh
khả năng chạy nhiều instance của toàn hệ thống. Các trường hợp lỗi broker và mất
confirm là mô phỏng có kiểm soát trong test outbox; suite liên service dùng broker thật.
