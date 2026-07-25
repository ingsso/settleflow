-- =========================================
-- V3__add_ledger.sql
-- 이중장부: journal(사건 헤더) + ledger_entry(분개 줄)
-- payment/refund 가 원인으로서 journal 을 가리킨다(C 방식).
-- append-only: journal/ledger_entry 에 updated_at 없음.
-- =========================================

CREATE TABLE journal (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    type       VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT chk_journal_type
        CHECK (type IN ('PAYMENT', 'REFUND'))
);

CREATE TABLE ledger_entry (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    journal_id BIGINT NOT NULL REFERENCES journal(id),
    account    VARCHAR(20) NOT NULL,
    dr_cr      VARCHAR(20) NOT NULL,
    amount     BIGINT NOT NULL,

    CONSTRAINT chk_ledger_account
        CHECK (account IN ('CASH', 'SELLER_PAYABLE', 'FEE_REVENUE')),
    CONSTRAINT chk_ledger_dr_cr
        CHECK (dr_cr IN ('DEBIT', 'CREDIT')),
    CONSTRAINT chk_ledger_amount
        CHECK (amount > 0)
);

CREATE INDEX idx_ledger_entry_journal_id ON ledger_entry (journal_id);

-- payment/refund → journal (원인이 journal 을 가리킴).
-- nullable: 결제는 PENDING 으로 먼저 태어나고 journal 은 PAID 확정 시 생김(§3).
-- UNIQUE: journal←payment/refund 최대 1(뒷면 잠금) → 진짜 1:1.
ALTER TABLE payment
    ADD COLUMN journal_id BIGINT REFERENCES journal(id);
ALTER TABLE payment
    ADD CONSTRAINT uq_payment_journal_id UNIQUE (journal_id);

ALTER TABLE refund
    ADD COLUMN journal_id BIGINT REFERENCES journal(id);
ALTER TABLE refund
    ADD CONSTRAINT uq_refund_journal_id UNIQUE (journal_id);
