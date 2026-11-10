-- Super Bistro schema. Every business table carries restaurant_id: one database, many restaurants.
-- Written in plain SQL that PostgreSQL (production) and H2 (tests) both accept.

CREATE TABLE restaurant (
    id          UUID PRIMARY KEY,
    name        VARCHAR(80)  NOT NULL,
    gst_pct     NUMERIC(5,2) NOT NULL DEFAULT 5,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE app_user (
    id             UUID PRIMARY KEY,
    restaurant_id  UUID NOT NULL REFERENCES restaurant(id) ON DELETE CASCADE,
    email          VARCHAR(160) NOT NULL,
    name           VARCHAR(80)  NOT NULL,
    password_hash  VARCHAR(100) NOT NULL,
    role           VARCHAR(20)  NOT NULL,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_app_user_email UNIQUE (email)
);
CREATE INDEX idx_app_user_restaurant ON app_user(restaurant_id);

CREATE TABLE menu_item (
    id             UUID PRIMARY KEY,
    restaurant_id  UUID NOT NULL REFERENCES restaurant(id) ON DELETE CASCADE,
    name           VARCHAR(80)   NOT NULL,
    price          NUMERIC(10,2) NOT NULL,
    category       VARCHAR(40)   NOT NULL,
    veg            BOOLEAN       NOT NULL DEFAULT TRUE,
    active         BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_menu_item_restaurant ON menu_item(restaurant_id);

CREATE TABLE customer_order (
    id             UUID PRIMARY KEY,
    restaurant_id  UUID NOT NULL REFERENCES restaurant(id) ON DELETE CASCADE,
    order_no       INTEGER      NOT NULL,
    business_day   DATE         NOT NULL,
    source         VARCHAR(16)  NOT NULL,
    order_type     VARCHAR(16)  NOT NULL,
    table_no       VARCHAR(20)  NOT NULL DEFAULT '',
    customer       VARCHAR(80)  NOT NULL DEFAULT '',
    phone          VARCHAR(20)  NOT NULL DEFAULT '',
    platform_id    VARCHAR(40)  NOT NULL DEFAULT '',
    note           VARCHAR(300) NOT NULL DEFAULT '',
    subtotal       NUMERIC(10,2) NOT NULL,
    discount       NUMERIC(10,2) NOT NULL DEFAULT 0,
    gst_pct        NUMERIC(5,2)  NOT NULL DEFAULT 0,
    gst_amt        NUMERIC(10,2) NOT NULL DEFAULT 0,
    total          NUMERIC(10,2) NOT NULL,
    payment        VARCHAR(16)  NOT NULL,
    status         VARCHAR(16)  NOT NULL,
    created_by     UUID,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_order_no_per_day UNIQUE (restaurant_id, business_day, order_no)
);
CREATE INDEX idx_order_restaurant_created ON customer_order(restaurant_id, created_at);

CREATE TABLE order_item (
    id            UUID PRIMARY KEY,
    order_id      UUID NOT NULL REFERENCES customer_order(id) ON DELETE CASCADE,
    line_no       INTEGER       NOT NULL,
    menu_item_id  UUID,
    name          VARCHAR(80)   NOT NULL,
    qty           INTEGER       NOT NULL,
    price         NUMERIC(10,2) NOT NULL
);
CREATE INDEX idx_order_item_order ON order_item(order_id);
