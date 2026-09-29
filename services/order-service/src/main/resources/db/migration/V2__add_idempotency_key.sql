-- =============================================================================
-- PBL6-17 - Idempotency-Key chống tạo đơn trùng khi retry / gửi đồng thời.
--
-- Mỗi cặp (user_id, idempotency_key) là duy nhất: nếu client gửi lại cùng key
-- thì trả lại đơn đã tạo thay vì tạo đơn mới. Cột nullable: đơn cũ (trước khi
-- có tính năng này) hoặc client không gửi key sẽ có NULL, và NULL không vi phạm
-- ràng buộc UNIQUE trong PostgreSQL / H2.
-- =============================================================================

ALTER TABLE orders ADD COLUMN idempotency_key VARCHAR(64);

CREATE UNIQUE INDEX idx_orders_user_idempotency
    ON orders (user_id, idempotency_key);
