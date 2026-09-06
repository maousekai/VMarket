-- =============================================================================
-- PBL6-42 (FR-AUTH-01) - Dang ky tai khoan.
--
-- Them cot kich hoat tai khoan. User moi dang ky -> email_verified = false
-- (trang thai PENDING). Viec gui email OTP/link va endpoint xac nhan kich hoat
-- thuoc PBL6-45; subtask nay chi tao user + danh dau chua xac thuc.
--
-- Expand-contract: cot NOT NULL kem DEFAULT false -> an toan voi dong da co
-- (hien chua co user nao).
--
-- LUU Y: header cua V1__init_auth_schema.sql ghi ke hoach "V2 = PBL6-43". Ke
-- hoach do da doi: PBL6-42 dung V2 cho email_verified. Cac migration sau danh so
-- tiep -> V3 (PBL6-43: failed_login_attempts, locked_until), V4 (PBL6-44)...
-- Khong sua file V1 de tranh doi checksum Flyway cua migration da chay.
-- =============================================================================

ALTER TABLE users
    ADD COLUMN email_verified BOOLEAN NOT NULL DEFAULT false;
