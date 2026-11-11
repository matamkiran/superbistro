-- Running table bills: dishes added later belong to a new kitchen round (each round gets its own KOT).
ALTER TABLE order_item ADD COLUMN kot_round INTEGER NOT NULL DEFAULT 1;
ALTER TABLE customer_order ADD COLUMN kot_round INTEGER NOT NULL DEFAULT 1;
-- Home delivery address (kept per order; the customer list remembers the latest one).
ALTER TABLE customer_order ADD COLUMN address VARCHAR(300) NOT NULL DEFAULT '';
-- Who cancelled an order, when, and why.
ALTER TABLE customer_order ADD COLUMN cancel_reason VARCHAR(200) NOT NULL DEFAULT '';
ALTER TABLE customer_order ADD COLUMN cancelled_by UUID;
ALTER TABLE customer_order ADD COLUMN cancelled_at TIMESTAMP WITH TIME ZONE;
CREATE INDEX idx_order_restaurant_phone ON customer_order(restaurant_id, phone);
