CREATE SEQUENCE payment_code_seq START WITH 1000000 INCREMENT BY 1;
CREATE TABLE payment_transactions (
    id BIGINT PRIMARY KEY, order_id VARCHAR(26) NOT NULL UNIQUE, buyer_id VARCHAR(26) NOT NULL,
    amount BIGINT NOT NULL CHECK (amount > 0), method VARCHAR(10) NOT NULL, status VARCHAR(20) NOT NULL,
    payment_link_id VARCHAR(64), checkout_url VARCHAR(2048), qr_code VARCHAR(4096), reference VARCHAR(128) UNIQUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL, expires_at TIMESTAMP WITH TIME ZONE,
    paid_at TIMESTAMP WITH TIME ZONE, cancel_pending BOOLEAN NOT NULL DEFAULT FALSE,
    next_cancel_attempt_at TIMESTAMP WITH TIME ZONE, cancel_attempts INTEGER NOT NULL DEFAULT 0,
    reconciliation_required BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE TABLE payment_order_states (order_id VARCHAR(26) PRIMARY KEY, cancelled BOOLEAN NOT NULL DEFAULT FALSE);
CREATE INDEX idx_payment_cancel_retry ON payment_transactions(cancel_pending, next_cancel_attempt_at, id);
CREATE INDEX idx_payments_buyer ON payment_transactions(buyer_id, created_at);
CREATE INDEX idx_payments_expiry ON payment_transactions(status, expires_at);
CREATE TABLE refund_requests (
    id VARCHAR(36) PRIMARY KEY, payment_id BIGINT NOT NULL UNIQUE REFERENCES payment_transactions(id),
    amount BIGINT NOT NULL, reason VARCHAR(500) NOT NULL, status VARCHAR(20) NOT NULL,
    requested_at TIMESTAMP WITH TIME ZONE NOT NULL, completed_at TIMESTAMP WITH TIME ZONE,
    confirmed_by VARCHAR(26), transfer_reference VARCHAR(128)
);

CREATE TABLE event_outbox (id VARCHAR(36) PRIMARY KEY, event_type VARCHAR(80) NOT NULL, payload TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL);
CREATE INDEX idx_outbox_created ON event_outbox(created_at);
