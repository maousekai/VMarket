-- =============================================================================
-- PBL6-13 - User Service: so dia chi giao hang (Address - FR-USER-02).
--
-- Theo dung quy uoc cua V1__init_user_schema.sql: khoa chinh ULID VARCHAR(26),
-- thoi gian TIMESTAMP WITH TIME ZONE (dang chuan SQL, khong dung bi danh
-- TIMESTAMPTZ) de FlywayMigrationTest chay duoc script nay tren H2.
--
-- user_id KHONG co FOREIGN KEY sang users (khac CSDL) - xem ghi chu o V1.
-- =============================================================================

CREATE TABLE addresses (
    id             VARCHAR(26)  PRIMARY KEY,
    user_id        VARCHAR(26)  NOT NULL,
    -- Nguoi nhan co the khac chu tai khoan (gui qua cho nguoi khac) nen luu rieng,
    -- khong lay tu user_profiles. Dia chi da dung cho don hang cu cung khong duoc
    -- doi theo khi chu tai khoan sua ho so.
    recipient_name VARCHAR(100) NOT NULL,
    phone          VARCHAR(20)  NOT NULL,
    province       VARCHAR(100) NOT NULL,
    district       VARCHAR(100) NOT NULL,
    ward           VARCHAR(100) NOT NULL,
    street_address VARCHAR(255) NOT NULL,
    note           VARCHAR(255),
    is_default     BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at     TIMESTAMP WITH TIME ZONE  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMP WITH TIME ZONE  NOT NULL DEFAULT now()
);

-- Liet ke so dia chi cua mot user.
CREATE INDEX idx_addresses_user_id ON addresses (user_id);
