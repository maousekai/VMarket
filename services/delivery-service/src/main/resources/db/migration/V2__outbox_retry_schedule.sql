ALTER TABLE delivery_outbox ADD COLUMN retry_attempts INTEGER NOT NULL DEFAULT 0;
ALTER TABLE delivery_outbox ADD COLUMN next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP;
CREATE INDEX idx_delivery_outbox_due ON delivery_outbox(next_attempt_at, created_at, id);
