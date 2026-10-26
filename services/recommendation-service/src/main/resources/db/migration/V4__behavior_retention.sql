CREATE INDEX user_event_age_idx ON user_event (occurred_at_ms);

-- Minimal suppression state prevents replay from restoring erased behavior.
CREATE TABLE erased_behavior_user (
    user_hash CHAR(64) PRIMARY KEY
);
