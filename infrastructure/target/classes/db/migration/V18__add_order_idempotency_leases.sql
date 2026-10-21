ALTER TABLE order_create_idempotency_receipts
    ADD COLUMN IF NOT EXISTS processing_token UUID NULL;

ALTER TABLE order_create_idempotency_receipts
    ADD COLUMN IF NOT EXISTS processing_until TIMESTAMP WITH TIME ZONE NULL;

CREATE INDEX IF NOT EXISTS idx_order_create_idempotency_processing
    ON order_create_idempotency_receipts (principal_id, idempotency_key, processing_until);
