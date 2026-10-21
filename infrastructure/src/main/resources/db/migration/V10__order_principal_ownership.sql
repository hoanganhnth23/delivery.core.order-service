-- Additive customer ownership migration. user_id remains the legacy user-profile reference.
ALTER TABLE orders ADD COLUMN IF NOT EXISTS user_principal_id BIGINT;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS creator_principal_id BIGINT;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS cancelled_by_principal_id BIGINT;
CREATE INDEX IF NOT EXISTS idx_orders_user_principal_created
    ON orders (user_principal_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_orders_creator_principal_created
    ON orders (creator_principal_id, created_at DESC);
