-- =============================================================================
-- Review PR 68 lan 2, muc 4.7 + 4.10:
--  - Trang thai don moi DELIVERY_FAILED / RETURNED_TO_SELLER (cot status VARCHAR(20) du dai).
--    Don truoc day bi chuyen CANCELLED do hang hoan ve shop (DeliveryStatusChanged RETURNED,
--    delivery_version > 0) duoc chuyen sang RETURNED_TO_SELLER.
--  - Toa do diem giao chep tu dia chi luc dat hang.
-- =============================================================================
ALTER TABLE orders ADD COLUMN latitude DOUBLE PRECISION;
ALTER TABLE orders ADD COLUMN longitude DOUBLE PRECISION;

UPDATE orders SET status = 'RETURNED_TO_SELLER'
 WHERE status = 'CANCELLED' AND delivery_version > 0;
