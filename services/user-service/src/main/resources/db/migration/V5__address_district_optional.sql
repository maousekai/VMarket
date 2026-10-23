-- =============================================================================
-- PBL6-25 - User Service: quan/huyen cua dia chi khong con bat buoc.
--
-- Tu 01/07/2025 Da Nang (sap nhap Quang Nam) bo cap quan/huyen, dia chi chi con
-- tinh/thanh va phuong/xa. Cot district giu lai de dia chi cu van doc duoc, nhung
-- cho phep NULL de dia chi moi khong phai dien gia tri khong co that.
--
-- Cu phap nay chay duoc tren ca PostgreSQL va H2 (FlywayMigrationTest).
-- =============================================================================

ALTER TABLE addresses ALTER COLUMN district DROP NOT NULL;
