-- =============================================================================
-- PBL6-43 (FR-AUTH-02) - Dang nhap JWT + khoa sau 5 lan sai + xoay vong refresh.
--
-- users:
--   failed_login_attempts - dem so lan dang nhap sai LIEN TIEP; reset ve 0 khi
--                           dang nhap thanh cong.
--   locked_until          - thoi diem het khoa; NULL = khong khoa. Dat = now()+15p
--                           khi failed_login_attempts cham 5 (hoac Admin khoa tay).
--
-- refresh_tokens (xoay vong + phat hien dung lai):
--   revoked_at  - thoi diem token bi thu hoi (rotate / logout / phat hien reuse /
--                 tai khoan bi khoa).
--   replaced_by - id cua refresh token moi thay the (chuoi audit; khong FK de
--                 tranh rang buoc thu tu insert).
--
-- Tra cuu "refresh token con hieu luc cua mot user" da co index idx_refresh_tokens_user_id
-- (tao o V1); bo loc revoked_at IS NULL thuc hien tren tap nho nen khong can
-- partial index rieng.
-- =============================================================================

ALTER TABLE users ADD COLUMN failed_login_attempts INT NOT NULL DEFAULT 0;
ALTER TABLE users ADD COLUMN locked_until          TIMESTAMP WITH TIME ZONE;

ALTER TABLE refresh_tokens ADD COLUMN revoked_at  TIMESTAMP WITH TIME ZONE;
ALTER TABLE refresh_tokens ADD COLUMN replaced_by VARCHAR(26);
