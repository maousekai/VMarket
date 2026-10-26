CREATE TABLE outbox_replay_audit (
    id VARCHAR(36) PRIMARY KEY,
    event_id VARCHAR(36) NOT NULL,
    aggregate_id VARCHAR(26),
    event_type VARCHAR(80) NOT NULL,
    previous_attempts INTEGER NOT NULL,
    previous_error VARCHAR(120),
    replayed_by VARCHAR(128) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    replayed_at TIMESTAMP WITH TIME ZONE NOT NULL
);
-- No FK: retain the audit after the successfully dispatched event is deleted.
CREATE INDEX idx_outbox_replay_event ON outbox_replay_audit(event_id, replayed_at);
