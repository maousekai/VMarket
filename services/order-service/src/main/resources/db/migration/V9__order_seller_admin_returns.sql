-- =============================================================================
-- PBL6-17 - Order Service: hoàn thiện xử lý đơn hàng Seller/Admin và Trả hàng
-- (FR-ORD-02, FR-ORD-04, FR-ORD-05, FR-ORD-06, FR-ORD-07).
-- =============================================================================

CREATE TABLE order_returns (
    id              VARCHAR(26)  PRIMARY KEY,
    order_id        VARCHAR(26)  NOT NULL REFERENCES orders(id),
    user_id         VARCHAR(26)  NOT NULL,
    shop_id         VARCHAR(26)  NOT NULL,
    status          VARCHAR(20)  NOT NULL,
    reason          VARCHAR(500) NOT NULL,
    evidence_urls   TEXT,
    seller_note     VARCHAR(500),
    admin_note      VARCHAR(500),
    refund_amount   NUMERIC(12,2) NOT NULL DEFAULT 0,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    resolved_at     TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_order_returns_order_id ON order_returns (order_id);
CREATE INDEX idx_order_returns_user_id ON order_returns (user_id);
CREATE INDEX idx_order_returns_shop_id ON order_returns (shop_id);
CREATE INDEX idx_order_returns_status ON order_returns (status);

CREATE TABLE order_return_items (
    id              VARCHAR(26)  PRIMARY KEY,
    return_id       VARCHAR(26)  NOT NULL REFERENCES order_returns(id),
    order_item_id   VARCHAR(26)  NOT NULL REFERENCES order_items(id),
    product_id      VARCHAR(26)  NOT NULL,
    variant_id      VARCHAR(26),
    quantity        INT          NOT NULL,
    unit_price      NUMERIC(12,2) NOT NULL,
    line_total      NUMERIC(12,2) NOT NULL
);

CREATE INDEX idx_order_return_items_return_id ON order_return_items (return_id);
CREATE INDEX idx_order_items_shop_id ON order_items (shop_id);
CREATE INDEX idx_orders_status_updated ON orders (status, updated_at);

