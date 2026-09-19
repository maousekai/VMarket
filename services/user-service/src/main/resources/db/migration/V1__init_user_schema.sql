-- =============================================================================
-- PBL6-13 - User Service: schema khoi tao (UserProfile - FR-USER-01).
--
-- Quy uoc dung chung voi auth-service (xem V1__init_auth_schema.sql):
--   - Khoa chinh: ULID (Crockford base32, 26 ky tu), sinh o tang app (BaseEntity).
--   - Kieu cot dinh danh: VARCHAR(26) - khop mapping mac dinh cua Hibernate cho
--     String, tranh loi 'ddl-auto: validate' giua char/varchar.
--   - Thoi gian: TIMESTAMP WITH TIME ZONE, luu UTC. Viet dang chuan SQL chu KHONG
--     dung bi danh TIMESTAMPTZ cua Postgres: H2 (MODE=PostgreSQL) khong hieu bi
--     danh do, ma FlywayMigrationTest chay chinh script nay tren H2 de doi chieu
--     entity voi schema migration.
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
    created_at    TIMESTAMP WITH TIME ZONE  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMP WITH TIME ZONE  NOT NULL DEFAULT now()
);
