-- =============================================================================
-- PBL6-20 - Delivery Service: quan/huyen cua dia chi giao hang va shipper
-- khong con bat buoc.
--
-- Tu 01/07/2025 Da Nang bo cap quan/huyen, dia chi chi con tinh/thanh va
-- phuong/xa. Cot district giu lai de don cu va shipper cu van doc duoc, nhung
-- cho phep NULL: don hang moi tao tu order-service (district = NULL) phai
-- luu duoc va duoc tu dong ghep shipper theo tinh/thanh.
--
-- Cu phap nay chay duoc tren ca PostgreSQL va H2 (FlywayMigrationTest).
-- =============================================================================

ALTER TABLE shippers ALTER COLUMN district DROP NOT NULL;
ALTER TABLE delivery_assignments ALTER COLUMN district DROP NOT NULL;

UPDATE shippers SET district = NULL WHERE district = '';
UPDATE delivery_assignments SET district = NULL WHERE district = '';
