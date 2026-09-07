-- =============================================================================
-- PBL6-43 (FR-AUTH-02) - Dang nhap JWT + khoa sau 5 lan sai + xoay vong refresh.
--
-- users:
--   failed_login_attempts - dem so lan dang nhap sai LIEN TIEP; reset ve 0 khi
--                           dang nhap thanh cong.
--   locked_until          - thoi diem het khoa; NULL = khong khoa. Dat = now()+15p
--                           khi failed_login_attempts cham 5.
--
-- refresh_tokens (xoay vong + phat hien dung lai):
--   revoked_at  - thoi diem token bi thu hoi (rotate / logout / phat hien reuse).
--   replaced_by - id cua refresh token moi thay the (chuoi audit; khong FK de
--                 tranh rang buoc thu tu insert).
-- =============================================================================

ALTER TABLE users
    ADD COLUMN failed_login_attempts INT         NOT NULL DEFAULT 0,
    ADD COLUMN locked_until          TIMESTAMPTZ;

ALTER TABLE refresh_tokens
    ADD COLUMN revoked_at  TIMESTAMPTZ,
    ADD COLUMN replaced_by VARCHAR(26);

-- Tra cuu cac refresh token con hieu luc cua mot user (thu hoi hang loat khi
-- phat hien reuse hoac khi khoa tai khoan).
CREATE INDEX idx_refresh_tokens_user_active
    ON refresh_tokens (user_id)
    WHERE revoked_at IS NULL;
