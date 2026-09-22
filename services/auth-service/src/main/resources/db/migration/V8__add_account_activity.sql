-- =============================================================================
-- PBL6-13 (FR-USER-04) - Lich su hoat dong co ban cua tai khoan (Admin xem).
--
-- Mot dong / mot su kien lam DOI TRANG THAI tai khoan. Ghi trong CUNG transaction
-- voi thay doi do nen khong co chuyen doi trang thai ma thieu lich su (hoac nguoc
-- lai). Chi them, khong sua / xoa - mo khoa khong con xoa mat dau vet lan khoa:
-- users.suspended_* chi la trang thai HIEN TAI, lich su nam o day.
--
--   action   - SUSPENDED | UNSUSPENDED | LOGIN_LOCKED | PASSWORD_CHANGED |
--              PASSWORD_RESET (enum AccountActivityType, luu dang chuoi).
--   actor_id - users.id cua Admin thuc hien (SUSPENDED/UNSUSPENDED); NULL = chinh
--              nguoi dung hoac he thong. Khong FK: xoa Admin khong keo theo lich su.
--   reason   - ly do khoa (SUSPENDED); NULL voi cac su kien khac.
--
-- DANH SO: V8 noi tiep V7 cua cung PR; V6 van do PBL6-46 giu - xem ghi chu o V7.
-- =============================================================================

CREATE TABLE account_activities (
    id         VARCHAR(26)  PRIMARY KEY,
    user_id    VARCHAR(26)  NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    action     VARCHAR(40)  NOT NULL,
    actor_id   VARCHAR(26),
    reason     VARCHAR(500),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

-- Xem lich su mot tai khoan, moi nhat truoc (API phan trang cho Admin).
CREATE INDEX idx_account_activities_user_created ON account_activities (user_id, created_at);
