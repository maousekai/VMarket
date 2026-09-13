-- =============================================================================
-- PBL6-44 (FR-AUTH-01) - Xac thuc email bang OTP (ma 6 so gui qua email).
--
-- users.password_hash -> NULLABLE: user tao qua luong OTP khong co mat khau
--   (dang nhap lai bang OTP, hoac dat mat khau sau - ngoai pham vi ticket nay).
--
-- email_otp: mot dong / mot lan phat OTP. Key theo email (email co the chua co
--   user). code_hash = BCrypt cua ma 6 so - DB lo van khong lay duoc ma.
--   attempts: dem so lan nhap sai; >= 5 -> ma vo hieu.
--   consumed_at: != NULL -> ma da dung / da bi vo hieu.
-- =============================================================================

ALTER TABLE users ALTER COLUMN password_hash DROP NOT NULL;

CREATE TABLE email_otp (
    id          VARCHAR(26)  PRIMARY KEY,
    email       VARCHAR(320) NOT NULL,
    code_hash   VARCHAR(255) NOT NULL,
    expires_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    attempts    INT          NOT NULL DEFAULT 0,
    consumed_at TIMESTAMP WITH TIME ZONE,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

-- Tra cuu OTP moi nhat cua mot email + dem so lan phat trong 1 gio (rate limit).
CREATE INDEX idx_email_otp_email_created ON email_otp (email, created_at);
