-- PBL6-63: trục thanh toán của đơn (UNPAID -> PAID -> REFUNDED), tách khỏi orders.status.
ALTER TABLE orders ADD COLUMN payment_status VARCHAR(20) DEFAULT 'UNPAID' NOT NULL;

-- Backfill từ dữ liệu sẵn có:
--  * VNPAY đã rời WAITING_PAYMENT mà chưa huỷ => PaymentSucceeded đã tới => PAID.
--  * COD đã giao / hoàn tất => đã thu tiền mặt => PAID.
-- Đơn VNPAY đã huỷ không biết chắc có từng thu tiền hay không, để UNPAID; payment-service vẫn
-- là nguồn sự thật cho các khoản hoàn tiền trước thời điểm này.
UPDATE orders SET payment_status = 'PAID'
 WHERE payment_method = 'VNPAY'
   AND status IN ('PENDING', 'PROCESSING', 'SHIPPED', 'DELIVERED', 'COMPLETED');

UPDATE orders SET payment_status = 'PAID'
 WHERE payment_method = 'COD'
   AND status IN ('DELIVERED', 'COMPLETED');
