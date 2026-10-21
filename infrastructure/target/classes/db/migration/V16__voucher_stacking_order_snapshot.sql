ALTER TABLE orders ADD COLUMN IF NOT EXISTS promotion_reservation_id UUID;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS item_discount DECIMAL(12,2);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS shipping_discount DECIMAL(12,2);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS customer_shipping_fee DECIMAL(12,2);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS gross_shipping_fee DECIMAL(12,2);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS platform_subsidy DECIMAL(12,2);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS shop_discount DECIMAL(12,2);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS promotion_breakdown TEXT;

UPDATE orders
SET item_discount = COALESCE(item_discount, discount_amount, 0),
    shipping_discount = COALESCE(shipping_discount, 0),
    gross_shipping_fee = COALESCE(gross_shipping_fee, shipping_fee, 0),
    customer_shipping_fee = COALESCE(customer_shipping_fee, shipping_fee, 0),
    platform_subsidy = COALESCE(platform_subsidy, 0),
    shop_discount = COALESCE(shop_discount, discount_amount, 0)
WHERE item_discount IS NULL
   OR shipping_discount IS NULL
   OR gross_shipping_fee IS NULL
   OR customer_shipping_fee IS NULL
   OR platform_subsidy IS NULL
   OR shop_discount IS NULL;

ALTER TABLE orders ADD CONSTRAINT uk_orders_promotion_reservation
    UNIQUE (promotion_reservation_id);
CREATE INDEX IF NOT EXISTS idx_orders_promotion_reservation
    ON orders (promotion_reservation_id);
