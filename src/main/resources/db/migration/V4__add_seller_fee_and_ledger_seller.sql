-- =========================================
-- V4__add_seller_fee_and_ledger_seller.sql
-- 셀러별 분개(W2D2): SELLER_PAYABLE·FEE_REVENUE 를 셀러 단위로 쪼갠다.
-- 한 주문에 셀러가 여럿일 수 있어(order_item → product → seller) 3줄 분개가 2N+1 줄이 된다.
-- 장부가 정산의 진실원본이 되려면 "누구에게 얼마"가 줄에 남아야 한다(Week 3 대사).
-- =========================================

-- 수수료율은 basis point 정수(500 = 5%). 돈 근처에 부동소수점을 두지 않는다.
-- DEFAULT 유지: 기존 행 백필 + 픽스처 편의. 계약율이 다른 셀러는 명시적으로 넣는다.
ALTER TABLE seller
    ADD COLUMN fee_rate INT NOT NULL DEFAULT 500;

ALTER TABLE seller
    ADD CONSTRAINT chk_seller_fee_rate
        CHECK (fee_rate BETWEEN 0 AND 10000);

-- nullable: CASH 줄에는 셀러가 없다.
-- seller_id 는 "거래상대방"이 아니라 분석 차원이다 — SELLER_PAYABLE 에선 갚을 상대,
-- FEE_REVENUE 에선 그 수익이 누구에게서 났나를 뜻한다.
ALTER TABLE ledger_entry
    ADD COLUMN seller_id BIGINT REFERENCES seller(id);

CREATE INDEX idx_ledger_entry_seller_id ON ledger_entry (seller_id);

-- 계정과 셀러의 관계를 잠근다. 판정에 자기 행의 두 컬럼만 필요해서 CHECK 로 표현된다
-- (Day 3 의 Σ차변==Σ대변 은 여러 행을 합쳐야 하므로 CHECK 로 안 되는 것과 반대편).
ALTER TABLE ledger_entry
    ADD CONSTRAINT chk_ledger_seller_presence
        CHECK (
            (account =  'CASH' AND seller_id IS     NULL)
         OR (account <> 'CASH' AND seller_id IS NOT NULL)
        );
