-- Details printed on the customer's bill.
ALTER TABLE restaurant ADD COLUMN address VARCHAR(200) NOT NULL DEFAULT '';
ALTER TABLE restaurant ADD COLUMN phone VARCHAR(40) NOT NULL DEFAULT '';
ALTER TABLE restaurant ADD COLUMN gstin VARCHAR(20) NOT NULL DEFAULT '';
ALTER TABLE restaurant ADD COLUMN bill_footer VARCHAR(200) NOT NULL DEFAULT '';
