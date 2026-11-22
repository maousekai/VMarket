-- PBL6-35: danh gia chi hien tu khi nhan hang den truoc khi tra hang. Khi yeu cau tra hang / hoan
-- tien duoc chot (Order Service phat ReturnResolved), danh gia cua don chuyen sang RETURNED: khong
-- hien cong khai, khong tinh diem, admin khong khoi phuc duoc.
ALTER TABLE reviews DROP CONSTRAINT ck_review_status;
ALTER TABLE reviews ADD CONSTRAINT ck_review_status CHECK (status IN ('VISIBLE', 'HIDDEN', 'RETURNED'));

-- Don da tra hang / hoan tien (tu su kien ReturnResolved). Chan viet danh gia moi cho don nay;
-- order_id la khoa nen su kien lap lai (at-least-once) chi ghi mot lan.
CREATE TABLE returned_orders (
    order_id VARCHAR(64) PRIMARY KEY,
    return_id VARCHAR(64) NOT NULL,
    resolved_at TIMESTAMP WITH TIME ZONE NOT NULL
);
