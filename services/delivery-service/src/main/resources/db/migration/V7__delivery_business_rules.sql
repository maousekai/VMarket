-- =============================================================================
-- Review PR 68 lan 2: nghiep vu giao hang da chot.
--  - ward + khu giao hang (district) cho phan cong theo khu
--  - ly do that bai chuan, gio that bai gan nhat (giao lai sau >= 2 gio)
--  - co "Can xu ly" khi khoa shipper dang giu hang
--  - mot nguon GPS moi chuyen (mobile uu tien)
--  - lay hang / tra hang theo tung shop, seller xac nhan nhan lai
--  - publicReason (nguoi mua thay) tach khoi ghi chu noi bo
-- Cu phap chay duoc tren PostgreSQL va H2.
-- =============================================================================
ALTER TABLE delivery_assignments ADD COLUMN ward VARCHAR(100);
ALTER TABLE delivery_assignments ADD COLUMN failure_reason_code VARCHAR(40);
ALTER TABLE delivery_assignments ADD COLUMN last_failed_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE delivery_assignments ADD COLUMN needs_attention BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE delivery_assignments ADD COLUMN gps_source VARCHAR(10);
ALTER TABLE delivery_assignments ADD COLUMN gps_source_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE delivery_assignments ADD COLUMN dropped_off_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE delivery_assignments ADD COLUMN assignment_failure_notified BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE delivery_handoffs ADD COLUMN picked_up_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE delivery_handoffs ADD COLUMN returned_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE delivery_handoffs ADD COLUMN return_received_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE delivery_status_history ADD COLUMN reason_code VARCHAR(40);
ALTER TABLE delivery_status_history ADD COLUMN public_reason VARCHAR(255);

-- Chuyen cu da qua buoc lay hang: coi nhu moi shop da duoc lay.
UPDATE delivery_handoffs SET picked_up_at = created_at
 WHERE assignment_id IN (SELECT id FROM delivery_assignments
                          WHERE status IN ('PICKED_UP','IN_TRANSIT','FAILED','RETURNING','RETURNED','DELIVERED'));

CREATE INDEX idx_delivery_dropped_off ON delivery_assignments(status, dropped_off_at);
