-- =============================================================================
-- PBL6-53 - Order Service: Optimistic locking (@Version) for orders.
--
-- Phong chong loi ghi de du lieu khi hai thao tac sua don hang dien ra song song
-- (Lost Update Anomaly): vd nguoi mua huy don cung luc shipper giao hang thanh cong,
-- hoac hai webhook cap nhat trang thai cung luc.
--
-- Cu phap nay chay duoc tren ca PostgreSQL va H2 (FlywayMigrationTest).
-- =============================================================================

ALTER TABLE orders ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

