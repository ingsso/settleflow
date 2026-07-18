-- =========================================
-- V1__init_schema.sql
-- =========================================

CREATE TABLE seller (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name       VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE product (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    seller_id  BIGINT NOT NULL REFERENCES seller(id),
    name       VARCHAR(255) NOT NULL,
    price      BIGINT NOT NULL,
    stock      INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT chk_product_price
        CHECK (price > 0),
    CONSTRAINT chk_product_stock
        CHECK (stock >= 0)
);

CREATE INDEX idx_product_seller_id ON product (seller_id);

CREATE TABLE orders (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    status       VARCHAR(20) NOT NULL,
    total_amount BIGINT NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT chk_orders_status
        CHECK (status IN ('CREATED', 'PAID', 'CANCELED')),
    CONSTRAINT chk_orders_total_amount
        CHECK (total_amount > 0)
);

CREATE TABLE order_item (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id     BIGINT NOT NULL REFERENCES orders(id),
    product_id   BIGINT NOT NULL REFERENCES product(id),
    product_name VARCHAR(255) NOT NULL,
    unit_price   BIGINT NOT NULL,
    quantity     INT NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT chk_order_item_quantity
        CHECK (quantity > 0),
    CONSTRAINT chk_order_item_unit_price
        CHECK (unit_price > 0)
);

CREATE INDEX idx_order_item_order_id ON order_item (order_id);

CREATE TABLE payment (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id            BIGINT NOT NULL REFERENCES orders(id),
    merchant_payment_id UUID NOT NULL UNIQUE,
    pg_transaction_id   VARCHAR(255) UNIQUE,
    status              VARCHAR(20) NOT NULL,
    amount              BIGINT NOT NULL,
    refunded_amount     BIGINT NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT chk_payment_status
        CHECK (status IN ('PENDING', 'PAID', 'FAILED', 'UNKNOWN')),
    CONSTRAINT chk_payment_amount
        CHECK (amount > 0),
    CONSTRAINT chk_payment_refunded_amount
        CHECK (refunded_amount >= 0 AND refunded_amount <= amount)
);

CREATE INDEX idx_payment_order_id ON payment (order_id);

-- 부분 유니크 인덱스: PAID 결제는 주문당 1건만
CREATE UNIQUE INDEX uq_payment_order_paid
    ON payment (order_id)
    WHERE status = 'PAID';

CREATE TABLE refund (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    payment_id BIGINT NOT NULL REFERENCES payment(id),
    amount     BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT chk_refund_amount
        CHECK (amount > 0)
);

CREATE INDEX idx_refund_payment_id ON refund (payment_id);