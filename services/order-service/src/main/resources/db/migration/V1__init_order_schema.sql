-- =============================================================================
-- PBL6-17 - Order Service: schema khoi tao (Order - FR-ORDER-01/02/03).
--
-- Quy uoc dung chung voi auth-service / user-service:
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
--   vmarket_order. SRS muc 5 quy dinh database-per-service nen KHONG duoc tao FK
--   xuyen CSDL. Rang buoc toan ven o day la ngu nghia: user_id luon la users.id
--   do Identity Service cap, lay tu claim `sub` cua access token da verify.
--
--   Nguoc lai, order_items.order_id CCO FOREIGN KEY toi orders(id) vi hai bang
--   nay cung mot CSDL: mot don hang khong the ton tai khong co don chu.
--
-- THIET KE - snapshot dia chi vao don hang:
--   Dia chi giao hang duoc CHEP THANH COT rieng trong orders thay vi tham chieu
--   bang addresses cua user-service. Nguoi dung co the sua/xoa dia chi bat ky luc
--   nao, nhung thong tin van chuyen cua don hang da dat PHAI khong doi theo.
-- =============================================================================

CREATE TABLE orders (
    id              VARCHAR(26)  PRIMARY KEY,
    user_id         VARCHAR(26)  NOT NULL,
    -- 20 chu khong phai 10: gia tri dai nhat cua enum OrderStatus la DELIVERED
    -- (9 ky tu) / CANCELLED (9 ky tu) - de du rong cho trang thai mo rong sau nay.
    status          VARCHAR(20)  NOT NULL,
    recipient_name  VARCHAR(100) NOT NULL,
    phone           VARCHAR(20)  NOT NULL,
    province        VARCHAR(100) NOT NULL,
    district        VARCHAR(100) NOT NULL,
    ward            VARCHAR(100) NOT NULL,
    street_address  VARCHAR(255) NOT NULL,
    note            VARCHAR(255),
    total_amount    NUMERIC(12,2) NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMP WITH TIME ZONE  NOT NULL DEFAULT now()
);

-- Trang "Don hang cua toi" luon truy van theo user_id va sap moi nhat truoc.
CREATE INDEX idx_orders_user_id_created_at ON orders (user_id, created_at DESC);

CREATE TABLE order_items (
    id          VARCHAR(26)  PRIMARY KEY,
    order_id    VARCHAR(26)  NOT NULL REFERENCES orders(id),
    product_id  VARCHAR(26)  NOT NULL,
    -- San pham khong co bien the thi variant_id NULL.
    variant_id  VARCHAR(26),
    shop_id     VARCHAR(26)  NOT NULL,
    quantity    INT          NOT NULL,
    unit_price  NUMERIC(12,2) NOT NULL,
    line_total  NUMERIC(12,2) NOT NULL
);

CREATE INDEX idx_order_items_order_id ON order_items (order_id);