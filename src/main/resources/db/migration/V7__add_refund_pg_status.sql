-- =========================================
-- V7__add_refund_pg_status.sql
-- 환불 Saga(W3D2). 환불이 "요청"과 "PG 확인" 둘로 갈라진다.
--
-- W2D5 까지 환불은 동기였다 — 요청 = 장부 반영이었다. W3D2 부터 consumer 가 refund.requested 를
-- 받아 실제 PG 환불을 부르므로, 요청 시점엔 아직 아무 일도 확정되지 않았다.
--
-- 그래서 역분개 journal 을 PG 확인 뒤로 미룬다: 장부에 "일어나지 않은 일"이 적힐 수 없게 한다
-- (W1D1 §5 append-only — 정정은 예외적 도구지 평시 동작이 아니다). 대신 선반영했다면 필요했을
-- "보상 분개"가 통째로 사라진다.
--
-- 다만 payment.refunded_amount 누적은 요청 시점에 그대로 둔다. 그 조건부 UPDATE 가 과다환불을
-- 막는 유일한 판정이고(W1D2 RefundRaceTest: 조건을 빼면 8,000 이 11,000), 미루면 판정할 근거가
-- 아직 존재하지 않는 상태가 되어 동시 환불이 전부 통과한다. 즉 이 컬럼은 "예약"이다 —
-- W1D3 의 재고 예약(주문 시 원자적 차감 → 결제 실패 시 배치가 복원)과 같은 모양.
-- =========================================

-- pg_status: 이 환불 시도가 어디까지 갔나. 재무 진실이 아니라 진행 상태다.
--
-- refund 행은 append-only 인데(W1D1 §5) 이 컬럼만 mutable 인 이유는 outbox.published_at 과 같다
-- (V6 주석): 재무 진실은 journal 이 갖고, 여긴 그 진실이 언제 확정됐는지를 나르는 메커니즘이다.
--
-- 4 상태인 이유는 payment.status 와 같다(W1D1 §3). 타임아웃은 실패가 아니라 "정보의 부재"라서
-- FAILED 와 같은 칸에 두면 안 된다 — UNKNOWN 을 FAILED 로 취급해 예약을 풀면, PG 는 실제로
-- 환불했는데 잔액만 되살아나 이중환불이 열린다(W1D5 의 성급한 재고 복원 = 이중판매와 같은 구멍).
ALTER TABLE refund
    ADD COLUMN pg_status VARCHAR(16) NOT NULL DEFAULT 'REQUESTED';

-- PG 가 환불 확정 시 발급하는 번호. 대사(W3D4)가 외부 리포트와 맞출 열쇠.
ALTER TABLE refund
    ADD COLUMN pg_refund_id VARCHAR(64);

-- 확정 시각(성공·거절·무응답 모두). created_at(요청) 과의 간격이 Saga 지연이다.
ALTER TABLE refund
    ADD COLUMN confirmed_at TIMESTAMPTZ;

ALTER TABLE refund
    ADD CONSTRAINT chk_refund_pg_status
        CHECK (pg_status IN ('REQUESTED', 'COMPLETED', 'FAILED', 'UNKNOWN'));

-- 백필. V7 이전(W2D5)의 환불은 동기라 요청 = 확정이었고, journal 이 붙어 있다는 것 자체가
-- "이미 확정됨"의 증거다. 그 행들을 REQUESTED 로 두면 아래 CHECK 이 이력을 거짓으로 만든다.
-- pg_refund_id 는 채우지 않는다 — 그때 PG 환불을 부른 적이 없어 존재한 적 없는 값이다.
-- 지어내는 대신 NULL 로 두고, 아래 CHECK 을 그에 맞춰 단방향으로 건다.
UPDATE refund
   SET pg_status    = 'COMPLETED',
       confirmed_at = created_at
 WHERE journal_id IS NOT NULL;

-- 장부는 확정된 환불에만 있다 — 양방향으로 못박는다.
--   COMPLETED 인데 journal 없음  → 확정했는데 장부에 안 적음(진실 누락)
--   COMPLETED 아닌데 journal 있음 → 안 일어난 일이 장부에 적힘(W3D2 가 없애려던 바로 그것)
-- 앱이 순서를 지키는 것과 별개로 스키마가 백스톱을 선다(W2D3 의 앱 assertBalanced + DB 트리거와 같은 결).
ALTER TABLE refund
    ADD CONSTRAINT chk_refund_journal_matches_status
        CHECK ((pg_status = 'COMPLETED') = (journal_id IS NOT NULL));

-- pg_refund_id 는 단방향이다 — "번호가 있으면 반드시 COMPLETED" 만 못박고, 역은 걸지 않는다.
-- 위 백필 대상(W2D5 동기 환불)이 COMPLETED 인데 번호가 없기 때문이다. 양방향으로 걸려면 없는
-- 번호를 지어내야 하고, 그러면 대사(W3D4)가 외부 리포트와 맞출 때 가짜 열쇠를 만나게 된다.
ALTER TABLE refund
    ADD CONSTRAINT chk_refund_pg_refund_id
        CHECK (pg_refund_id IS NULL OR pg_status = 'COMPLETED');
