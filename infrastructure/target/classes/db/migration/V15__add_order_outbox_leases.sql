ALTER TABLE outbox_events
    ADD COLUMN IF NOT EXISTS lease_token UUID NULL;

ALTER TABLE outbox_events
    ADD COLUMN IF NOT EXISTS lease_until TIMESTAMP NULL;

CREATE INDEX IF NOT EXISTS idx_order_outbox_claimable
    ON outbox_events (status, next_attempt_at, lease_until, created_at, id);
