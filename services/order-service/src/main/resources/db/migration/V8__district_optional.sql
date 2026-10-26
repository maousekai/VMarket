-- =============================================================================
-- PBL6-25 - Order Service: quan/huyen cua dia chi giao hang khong con bat buoc.
--
-- Tu 01/07/2025 Da Nang (sap nhap Quang Nam) bo cap quan/huyen, dia chi chi con
-- tinh/thanh va phuong/xa. Cot district giu lai de don cu van doc duoc, nhung
-- cho phep NULL: dia chi moi tao tu user-service (district = NULL) phai dat
-- duoc hang, neu khong moi don se loi 500 khi ghi snapshot.
--
-- Cu phap nay chay duoc tren ca PostgreSQL va H2 (FlywayMigrationTest).
-- =============================================================================

ALTER TABLE orders ALTER COLUMN district DROP NOT NULL;
