CREATE TABLE user_event (
    event_id UUID PRIMARY KEY,
    user_id VARCHAR(128) NOT NULL,
    product_id VARCHAR(128) NOT NULL,
    action VARCHAR(16) NOT NULL CHECK (action IN ('view', 'add_to_cart', 'purchase')),
    occurred_at_ms BIGINT NOT NULL CHECK (occurred_at_ms >= 0)
);

CREATE INDEX user_event_user_product_idx ON user_event (user_id, product_id);
CREATE INDEX user_event_product_user_idx ON user_event (product_id, user_id);
CREATE INDEX user_event_purchases_idx ON user_event (user_id, product_id) WHERE action = 'purchase';
