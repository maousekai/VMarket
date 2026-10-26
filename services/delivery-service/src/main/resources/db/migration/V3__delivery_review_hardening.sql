ALTER TABLE delivery_assignments ADD COLUMN location_sequence BIGINT NOT NULL DEFAULT 0;
ALTER TABLE delivery_assignments ADD COLUMN tracking_sequence BIGINT NOT NULL DEFAULT 0;
UPDATE delivery_assignments SET tracking_sequence = version;
UPDATE delivery_assignments SET location_sequence = COALESCE(
    (SELECT MAX(r.sequence) FROM route_logs r WHERE r.assignment_id = delivery_assignments.id), 0);

ALTER TABLE delivery_status_history ADD COLUMN shipper_id VARCHAR(26);
ALTER TABLE delivery_status_history ADD COLUMN previous_shipper_id VARCHAR(26);
ALTER TABLE delivery_status_history ADD COLUMN area_override BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE delivery_outbox ADD COLUMN state VARCHAR(10) NOT NULL DEFAULT 'PENDING';
ALTER TABLE delivery_outbox ADD COLUMN last_error VARCHAR(120);
CREATE INDEX idx_delivery_outbox_pending ON delivery_outbox(state, next_attempt_at, created_at, id);
