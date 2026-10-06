ALTER TABLE orders ADD COLUMN payment_method VARCHAR(10) NOT NULL DEFAULT 'COD';
ALTER TABLE orders ADD COLUMN payment_expires_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE orders ADD COLUMN stock_reserved BOOLEAN NOT NULL DEFAULT FALSE;
CREATE INDEX idx_orders_payment_expiry ON orders(status, payment_expires_at);

CREATE TABLE event_outbox (id VARCHAR(36) PRIMARY KEY, event_type VARCHAR(80) NOT NULL, payload TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL);
CREATE INDEX idx_outbox_created ON event_outbox(created_at);
