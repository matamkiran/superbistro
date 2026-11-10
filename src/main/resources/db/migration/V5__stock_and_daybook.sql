-- Store room: raw materials and their stock level.
CREATE TABLE stock_item (
    id             UUID PRIMARY KEY,
    restaurant_id  UUID NOT NULL REFERENCES restaurant(id) ON DELETE CASCADE,
    name           VARCHAR(80)   NOT NULL,
    unit           VARCHAR(12)   NOT NULL,
    category       VARCHAR(40)   NOT NULL DEFAULT 'Other',
    current_qty    NUMERIC(12,3) NOT NULL DEFAULT 0,
    min_qty        NUMERIC(12,3) NOT NULL DEFAULT 0,
    reorder_qty    NUMERIC(12,3) NOT NULL DEFAULT 0,
    last_cost      NUMERIC(10,2),
    active         BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_stock_item_restaurant ON stock_item(restaurant_id);

-- Purchase lists: ORDERED (to bring in) -> RECEIVED, or CANCELLED.
CREATE TABLE purchase (
    id             UUID PRIMARY KEY,
    restaurant_id  UUID NOT NULL REFERENCES restaurant(id) ON DELETE CASCADE,
    supplier       VARCHAR(80)   NOT NULL DEFAULT '',
    note           VARCHAR(300)  NOT NULL DEFAULT '',
    status         VARCHAR(12)   NOT NULL,
    payment        VARCHAR(12),
    total          NUMERIC(12,2),
    created_by     UUID,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    received_at    TIMESTAMP WITH TIME ZONE
);
CREATE INDEX idx_purchase_restaurant ON purchase(restaurant_id, created_at);

CREATE TABLE purchase_line (
    id             UUID PRIMARY KEY,
    purchase_id    UUID NOT NULL REFERENCES purchase(id) ON DELETE CASCADE,
    stock_item_id  UUID NOT NULL REFERENCES stock_item(id),
    qty            NUMERIC(12,3) NOT NULL,
    unit_cost      NUMERIC(10,2),
    received_qty   NUMERIC(12,3)
);
CREATE INDEX idx_purchase_line_purchase ON purchase_line(purchase_id);

-- Every change to a stock level, for history. qty_change is signed.
CREATE TABLE stock_move (
    id             UUID PRIMARY KEY,
    restaurant_id  UUID NOT NULL REFERENCES restaurant(id) ON DELETE CASCADE,
    stock_item_id  UUID NOT NULL REFERENCES stock_item(id) ON DELETE CASCADE,
    move_type      VARCHAR(12)   NOT NULL,
    qty_change     NUMERIC(12,3) NOT NULL,
    qty_after      NUMERIC(12,3) NOT NULL,
    unit_cost      NUMERIC(10,2),
    note           VARCHAR(200)  NOT NULL DEFAULT '',
    purchase_id    UUID,
    created_by     UUID,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_stock_move_item ON stock_move(stock_item_id, created_at);

-- Day book: money in and out other than sales (supplier payments, gas, salary, cash added).
CREATE TABLE cash_entry (
    id             UUID PRIMARY KEY,
    restaurant_id  UUID NOT NULL REFERENCES restaurant(id) ON DELETE CASCADE,
    business_day   DATE          NOT NULL,
    direction      VARCHAR(4)    NOT NULL,
    method         VARCHAR(12)   NOT NULL,
    category       VARCHAR(40)   NOT NULL,
    amount         NUMERIC(12,2) NOT NULL,
    note           VARCHAR(200)  NOT NULL DEFAULT '',
    purchase_id    UUID,
    created_by     UUID,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_cash_entry_day ON cash_entry(restaurant_id, business_day);

-- Opening cash and the cash counted at closing, per day.
CREATE TABLE day_close (
    restaurant_id  UUID NOT NULL REFERENCES restaurant(id) ON DELETE CASCADE,
    business_day   DATE NOT NULL,
    opening_cash   NUMERIC(12,2),
    counted_cash   NUMERIC(12,2),
    closed_by      UUID,
    closed_at      TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (restaurant_id, business_day)
);
CREATE INDEX idx_order_restaurant_day ON customer_order(restaurant_id, business_day);
