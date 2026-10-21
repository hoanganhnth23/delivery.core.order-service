-- Some older operator fixtures created the order outbox with a status check
-- that pre-dates lease claiming. Replace it in a forward-only migration so a
-- rolling deployment can safely write IN_FLIGHT rows.
ALTER TABLE outbox_events
    DROP CONSTRAINT IF EXISTS ck_order_outbox_status;
ALTER TABLE outbox_events
    DROP CONSTRAINT IF EXISTS outbox_events_status_check;

ALTER TABLE outbox_events
    ADD CONSTRAINT ck_order_outbox_status
    CHECK (status IN ('PENDING', 'IN_FLIGHT', 'SENT', 'DEAD'));

CREATE INDEX IF NOT EXISTS idx_order_outbox_claimable
    ON outbox_events (status, next_attempt_at, lease_until, created_at, id);
