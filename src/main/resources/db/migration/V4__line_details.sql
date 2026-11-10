-- Per-line details from the item popup: price changed at the till, line discount, kitchen note.
ALTER TABLE order_item ADD COLUMN menu_price NUMERIC(10,2);
ALTER TABLE order_item ADD COLUMN line_discount NUMERIC(10,2) NOT NULL DEFAULT 0;
ALTER TABLE order_item ADD COLUMN note VARCHAR(120) NOT NULL DEFAULT '';
UPDATE order_item SET menu_price = price WHERE menu_price IS NULL;
