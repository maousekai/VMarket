ALTER TABLE orders ADD COLUMN delivery_version BIGINT NOT NULL DEFAULT 0;
CREATE TABLE delivery_order_outbox (
    id VARCHAR(36) PRIMARY KEY,
    event_type VARCHAR(80) NOT NULL,
    payload TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
