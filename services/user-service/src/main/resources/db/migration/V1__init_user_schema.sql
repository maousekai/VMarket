-- =============================================================================
-- PBL6-13 - User Service: schema khoi tao (UserProfile, Address).
--
-- Quy uoc dung chung voi auth-service (xem V1__init_auth_schema.sql):
--   - Khoa chinh: ULID (Crockford base32, 26 ky tu), sinh o tang app (BaseEntity).
--   - Kieu cot dinh danh: VARCHAR(26) - khop mapping mac dinh cua Hibernate cho
--     String, tranh loi 'ddl-auto: validate' giua char/varchar.
--   - Thoi gian: TIMESTAMPTZ, luu UTC.
--
-- QUAN TRONG - user_id KHONG co FOREIGN KEY:
--   users nam trong CSDL vmarket_auth cua auth-service, con bang nay nam trong
--   vmarket_user. SRS muc 5 quy dinh database-per-service nen KHONG duoc tao FK
--   xuyen CSDL (va Postgres cung khong cho). Rang buoc toan ven o day la ngu
--   nghia: user_id luon la users.id do Identity Service cap, lay tu claim `sub`
--   cua access token da verify. Khi xoa tai khoan, auth-service phat su kien va
--   user-service tu don du lieu cua minh (thuoc ticket khac).
-- =============================================================================

CREATE TABLE user_profiles (
    -- Ho so 1-1 voi tai khoan. user_id UNIQUE chinh la rang buoc "moi tai khoan
    -- chi co dung mot ho so".
    id            VARCHAR(26)  PRIMARY KEY,
    user_id       VARCHAR(26)  NOT NULL UNIQUE,
    full_name     VARCHAR(100),
    avatar_url    VARCHAR(500),
    phone         VARCHAR(20),
    date_of_birth DATE,
    -- 20 chu khong phai 10: gia tri dai nhat cua enum Gender la UNDISCLOSED (11 ky
    -- tu). De VARCHAR(10) thi insert gia tri do se loi ngay tren PostgreSQL.
    gender        VARCHAR(20),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Admin tim kiem theo so dien thoai (FR-USER-04). Tim theo ho ten dung ILIKE
-- '%...%' nen index B-tree khong giup duoc; voi quy mo do an thi seq scan chap
-- nhan duoc, khi du lieu lon can bat extension pg_trgm + index GIN.
CREATE INDEX idx_user_profiles_phone ON user_profiles (phone);

CREATE TABLE addresses (
    id             VARCHAR(26)  PRIMARY KEY,
    user_id        VARCHAR(26)  NOT NULL,
    -- Nguoi nhan co the khac chu tai khoan (gui qua cho nguoi khac) nen luu rieng,
    -- khong lay tu user_profiles.
    recipient_name VARCHAR(100) NOT NULL,
    phone          VARCHAR(20)  NOT NULL,
    province       VARCHAR(100) NOT NULL,
    district       VARCHAR(100) NOT NULL,
    ward           VARCHAR(100) NOT NULL,
    street_address VARCHAR(255) NOT NULL,
    note           VARCHAR(255),
    is_default     BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Liet ke so dia chi cua mot user
CREATE INDEX idx_addresses_user_id ON addresses (user_id);

-- FR-USER-02 "dat mot dia chi mac dinh": rang buoc TOI DA MOT dia chi mac dinh
-- cho moi user, cuong che o tang CSDL bang partial unique index.
--
-- Vi sao can ca o DB du tang service da xu ly: hai request "dat mac dinh" chay
-- song song deu doc thay dia chi cu roi cung ghi is_default = true -> khong co
-- index nay thi user co 2 dia chi mac dinh va khong the biet don hang lay cai
-- nao. Co index thi request thu hai fail, service dich thanh 409.
--
-- LUU Y: test chay tren H2 voi ddl-auto: create-drop nen KHONG co index nay;
-- H2 khong ho tro partial index. Test bao ve bat bien bang tang service.
CREATE UNIQUE INDEX uq_addresses_one_default_per_user
    ON addresses (user_id)
    WHERE is_default;
