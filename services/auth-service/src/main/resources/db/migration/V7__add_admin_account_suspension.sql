-- =============================================================================
-- PBL6-13 (FR-USER-04) - Admin khoa / mo khoa tai khoan.
--
-- LUU Y DANH SO: V5 la cua PBL6-45 (quen mat khau, da merge). V6 da duoc PBL6-46
-- (quan ly phien, PR #17 - V6__add_refresh_token_session_metadata.sql) giu, nen
-- file nay dung V7 de hai nhanh khong dam nhau -> PR #17 nen merge TRUOC. Neu PR
-- nay merge truoc thi DB nao da chay V7 se tu choi V6 "den muon" (Flyway khong
-- bat outOfOrder) -> khi do phai doi so mot trong hai file truoc khi merge.
--
-- Vi sao KHONG dung lai cot locked_until (V3):
--   locked_until la khoa TAM 15 phut do dang nhap sai (FR-AUTH-02) va duoc go tu
--   dong o nhieu luong: dang nhap dung, dat lai mat khau (PBL6-45 goi clearLock).
--   Neu Admin khoa bang cach dat locked_until = tuong lai xa thi nguoi bi khoa chi
--   can "Quen mat khau" la tu mo khoa duoc. Khoa boi Admin can cot rieng, chi Admin
--   go duoc.
--
-- users:
--   suspended_at     - thoi diem Admin khoa; NULL = khong bi khoa boi Admin.
--   suspended_reason - ly do khoa (hien cho Admin, khong tra cho nguoi bi khoa).
--   suspended_by     - users.id cua Admin thuc hien (audit; khong FK de xoa Admin
--                      khong keo theo lich su).
-- =============================================================================

ALTER TABLE users ADD COLUMN suspended_at     TIMESTAMP WITH TIME ZONE;
ALTER TABLE users ADD COLUMN suspended_reason VARCHAR(500);
ALTER TABLE users ADD COLUMN suspended_by     VARCHAR(26);
