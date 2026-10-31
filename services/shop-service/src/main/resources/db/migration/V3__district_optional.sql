-- =============================================================================
-- PBL6-25 - Shop Service: quan/huyen cua dia chi kho khong con bat buoc.
--
-- Tu 01/07/2025 Da Nang (sap nhap Quang Nam) bo cap quan/huyen, dia chi chi con
-- tinh/thanh va phuong/xa. Cot district giu lai de ho so cu van doc duoc, nhung
-- cho phep NULL de nguoi ban khong phai dien mot cap khong con ton tai.
--
-- Cu phap nay chay duoc tren ca PostgreSQL va H2 (FlywayMigrationTest).
-- =============================================================================

ALTER TABLE shops ALTER COLUMN district DROP NOT NULL;
