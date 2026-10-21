ALTER TABLE orders
    ADD COLUMN IF NOT EXISTS inventory_reservation_id UUID;

-- Both PostgreSQL and H2 permit multiple NULL values in a unique index, so
-- this enforces one linked reservation per order without a PostgreSQL-only
-- partial-index predicate.
CREATE UNIQUE INDEX IF NOT EXISTS uk_orders_inventory_reservation
    ON orders (inventory_reservation_id);
