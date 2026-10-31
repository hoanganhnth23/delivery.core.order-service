-- Tier 1 Optimization: Hot query composite indexes for timeline queries
-- Evidence: Index scan eliminates Seq Scan on order history queries under concurrency.
CREATE INDEX IF NOT EXISTS idx_orders_user_status_created
    ON orders (user_id, status, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_orders_restaurant_status_created
    ON orders (restaurant_id, status, created_at DESC);
