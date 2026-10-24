CREATE TABLE shippers (
    id VARCHAR(26) PRIMARY KEY, provision_key VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(100) NOT NULL, phone VARCHAR(20) NOT NULL,
    province VARCHAR(100) NOT NULL, district VARCHAR(100) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE, created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE TABLE delivery_assignments (
    id VARCHAR(36) PRIMARY KEY, order_id VARCHAR(26) NOT NULL UNIQUE,
    buyer_id VARCHAR(26) NOT NULL, shipper_id VARCHAR(26) REFERENCES shippers(id),
    status VARCHAR(30) NOT NULL, attempt INTEGER NOT NULL DEFAULT 1,
    payment_method VARCHAR(20) NOT NULL, cod_amount BIGINT NOT NULL,
    province VARCHAR(100) NOT NULL, district VARCHAR(100) NOT NULL,
    order_snapshot TEXT NOT NULL, version BIGINT NOT NULL DEFAULT 0,
    latitude DOUBLE PRECISION, longitude DOUBLE PRECISION, location_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CHECK (attempt BETWEEN 1 AND 3), CHECK (cod_amount >= 0)
);
CREATE TABLE delivery_handoffs (
    assignment_id VARCHAR(36) NOT NULL REFERENCES delivery_assignments(id),
    shop_id VARCHAR(26) NOT NULL, seller_id VARCHAR(26) NOT NULL, pickup_snapshot TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL, PRIMARY KEY (assignment_id, shop_id)
);
CREATE TABLE delivery_status_history (
    id VARCHAR(36) PRIMARY KEY, assignment_id VARCHAR(36) NOT NULL REFERENCES delivery_assignments(id),
    status VARCHAR(30) NOT NULL, actor_id VARCHAR(26) NOT NULL, reason VARCHAR(255), attempt INTEGER NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE TABLE route_logs (
    id VARCHAR(36) PRIMARY KEY, assignment_id VARCHAR(36) NOT NULL REFERENCES delivery_assignments(id),
    sequence BIGINT NOT NULL, latitude DOUBLE PRECISION NOT NULL, longitude DOUBLE PRECISION NOT NULL,
    recorded_at TIMESTAMP WITH TIME ZONE NOT NULL, UNIQUE (assignment_id, sequence)
);
CREATE TABLE delivery_outbox (
    id VARCHAR(36) PRIMARY KEY, assignment_id VARCHAR(36) NOT NULL REFERENCES delivery_assignments(id),
    event_type VARCHAR(80) NOT NULL, payload TEXT NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_delivery_shipper_status ON delivery_assignments(shipper_id, status);
CREATE INDEX idx_delivery_waiting ON delivery_assignments(status, province, district);
CREATE INDEX idx_delivery_history ON delivery_status_history(assignment_id, occurred_at);
