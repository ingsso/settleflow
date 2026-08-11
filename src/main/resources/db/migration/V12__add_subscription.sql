-- =========================================
-- V12__add_subscription.sql
-- 정기결제 구독(W5D1). 테이블 둘: 계약(subscription) + 주기(billing_cycle).
--
-- ── 왜 둘인가 ─────────────────────────────────────────────────────────────
-- 계획서는 구독 하나에 상태머신 하나(ACTIVE → PAST_DUE → CANCELED)를 요구했다. 폐기했다.
-- 그 전이표에는 화살표가 하나 빠져 있다 — 재시도가 성공하면 PAST_DUE → ACTIVE 로 돌아온다.
--
-- 되돌아오는 상태머신에서는 상태가 사건의 기억을 못 한다. payment 는 DAG 라 status='PAID'
-- 하나가 "승인 사건이 이미 반영됐다"를 뜻했고, 그래서 IGNORED 판정이 상태만 보고 가능했다
-- (W1D4 멱등 backbone). ACTIVE 는 "한 번도 청구 안 함"일 수도 "3주기 성공"일 수도 있다.
-- 같은 라벨, 다른 사실. 구체적으로 부서지는 자리:
--
--     3주기 성공 → ACTIVE / 4주기 실패 → PAST_DUE / 3주기의 지연된 성공 웹훅 도착
--     → 상태만 보면 PAST_DUE 는 종착지가 아니므로 TRANSITIONED → ACTIVE
--     → 실재하는 4주기 연체가 낡은 메시지에 지워진다 (ABA)
--
-- 더 나쁜 건 PaymentTransitionService.dispatch() 의 낙관적 락 루프다. "재조회하면 이미
-- 종착지라 IGNORED" 를 전제로 부수효과 1회를 보증하는데, 순환이 생기면 재조회 후 다시
-- TRANSITIONED 가 날 수 있고 그 부수효과는 돈을 긁는 일이다.
--
-- 그래서 주기를 행으로 내렸다. 사건은 subscription 이 아니라 자기 주기 행으로 가고,
-- 주기 행은 되돌아오지 않는다 — 각 행에 종착지가 있으므로 payment 의 판정 규칙이 그대로 산다.
--
-- ── 축을 가른 이유 ────────────────────────────────────────────────────────
-- 계획서 enum 셋은 축이 둘 섞여 있었다: CANCELED 는 계약 사실(사람이 해지했다)이고
-- PAST_DUE 는 청구 건강도(미해결 실패 주기가 있다)다. ACTIVE 한 칸이 "계약이 살아있다"와
-- "연체가 없다"를 겸한다. W3D4(refunded_amount) · W3D5(created_at) · W4D5(Sort 단언)에서
-- 세 번 갈라진 그 모양이라, 이번엔 갈라지기 전에 가른다. 사후 수리가 아닌 건 처음이다.
--
-- 선례는 payment 안에 있다: status(사실, 저장) 와 refundStatus()(라벨, 파생)가 다른 축이라고
-- Payment.java 에 명시돼 있고, 그 분리는 W3D4 에서 값을 치르고 얻은 것이다.
--   → subscription.status  = ACTIVE / CANCELED 만. 단방향 DAG 라 backbone 이 그대로 통한다.
--   → 연체 여부            = 컬럼 없음. billing_cycle 행들에서 파생한다.
--
-- ── 시도 층은 payment 행이다 (D3 를 D1 로 앞당김) ─────────────────────────
-- 한 주기 안의 재시도 3번도 각자 종착지를 가져야 한다 — 같은 ABA 가 주기 안쪽에서 재발하고,
-- BillingCycle.onPaymentFailed 는 상태만으로 "새 실패"와 "같은 실패의 재통보"를 못 가른다.
-- 그 걸러내기를 payment 행이 맡는다(종착지 전이표 · 낙관적 락 · 멱등 backbone 을 이미 가짐).
-- 새 테이블을 만들면 그걸 복제하게 된다.
--
-- ── 왜 order 를 안 만들고 order_id 를 푸는가 ──────────────────────────────
-- 진짜 질문은 "order_id 를 어떻게 하나"가 아니라 **구독료는 누구 돈인가**였다.
-- chk_ledger_seller_presence(V4) 가 CASH 아닌 모든 분개 줄에 seller_id 를 요구하므로,
-- 구독료도 반드시 셀러에 귀속된다. 두 해석이 갈린다:
--     A. 셀러의 정기배송 상품 (고객 → 셀러)  → order 필요, 매달 재고 차감
--     B. 셀러가 플랫폼에 내는 이용료 (셀러 → 플랫폼) → order 불필요
-- B 를 골랐다. 근거 둘 다 실측에서 나왔다:
--     ① OrderService.place() 가 주문+재고+결제를 한 흐름으로 묶는다("주문의 유일한 진입점").
--        A 면 재고만 따로 깎는 두 번째 경로가 필요하고, 그 순간 저 javadoc 이 거짓이 된다 —
--        W4 가 두 번 데인 "검증 없이 적힌 단정"을 우리 손으로 만드는 셈.
--     ② V8 정산은 SELLER_PAYABLE 만 합친다. B 면 구독료가 settlement.net_amount 에 안 섞이고,
--        3주치 정산·대사 실측 수치가 재해석 대상이 되지 않는다.
--
-- 대가는 payment.order_id NOT NULL(V1) 을 푸는 것이다. 그 전제에 기대는 자리를 세어봤다 —
-- 넷이고, 셋이 PaymentTransitionService.applyApproved 의 부수효과 블록 한 곳에 몰려 있다
-- (LedgerService.sharesOf / order.onPaymentConfirmed / PaymentCompletedEvent.from).
-- dispatch() 가 부수효과를 Consumer<Payment> 로 이미 파라미터화해 둬서, 분기문이 아니라
-- 형제 진입점(applySubscriptionApproved)으로 갈린다.
--
-- 넷째는 처음에 "환불 안분 경로라 안 닿는다"고 적었다가 **틀렸다**. ReconciliationService
-- :218 의 reconstructNetBySeller 는 findPaidInRange 로 그날 PAID 결제를 전부 훑고 sharesOf 를
-- 부른다 — 구독 청구가 PAID 가 되는 순간 NPE 이고 대사 배치가 그날 통째로 죽는다.
-- isSubscriptionCharge() 로 건너뛰게 고쳤다. 회피가 아니라 정의상 0 이다(구독 분개엔
-- SELLER_PAYABLE 줄이 없고 V8 집계도 SELLER_PAYABLE 만 합치므로 양쪽이 같이 0 을 낸다).
-- =========================================


-- 계약. 되돌아오지 않는다: ACTIVE → CANCELED 하나뿐이다.
CREATE TABLE subscription (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    -- 구독료를 **내는** 셀러다. 판매 분개에서 seller_id 가 "돈을 받을 상대"였던 것과 방향이
    -- 반대인데, V4 가 seller_id 를 "거래상대방이 아니라 분석 차원"이라고 정의해둔 덕에
    -- 스키마를 안 건드리고 그대로 쓴다 — SUBSCRIPTION_REVENUE 줄에서는 "그 수익이 누구에게서
    -- 났나"를 뜻한다(FEE_REVENUE 의 정의와 같은 문장이다).
    seller_id         BIGINT NOT NULL REFERENCES seller(id),

    status            VARCHAR(20) NOT NULL,

    -- 주기당 청구액. billing_cycle.amount 와 겹쳐 보이지만 복제가 아니다 —
    -- 이건 "앞으로 얼마를 청구할 것인가"(정책, 변할 수 있다), 저건 "그때 얼마를
    -- 청구했나"(스냅샷, 안 변한다). 요금제를 바꿔도 지난 주기 금액은 그대로여야 한다.
    amount            BIGINT NOT NULL,

    -- ★ 미결: 일 단위로 뒀다. 실제 구독은 월 단위("매월 1일")가 자연스럽지만, 월 주기는
    --   월말 경계 문제(1/31 의 다음은 2/31?)를 끌고 오고 그건 dunning 과 무관한 별개 주제다.
    --   D5 의 k6 재시도 레이스 시나리오에서 주기를 짧게 잡아야 하는 사정도 일 단위 쪽이다.
    --   월 단위가 필요해지면 그때 컬럼을 바꾸는 게 아니라 "주기 규칙"을 따로 모델링해야 한다.
    interval_days     INT NOT NULL,

    -- 다음 정기 청구일. 시계 둘 중 하나다(다른 하나는 billing_cycle.next_retry_at).
    --
    -- 파생 가능해 보인다(마지막 주기 + interval). 저장하는 근거는 V8 의 기준 그대로다 —
    -- 복제냐 별개의 사실이냐. W5D4 가 "PG 장애(OPEN) 시 재시도 스킵, 다음 주기로 연기"를
    -- 요구하므로 이 값은 조정될 수 있고, 조정되는 순간 계산식으로는 못 돌아간다.
    --
    -- DATE 인 이유는 V8 settlement_date 와 같다: 청구 단위는 시각이 아니라 날짜다.
    -- 대가도 같다 — 이 값 안에 타임존이 없고, "KST 기준 그날"이라는 뜻은 경계를 계산하는
    -- 코드 한 곳에만 존재한다.
    --
    -- CANCELED 면 NULL. 해지된 구독에 "다음 청구일"은 뜻이 없다.
    next_billing_date DATE,

    canceled_at       TIMESTAMPTZ,

    -- 낙관적 락. 전이(ACTIVE→CANCELED)는 단방향이라 여기서만 보면 과하지만,
    -- next_billing_date 갱신은 정기 청구 스케줄러와 재시도 경로가 함께 노린다.
    version           BIGINT NOT NULL DEFAULT 0,

    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- PAST_DUE 가 없는 게 이 CHECK 의 요점이다. 나중에 누가 "연체 상태도 여기 두자"고
    -- 할 때 스키마가 먼저 거절한다.
    CONSTRAINT chk_subscription_status
        CHECK (status IN ('ACTIVE', 'CANCELED')),

    CONSTRAINT chk_subscription_amount
        CHECK (amount > 0),

    CONSTRAINT chk_subscription_interval_days
        CHECK (interval_days > 0),

    -- 상태와 두 시각 컬럼이 어긋나지 못하게 묶는다. 파생 라벨을 안 두기로 한 대신,
    -- 상태가 실제로 뜻하는 바를 스키마가 붙잡는다.
    CONSTRAINT chk_subscription_canceled_shape
        CHECK ((status = 'CANCELED') = (canceled_at IS NOT NULL)
               AND (status = 'CANCELED') = (next_billing_date IS NULL))
);

-- 정기 청구 스케줄러의 조회: "오늘 청구할 살아있는 구독". 해지된 행은 안 본다.
CREATE INDEX idx_subscription_due
    ON subscription (next_billing_date)
    WHERE status = 'ACTIVE';

-- FK 마다 인덱스를 두는 프로젝트 관례(idx_product_seller_id, idx_settlement_seller_id).
CREATE INDEX idx_subscription_seller_id ON subscription (seller_id);


-- 청구 주기 1회 = 1행. 여기가 상태머신의 실체다.
CREATE TABLE billing_cycle (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    subscription_id BIGINT NOT NULL REFERENCES subscription(id),

    -- 1 부터 단조증가. 되돌아오지 않는 축이라, (status, cycle_no) 쌍 위에서 보면
    -- 전이표가 다시 DAG 가 된다. 낡은 사건이 어느 주기의 것인지 이 값으로 갈린다.
    cycle_no        INT NOT NULL,

    -- PENDING  청구를 걸었고 아직 결과가 없다
    -- PAID     받았다 (종착지)
    -- FAILED   실패했고 재시도가 남았다
    -- ABANDONED 재시도 상한을 넘겨 포기했다 (종착지)
    --
    -- ★ FAILED 와 ABANDONED 를 가른 이유: 한 칸으로 두면 "재시도 대기중"과 "포기함"을
    --   next_retry_at IS NULL 로 갈라야 하고, 그건 오늘 폐기한 것과 같은 모양이다
    --   (칸 하나가 둘을 겸함). W4 회고가 W5 진입 전에 예고한 질문 — "포기를 무엇으로
    --   표현할 것인가" — 의 답이 이 값이다.
    --
    -- FAILED → PAID 는 순환이 아니다(A→B→C). PAID·ABANDONED 만 종착지다.
    status          VARCHAR(20) NOT NULL,

    -- 청구 시점 스냅샷. subscription.amount 를 참조하지 않는다(위 주석 참고).
    amount          BIGINT NOT NULL,

    period_start    DATE NOT NULL,
    period_end      DATE NOT NULL,

    -- 다음 재시도 시각. 시계 둘 중 나머지 하나다.
    --
    -- ★ attempt_count 를 두지 않았다. 시도 층은 payment 행이므로(위 미결 참고) 횟수는
    --   그 행들을 세면 나온다. 컬럼으로 두면 정확히 "원본과 항상 같아야 하는 값의 복제"가
    --   되고, W1D3·W2D4 에서 두 번 기각한 그 모양이다. D3 에서 payment 가 붙기 전까지
    --   "몇 번째 시도인가"는 이 스키마가 답하지 않는다 — 그게 미결의 정직한 표현이다.
    --
    --   엔티티 쪽 대응: 상한 판정을 엔티티가 스스로 하지 못하므로 인자로 받는다.
    --   Payment.refundStatus(long completedAmount) 가 이미 같은 모양이다(W3D4) —
    --   원천은 밖으로, 규칙은 엔티티에.
    --
    -- 시각이지 날짜가 아니다. 청구일은 날짜 단위지만 재시도는 "1일 뒤 이 시각"이라
    -- 하루 안에서 순서가 의미를 갖는다. next_billing_date 와 타입이 다른 게 실수가 아니다.
    next_retry_at   TIMESTAMPTZ,

    -- 낙관적 락. 같은 주기에 두 사건(동기 결과 · 웹훅, 또는 재시도 둘)이 동시에
    -- 도착할 때 부수효과 1회를 보증한다 — payment.version 과 같은 역할(W1D4).
    version         BIGINT NOT NULL DEFAULT 0,

    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- ★ 중복 청구를 막는 마지막 보루다. 멱등키(Redis SETNX)도 애플리케이션 판정도
    --   빠져나갈 수 있지만 이건 못 빠져나간다 — W1D2 가 "정합성은 언제나 그 한 문장이
    --   지킨다"로 정리한 자리와 같은 성격. D5 의 동시 재시도 레이스 시나리오가
    --   실제로 겨누는 제약이 이것이다.
    CONSTRAINT uq_billing_cycle_subscription_no
        UNIQUE (subscription_id, cycle_no),

    CONSTRAINT chk_billing_cycle_status
        CHECK (status IN ('PENDING', 'PAID', 'FAILED', 'ABANDONED')),

    CONSTRAINT chk_billing_cycle_amount
        CHECK (amount > 0),

    CONSTRAINT chk_billing_cycle_no
        CHECK (cycle_no > 0),

    CONSTRAINT chk_billing_cycle_period
        CHECK (period_end > period_start),

    -- 재시도 예약은 FAILED 인 동안에만 뜻이 있다. 종착지로 가면서 비우는 걸 잊으면
    -- 스케줄러가 이미 끝난 주기를 계속 집는다 — 스키마가 먼저 거절한다.
    CONSTRAINT chk_billing_cycle_retry_shape
        CHECK ((next_retry_at IS NOT NULL) = (status = 'FAILED'))
);

-- 재시도 스케줄러의 조회: "지금 다시 걸 실패 주기". 정기 청구 조회(idx_subscription_due)와
-- 다른 테이블·다른 컬럼·다른 타입이다 — 시계가 둘이라는 사실이 인덱스 둘로 드러난다.
CREATE INDEX idx_billing_cycle_retry_due
    ON billing_cycle (next_retry_at)
    WHERE status = 'FAILED';

-- 한 구독의 주기 이력을 최신순으로 훑는다(연체 여부 파생 · 마지막 주기 찾기).
-- subscription.status 에서 PAST_DUE 를 뺀 대가를 이 인덱스가 치른다.
CREATE INDEX idx_billing_cycle_subscription
    ON billing_cycle (subscription_id, cycle_no DESC);


-- =========================================
-- payment 를 시도 층으로 연결한다
-- =========================================

-- 주문 결제는 여전히 주문을 갖는다. NULL 이 허용되는 건 구독 청구뿐이고,
-- 그 배타성은 아래 CHECK 가 지킨다 — "풀었다"가 아니라 "조건을 옮겼다"에 가깝다.
ALTER TABLE payment
    ALTER COLUMN order_id DROP NOT NULL;

-- 시도 N 개가 한 주기를 가리키므로 UNIQUE 가 아니다. 그게 이 설계의 요점이다 —
-- 주기 하나에 payment 행이 여럿이고, 각 행이 자기 종착지를 갖는다.
ALTER TABLE payment
    ADD COLUMN billing_cycle_id BIGINT REFERENCES billing_cycle(id);

-- 배타적 아크. 결제는 주문의 것이거나 구독 주기의 것이지, 둘 다이거나 둘 다 아닐 수 없다.
-- <> 를 boolean 두 개에 쓰면 XOR 이다.
--
-- ★ 이 제약이 판별자를 겸한다. "구독 청구인가"를 payment_kind 같은 컬럼으로 따로 두면
--   그 컬럼과 FK 가 어긋날 수 있고(한 칸이 둘을 겸함), 여기서는 FK 자체가 답이다.
ALTER TABLE payment
    ADD CONSTRAINT chk_payment_parent_arc
        CHECK ((order_id IS NULL) <> (billing_cycle_id IS NULL));

CREATE INDEX idx_payment_billing_cycle_id ON payment (billing_cycle_id);


-- =========================================
-- 장부: 구독료 계정
-- =========================================
--
-- ── journal.type 은 안 늘린다 ─────────────────────────────────────────────
-- PAYMENT/REFUND 는 "돈이 들어오는 사건이냐 나가는 사건이냐"의 축이다. 구독 청구는 돈이
-- 들어오는 사건이므로 PAYMENT 가 맞다. SUBSCRIPTION_CHARGE 를 만들면 그 축에 다른 축
-- (무슨 돈이냐)을 섞게 되고, 그건 오늘 하루 걷어낸 모양이다.
--
-- ── 계정은 늘린다 ─────────────────────────────────────────────────────────
-- 구독료를 FEE_REVENUE 에 넣으면 "판매에서 뗀 수수료"와 "셀러가 낸 이용료"가 한 계정에서
-- 섞이고, 둘을 가를 방법이 사라진다. 정산액(SELLER_PAYABLE)은 안 더러워지지만 수익 계정은
-- 더러워지는 셈이라, B 를 고른 근거가 절반만 성립하게 된다.
--
-- chk_ledger_seller_presence(V4)는 그대로 만족한다 — CASH 가 아니고 seller_id 가 있다.
-- (chk_journal_type 은 손대지 않는다. 위 판단의 귀결이다.)
ALTER TABLE ledger_entry
    DROP CONSTRAINT chk_ledger_account;
ALTER TABLE ledger_entry
    ADD CONSTRAINT chk_ledger_account
        CHECK (account IN ('CASH', 'SELLER_PAYABLE', 'FEE_REVENUE', 'SUBSCRIPTION_REVENUE'));
