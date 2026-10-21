ALTER TABLE orders ADD COLUMN voucher_reservation_id UUID;
ALTER TABLE orders ADD COLUMN flash_sale_reservation_id UUID;
ALTER TABLE orders ADD CONSTRAINT uk_orders_voucher_reservation UNIQUE (voucher_reservation_id);
ALTER TABLE orders ADD CONSTRAINT uk_orders_flash_reservation UNIQUE (flash_sale_reservation_id);
