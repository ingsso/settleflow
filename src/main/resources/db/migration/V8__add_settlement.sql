-- =========================================
-- V8__add_settlement.sql
-- 야간 정산 배치(W3D3): (셀러, 날짜) 하나당 한 행.
--
-- 집계 원천은 장부다 — payment/refund 가 아니라 ledger_entry 의 SELLER_PAYABLE 을
-- CREDIT − DEBIT 로 합친다. 환불 역분개가 DEBIT 이라 "어느 날에 귀속시킬지"를
-- 손으로 정할 필요가 없다(역분개 journal 이 찍힌 날에 자동으로 빠진다).
--
-- 이 테이블은 파생 캐시가 아니다. W1D3(재고 Redis)·W2D4(환불 상태)에서 저장을 두 번
-- 기각한 이유는 "원본과 항상 같아야 하는 값의 복제"였기 때문이고, 여기 저장하는 건
-- 복제가 아니라 "그날 그렇게 판단했다"는 별개의 사실이다. 장부와 어긋날 수 있고,
-- 어긋남 자체가 정보다(W3D4 대사의 내부 축).
-- =========================================

CREATE TABLE settlement (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    seller_id       BIGINT NOT NULL REFERENCES seller(id),

    -- DATE: 정산 단위는 시각이 아니라 날짜 그 자체다. TIMESTAMPTZ 로 두면 "26일"이
    -- 시각을 갖게 된다. 대가는 이 값 안에 타임존이 없다는 것 — "KST 기준 26일"이라는
    -- 뜻은 경계를 계산하는 코드(ZoneId 상수) 한 곳에만 존재한다. 그게 두 군데로
    -- 흩어지는 순간 26일이 두 개가 된다.
    settlement_date DATE NOT NULL,

    -- net = 셀러에게 줄 금액(수수료 뗀 뒤). gross(=SELLER_PAYABLE + FEE_REVENUE)는
    -- 저장하지 않는다 — gross 는 언제 다시 SUM 해도 같은 값이라 파생해도 안전하고,
    -- net 은 확정해야 하는 약속이라 스냅샷이 필요하다.
    --
    -- ★ CHECK (net_amount > 0) 을 걸지 않는다. 판 것 없이 환불만 있는 날은 음수가
    --   되고, 그게 정상이다. 이 컬럼은 "지급액"이 아니라 "정산액(음수면 회수 대상)"이다.
    --   옆칸(ledger_entry.chk_ledger_amount, payment.chk_payment_amount)을 복사하면
    --   바로 걸리는 자리라 의도적으로 비워둔다.
    net_amount      BIGINT NOT NULL,

    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- 이 테이블은 변한다(journal/ledger_entry 와 반대). 재실행 시 덮어쓰기가 정상 경로라
    -- updated_at 은 "이 행은 변한다"는 선언으로서 필요하다(W1D3).
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- 중복 방지 장치가 아니라 덮어쓰기의 전제다. ON CONFLICT 는 충돌을 감지할 제약이
    -- 있어야 동작하므로, 이 UNIQUE 가 없으면 "덮어쓰기" 선택 자체가 성립하지 않는다.
    --
    -- 컬럼 순서 (settlement_date, seller_id): 배치의 ON CONFLICT 는 키 전체를 주므로
    -- 순서와 무관하고, 판단 기준은 읽는 쪽이다. W3D4 대사가 "특정 날짜의 전 셀러"를
    -- 훑으므로 날짜를 선두에 둬야 leftmost prefix 로 커버된다.
    CONSTRAINT uq_settlement_date_seller
        UNIQUE (settlement_date, seller_id)
);

-- 위 UNIQUE 가 날짜 선두라 셀러 단독 조회(한 셀러의 정산 이력)를 못 탄다.
-- FK 마다 인덱스를 두는 프로젝트 관례(idx_product_seller_id 등)와도 일치.
CREATE INDEX idx_settlement_seller_id ON settlement (seller_id);
