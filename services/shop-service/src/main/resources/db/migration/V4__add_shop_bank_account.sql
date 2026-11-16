-- =============================================================================
-- Tai khoan ngan hang nhan tien cua gian hang.
--
-- Nguoi ban khai khi dang ky gian hang va doi trong muc "Thong tin thanh toan"
-- cua kenh nguoi ban (PUT /api/shops/me/bank-account). bank_code la ma ngan hang
-- theo danh sach VietQR (VCB, TCB, MB...).
--
-- Nullable: ho so dang ky truoc migration nay chua co thong tin ngan hang.
-- Cu phap chay duoc tren ca PostgreSQL va H2 (FlywayMigrationTest).
-- =============================================================================

ALTER TABLE shops ADD COLUMN bank_code VARCHAR(20);
ALTER TABLE shops ADD COLUMN bank_account_number VARCHAR(30);
