CREATE TABLE item_feature (
    product_id VARCHAR(128) PRIMARY KEY,
    product_version BIGINT NOT NULL CHECK (product_version >= 0),
    snapshot JSONB NOT NULL,
    refreshed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE processed_event (
    event_id UUID PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
