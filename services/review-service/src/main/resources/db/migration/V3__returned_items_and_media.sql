-- PBL6-35.
--
-- 1. Tra hang theo tung san pham: don tao bang POST /api/orders co the gom nhieu gian hang, ma moi
--    yeu cau tra hang chi thuoc mot gian hang. Tra hang cua shop A khong duoc an danh gia cua shop B.
--    Du lieu cu chi biet don nao da tra: chuyen sang cac san pham da co danh gia trong don do.
CREATE TABLE returned_items (
    order_id VARCHAR(64) NOT NULL,
    product_id VARCHAR(64) NOT NULL,
    return_id VARCHAR(64) NOT NULL,
    resolved_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (order_id, product_id)
);
INSERT INTO returned_items (order_id, product_id, return_id, resolved_at)
SELECT DISTINCT o.order_id, r.product_id, o.return_id, o.resolved_at
FROM returned_orders o
JOIN reviews r ON r.order_id = o.order_id;
DROP TABLE returned_orders;

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
