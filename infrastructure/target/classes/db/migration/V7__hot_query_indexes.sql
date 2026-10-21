-- Evidence: scripts/verify-hot-query-plans.sh. The existing customer,
-- restaurant, shipper, status and creator indexes already support their
-- bounded history paths. Add only the missing global timeline index and align
-- the pending-outbox index with retry-first ordering plus the deterministic id
-- tie-breaker.
CREATE INDEX IF NOT EXISTS idx_orders_created_id
    ON orders (created_at DESC, id DESC);

DROP INDEX IF EXISTS idx_order_outbox_pending;
CREATE INDEX idx_order_outbox_pending
    ON outbox_events (status, next_attempt_at, created_at, id);
