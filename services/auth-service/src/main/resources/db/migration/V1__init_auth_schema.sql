-- =============================================================================
-- PBL6-41 - Auth Service: schema khoi tao (User, Role, UserRole, RefreshToken).
--
-- Nguyen tac: V1 chi chua CAC COT LOI. Cac cot nghiep vu bo sung se do migration
-- rieng cua tung subtask sau tao ra (expand-contract):
--   - V2 (PBL6-43): users.failed_login_attempts, users.locked_until;
--                   refresh_tokens.revoked_at (thu hoi / xoay vong token)
--   - V3 (PBL6-44): users.provider, users.provider_user_id (Google OAuth2)
--   - V4 (PBL6-45): users.email_verified / password_reset_tokens ...
--
-- Khoa chinh: ULID (Crockford base32, 26 ky tu), sinh o tang app (BaseEntity).
-- Kieu cot dinh danh: VARCHAR(26) (khop mapping mac dinh cua Hibernate cho String
-- -> tranh loi 'ddl-auto: validate' giua char/varchar va ngu nghia padding cua
-- bpchar). Thoi gian: TIMESTAMPTZ, luu UTC.
-- =============================================================================

CREATE TABLE roles (
    id         VARCHAR(26)  PRIMARY KEY,
    name       VARCHAR(20)  NOT NULL UNIQUE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE users (
    id            VARCHAR(26)  PRIMARY KEY,
    email         VARCHAR(320) NOT NULL UNIQUE,
    username      VARCHAR(50)  NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE user_roles (
    user_id     VARCHAR(26)  NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role_id     VARCHAR(26)  NOT NULL REFERENCES roles (id) ON DELETE CASCADE,
    assigned_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, role_id)
);

-- Tra cuu "nhung user co role X" (vd: liet ke admin)
CREATE INDEX idx_user_roles_role_id ON user_roles (role_id);

CREATE TABLE refresh_tokens (
    id         VARCHAR(26)  PRIMARY KEY,
    user_id    VARCHAR(26)  NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash VARCHAR(255) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ  NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Tra cuu / thu hoi toan bo token cua mot user
CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens (user_id);

-- -----------------------------------------------------------------------------
-- Seed 5 vai tro RBAC (SRS muc 7.2). ID la ULID co dinh -> migration idempotent
-- va giong nhau giua moi moi truong (dev / prod). Khong tao role dong.
-- -----------------------------------------------------------------------------
INSERT INTO roles (id, name) VALUES
    ('0C6A25VJ1SCTJAZHS4JKK4E5P7', 'GUEST'),
    ('0CQVSEAYSPRZH3X358K1MACKBH', 'BUYER'),
    ('04TSY74KJM7KGTAK139XHGNBB1', 'SELLER'),
    ('0R0H77HF18KPP6AKEBC792EV1Y', 'SHIPPER'),
    ('03QPZFXY4216QVTKBF3WJFYEKV', 'ADMIN');
