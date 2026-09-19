-- =============================================================================
-- PBL6-13 - FR-USER-02: rang buoc TOI DA MOT dia chi mac dinh cho moi user.
--
-- VI SAO NAM RIENG O db/vendor/postgresql :
--   Partial index (CREATE INDEX ... WHERE) la tinh nang rieng cua PostgreSQL; H2
--   bao "Syntax error" ngay tai tu khoa WHERE. Bo tho vao V2 thi
--   FlywayMigrationTest (chay migration that tren H2) khong khoi dong duoc.
--   spring.flyway.locations co placeholder {vendor} nen:
--     - chay tren PostgreSQL -> nap them thu muc nay, co day du rang buoc;
--     - chay tren H2 (test)  -> db/vendor/h2 khong ton tai, Flyway bo qua.
--
--   Thu muc vendor PHAI nam NGOAI db/migration: Flyway quet de quy, nen thu muc
--   con cua db/migration van bi nap khi chay tren H2 (no log "Discarding location
--   ... as it is a sub-location of ..." roi VAN chay file do) - dung y tach theo
--   vendor mat sach.
--
-- VI SAO CAN O TANG CSDL du AddressService da xu ly:
--   Hai request "dat mac dinh" chay song song deu doc thay dia chi cu roi cung
--   ghi is_default = true -> khong co index nay thi user co 2 dia chi mac dinh
--   va khong the biet don hang lay cai nao. Co index thi request thu hai bi tu
--   choi, AddressService dich thanh 409 DEFAULT_ADDRESS_CONFLICT.
--
-- HE QUA CHO TEST: tren H2 rang buoc nay KHONG ton tai, nen cac test cua
--   AddressApiTest bao ve bat bien bang tang service. Kiem tra rang buoc CSDL
--   that de danh cho test Testcontainers o PBL6-47.
-- =============================================================================

CREATE UNIQUE INDEX uq_addresses_one_default_per_user
    ON addresses (user_id)
    WHERE is_default;
