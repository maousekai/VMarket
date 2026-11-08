-- =============================================================================
-- PBL6-27 - Order Service: index cho thống kê / đơn gần đây theo gian hàng.
--
-- Seller dashboard lọc order_items theo shop_id rồi join orders theo created_at.
-- Không có index, PostgreSQL phải quét toàn bộ order_items khi shop có nhiều đơn.
-- Cú pháp IF NOT EXISTS chạy được trên cả PostgreSQL và H2 (FlywayMigrationTest).
-- =============================================================================

CREATE INDEX IF NOT EXISTS idx_orders_created_at ON orders (created_at);
CREATE INDEX IF NOT EXISTS idx_order_items_shop_id ON order_items (shop_id, order_id);
