CREATE TABLE IF NOT EXISTS restaurant_decision_receipts (
    event_id UUID PRIMARY KEY,
    order_id BIGINT NOT NULL,
    restaurant_id BIGINT NOT NULL,
    decision VARCHAR(16) NOT NULL,
    payload_fingerprint VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_order_restaurant_decision_order UNIQUE (order_id),
    CONSTRAINT ck_order_restaurant_decision_type CHECK (decision IN ('CONFIRMED', 'REJECTED'))
);

CREATE INDEX IF NOT EXISTS idx_order_restaurant_decision_restaurant
    ON restaurant_decision_receipts (restaurant_id, created_at);
