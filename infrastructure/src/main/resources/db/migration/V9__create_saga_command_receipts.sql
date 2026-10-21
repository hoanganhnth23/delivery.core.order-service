CREATE TABLE saga_command_receipts (
    event_id UUID PRIMARY KEY,
    command_type VARCHAR(64) NOT NULL,
    order_id BIGINT NOT NULL,
    saga_status VARCHAR(64) NOT NULL,
    payload_fingerprint VARCHAR(64) NOT NULL,
    received_at TIMESTAMP NOT NULL
);

CREATE INDEX idx_saga_command_receipts_order
    ON saga_command_receipts (order_id, received_at);
