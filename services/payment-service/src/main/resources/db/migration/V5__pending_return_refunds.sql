CREATE TABLE pending_return_refunds (
    id VARCHAR(36) PRIMARY KEY,
    order_id VARCHAR(26) NOT NULL,
    return_id VARCHAR(36) NOT NULL UNIQUE,
    amount BIGINT NOT NULL,
    reason VARCHAR(500) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    processed BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_pending_returns_order ON pending_return_refunds(order_id, processed);

