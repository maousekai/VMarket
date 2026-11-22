-- PBL6-35.
--
-- 1. Tra hang theo tung san pham: don tao bang POST /api/orders co the gom nhieu gian hang, ma moi
--    yeu cau tra hang chi thuoc mot gian hang. Tra hang cua shop A khong duoc an danh gia cua shop B.
CREATE TABLE returned_items (
    order_id VARCHAR(64) NOT NULL,
    product_id VARCHAR(64) NOT NULL,
    return_id VARCHAR(64) NOT NULL,
    resolved_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (order_id, product_id)
);
-- Du lieu cu chi biet don nao da tra, khong biet san pham nao. KHONG suy ra tu danh gia: don chua co
-- danh gia se mat dau chan, con san pham cua shop khac bi danh dau nham. Giu lai de
-- LegacyReturnReconciler hoi Order Service cac dong tra hang RESOLVED cua don roi chuyen sang
-- returned_items; trong luc cho, ca don van bi chan nhu truoc.
ALTER TABLE returned_orders RENAME TO legacy_returned_orders;

-- 2. Khoa theo don: gui danh gia va chot tra hang cung giu khoa dong nay trong transaction, nen mot
--    ben luon thay du lieu da commit cua ben kia (khong con truong hop ca hai cung thanh cong).
CREATE TABLE review_order_locks (
    order_id VARCHAR(64) PRIMARY KEY
);

-- 3. Anh danh gia: key trong kho anh la ngau nhien (khong chua buyerId), chu so huu luu o day.
CREATE TABLE review_media (
    url VARCHAR(1000) PRIMARY KEY,
    buyer_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
