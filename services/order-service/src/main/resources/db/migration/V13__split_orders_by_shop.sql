-- =============================================================================
-- Tách đơn theo gian hàng (SRS FR-ORD-01): mỗi shop trong giỏ -> một đơn riêng.
--
-- shop_id    : shop sở hữu đơn. NULL với đơn cũ có nhiều shop (không backfill được).
-- checkout_id: gom các đơn sinh ra từ cùng một lần bấm "Đặt hàng". NULL với đơn cũ.
-- Idempotency: một Idempotency-Key giờ có thể ứng với nhiều đơn (mỗi shop một đơn)
-- nên unique index mở rộng thành (user_id, idempotency_key, shop_id).
-- Chạy được trên cả PostgreSQL lẫn H2 (MODE=PostgreSQL).
-- =============================================================================

ALTER TABLE orders ADD COLUMN shop_id VARCHAR(26);
ALTER TABLE orders ADD COLUMN checkout_id VARCHAR(26);

-- Backfill chỉ khi đơn có đúng 1 shop distinct trong order_items.
UPDATE orders
SET shop_id = (SELECT MIN(oi.shop_id) FROM order_items oi WHERE oi.order_id = orders.id)
WHERE (SELECT COUNT(DISTINCT oi.shop_id) FROM order_items oi WHERE oi.order_id = orders.id) = 1;

DROP INDEX idx_orders_user_idempotency;

CREATE UNIQUE INDEX idx_orders_user_idempotency
    ON orders (user_id, idempotency_key, shop_id);

CREATE INDEX idx_orders_checkout_id ON orders (checkout_id);
