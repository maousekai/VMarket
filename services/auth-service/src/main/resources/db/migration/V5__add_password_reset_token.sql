-- =============================================================================
-- PBL6-45 (FR-AUTH-04) - Quen mat khau: ma 6 so gui qua email (giong PBL6-44).
--
-- password_reset_token: mot dong / mot lan phat ma. Key theo user_id (khac
--   email_otp key theo email) - muc tieu doi mat khau LUON LA user da ton tai,
--   nen dung FK that thay vi khoa theo chuoi email.
--   code_hash = BCrypt cua ma 6 so. attempts: dem so lan nhap sai; >= gioi han
--   cau hinh -> ma vo hieu. consumed_at: != NULL -> ma da dung / da bi vo hieu.
-- =============================================================================

CREATE TABLE password_reset_token (
    id          VARCHAR(26)  PRIMARY KEY,
    user_id     VARCHAR(26)  NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    code_hash   VARCHAR(255) NOT NULL,
    expires_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    attempts    INT          NOT NULL DEFAULT 0,
    consumed_at TIMESTAMP WITH TIME ZONE,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

-- Tra cuu ma moi nhat cua mot user + dem so lan phat trong 1 gio (rate limit).
CREATE INDEX idx_password_reset_token_user_created ON password_reset_token (user_id, created_at);
