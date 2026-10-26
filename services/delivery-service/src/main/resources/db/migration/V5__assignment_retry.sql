ALTER TABLE delivery_assignments ADD COLUMN assignment_attempts INTEGER NOT NULL DEFAULT 0;
ALTER TABLE delivery_assignments ADD COLUMN next_assignment_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE delivery_assignments ADD COLUMN assignment_claim_token VARCHAR(36);
ALTER TABLE delivery_assignments ADD COLUMN assignment_claim_until TIMESTAMP WITH TIME ZONE;
CREATE INDEX idx_assignment_retry ON delivery_assignments(status, next_assignment_at, assignment_claim_until);
