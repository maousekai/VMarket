-- =============================================================================
-- PBL6-14 - Shop Service: schema khoi tao (Shop, ShopStatusHistory - SRS 7.1).
--
-- Quy uoc dung chung voi auth-service / user-service:
--   - Khoa chinh: ULID (Crockford base32, 26 ky tu), sinh o tang app (BaseEntity).
--   - Kieu cot dinh danh: VARCHAR(26) - khop mapping mac dinh cua Hibernate cho
--     String, tranh loi 'ddl-auto: validate' giua char/varchar.
--   - Thoi gian: TIMESTAMP WITH TIME ZONE, luu UTC. Viet dang chuan SQL chu KHONG
--     dung bi danh TIMESTAMPTZ: FlywayMigrationTest chay chinh script nay tren H2
--     (MODE=PostgreSQL) de doi chieu entity voi schema migration.
--   - Chuoi dai dung VARCHAR(n) chu khong TEXT: H2 hieu TEXT la CLOB, khong khop
--     mapping String cua Hibernate khi validate.
--
-- owner_id / changed_by KHONG co FOREIGN KEY: users nam o CSDL vmarket_auth cua
-- auth-service (database-per-service - SRS 5.4). Gia tri luon la claim `sub` cua
-- access token da verify.
-- =============================================================================

CREATE TABLE shops (
    id             VARCHAR(26)   PRIMARY KEY,
    -- Moi tai khoan chi mo DUNG MOT gian hang (uq_shops_owner_id). Rang buoc o
    -- CSDL de hai request dang ky song song khong cung lot qua buoc kiem tra.
    owner_id       VARCHAR(26)   NOT NULL,
    name           VARCHAR(100)  NOT NULL,
    -- Khoa so khop ten: chuan hoa Unicode NFC + gop khoang trang + chu thuong.
    -- UNIQUE tren cot nay (khong phai tren name) de "Gom Hoi An" va "gom  hoi an"
    -- khong cung ton tai - chong gia mao ten gian hang. Dung cot rieng thay vi
    -- unique index tren lower(name) vi H2 khong ho tro index bieu thuc.
    name_key       VARCHAR(100)  NOT NULL,
    description    VARCHAR(2000),
    logo_url       VARCHAR(500),
    cover_url      VARCHAR(500),
    -- Chinh sach doi tra / van chuyen cua gian hang (FR-SHOP-02).
    policies       VARCHAR(5000),
    contact_email  VARCHAR(255)  NOT NULL,
    contact_phone  VARCHAR(20)   NOT NULL,
    -- Dia chi kho / lay hang: shipper den lay hang (UC Delivery) va phan cong theo
    -- khu vuc (FR-SHIP-02) can du cap tinh - quan - phuong.
    province       VARCHAR(100)  NOT NULL,
    district       VARCHAR(100)  NOT NULL,
    ward           VARCHAR(100)  NOT NULL,
    street_address VARCHAR(255)  NOT NULL,
    -- PENDING | ACTIVE | REJECTED | SUSPENDED (enum ShopStatus).
    status         VARCHAR(20)   NOT NULL,
    -- Ly do cua lan tu choi / dinh chi GAN NHAT - de nguoi ban biet phai sua gi.
    -- Lich su day du nam o shop_status_history.
    status_reason  VARCHAR(500),
    -- Lan dau duoc duyet ("tham gia tu"). Giu nguyen khi bi dinh chi roi mo lai.
    approved_at    TIMESTAMP WITH TIME ZONE,
    -- Optimistic locking (@Version): Admin duyet dung luc nguoi ban dang sua ->
    -- mot trong hai nhan 409 thay vi ghi de lang le len nhau.
    version        BIGINT        NOT NULL DEFAULT 0,
    created_at     TIMESTAMP WITH TIME ZONE  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMP WITH TIME ZONE  NOT NULL DEFAULT now(),
    CONSTRAINT uq_shops_owner_id UNIQUE (owner_id),
    CONSTRAINT uq_shops_name_key UNIQUE (name_key)
);

-- Danh sach cho Admin loc theo trang thai (hang doi "Cho duyet"), moi nhat truoc.
CREATE INDEX idx_shops_status_created_at ON shops (status, created_at);

CREATE TABLE shop_status_history (
    id          VARCHAR(26)   PRIMARY KEY,
    -- Cung CSDL nen co FK that. ON DELETE CASCADE: lich su khong co nghia khi
    -- gian hang da bi xoa han (hien chua co luong xoa - phong ve sau).
    shop_id     VARCHAR(26)   NOT NULL REFERENCES shops (id) ON DELETE CASCADE,
    -- NULL o dong dau tien (ho so vua nop).
    from_status VARCHAR(20),
    to_status   VARCHAR(20)   NOT NULL,
    reason      VARCHAR(500),
    -- userId cua nguoi thao tac: chu gian hang (nop / gui lai) hoac Admin.
    changed_by  VARCHAR(26)   NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE  NOT NULL DEFAULT now()
);

CREATE INDEX idx_shop_status_history_shop_id ON shop_status_history (shop_id, created_at);
