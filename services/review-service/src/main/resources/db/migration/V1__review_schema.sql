-- PBL6-22 Review Service: Review, ReviewImage, SellerReply + outbox phat ReviewCreated.
-- Id theo convention du an: ULID 26 ky tu (order_id cung la ULID cua Order Service).

CREATE TABLE reviews (
    id VARCHAR(26) PRIMARY KEY,
    order_id VARCHAR(26) NOT NULL,
    product_id VARCHAR(64) NOT NULL,
    variant_id VARCHAR(64),
    shop_id VARCHAR(64) NOT NULL,
    buyer_id VARCHAR(64) NOT NULL,
    rating INTEGER NOT NULL,
    comment VARCHAR(2000),
    status VARCHAR(20) NOT NULL,
    moderation_reason VARCHAR(500),
    moderated_by VARCHAR(64),
    moderated_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    -- FR-REV-01: moi san pham trong don chi danh gia mot lan (chot o tang DB de chong race).
    CONSTRAINT uq_review_order_product UNIQUE (order_id, product_id),
    CONSTRAINT ck_review_rating CHECK (rating BETWEEN 1 AND 5),
    CONSTRAINT ck_review_status CHECK (status IN ('VISIBLE', 'HIDDEN'))
);
-- Moi index khop dung (bo loc + thu tu sap xep created_at desc, id desc) cua mot truy van:
-- danh sach cong khai cua san pham / gian hang (status = VISIBLE)
CREATE INDEX idx_review_product ON reviews (product_id, status, created_at, id);
CREATE INDEX idx_review_shop_status ON reviews (shop_id, status, created_at, id);
-- danh sach cua seller khong loc trang thai
CREATE INDEX idx_review_shop ON reviews (shop_id, created_at, id);
-- admin loc theo trang thai
CREATE INDEX idx_review_status ON reviews (status, created_at, id);
-- danh gia cua toi
CREATE INDEX idx_review_buyer ON reviews (buyer_id, created_at, id);

CREATE TABLE review_images (
    id VARCHAR(26) PRIMARY KEY,
    review_id VARCHAR(26) NOT NULL REFERENCES reviews (id) ON DELETE CASCADE,
    url VARCHAR(1000) NOT NULL,
    sort_order INTEGER NOT NULL,
    CONSTRAINT uq_review_image_order UNIQUE (review_id, sort_order),
    CONSTRAINT ck_review_image_order CHECK (sort_order BETWEEN 0 AND 4)
);

CREATE TABLE seller_replies (
    id VARCHAR(26) PRIMARY KEY,
    review_id VARCHAR(26) NOT NULL UNIQUE REFERENCES reviews (id) ON DELETE CASCADE,
    seller_id VARCHAR(64) NOT NULL,
    content VARCHAR(1000) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

-- Outbox: chi luu productId; aggregate tinh LUC PHAT (duoi khoa product_rating_revisions)
-- de ban tin phat sau cung luon phan anh du lieu da commit moi nhat.
CREATE TABLE review_outbox (
    id VARCHAR(26) PRIMARY KEY,
    event_type VARCHAR(80) NOT NULL,
    review_id VARCHAR(26) NOT NULL,
    product_id VARCHAR(64) NOT NULL,
    state VARCHAR(10) NOT NULL DEFAULT 'PENDING',
    retry_attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_error VARCHAR(500),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_review_outbox_state CHECK (state IN ('PENDING', 'DEAD'))
);
CREATE INDEX idx_review_outbox_due ON review_outbox (state, next_attempt_at, created_at, id);

-- Moi san pham mot dong: vua la KHOA tuan tu hoa "tinh aggregate + publish" giua nhieu
-- replica (UPDATE giu khoa dong den het transaction), vua cap REVISION tang dan dua vao
-- ReviewCreated. Consumer so revision thay vi timestamp envelope (dong ho tung replica co
-- the lech/lui). Dung bang thuong thay vi pg_advisory_xact_lock de chay duoc ca tren H2.
CREATE TABLE product_rating_revisions (
    product_id VARCHAR(64) PRIMARY KEY,
    revision BIGINT NOT NULL DEFAULT 0
);
