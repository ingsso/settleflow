-- =========================================
-- V9__rename_refunded_amount_to_reserved.sql
-- payment.refunded_amount → reserved_refund_amount (W3D4)
--
-- 순수 개명이다. 값도 로직도 한 줄 안 바뀐다 — 이름을 이미 참인 사실에 맞추는 것뿐이다.
--
-- W3D2 에서 환불이 Saga 로 갈라지면서 이 컬럼의 의미가 바뀌었다. 역분개 journal 은 PG 확인
-- 뒤로 미뤘지만 이 누적만은 요청 시점에 남겼기 때문이다(미루면 잔액을 판정할 근거가 없어
-- 동시 환불이 전부 통과한다 — W1D2 실측: 8,000 이어야 할 값이 11,000). 그래서 이 컬럼이
-- 담는 건 "환불된 금액"이 아니라 다음 셋의 합이다:
--
--     COMPLETED(환불됨) + REQUESTED(PG 확인 대기중) + UNKNOWN(무응답 보류중)
--
-- 즉 이건 환불의 기록이 아니라 <예약>이다(W1D3 재고 예약과 같은 모양 — 원자적으로 잡아두고
-- 거절되면 되돌린다). FAILED 만 releaseRefundedAmount 로 빠진다.
--
-- 이름이 거짓말인 대가는 이미 실현됐다: Payment.refundStatus() 가 이 값에서 라벨을 파생해
-- <진행중인 환불을 환불된 것으로> 셌다. 게다가 UNKNOWN 은 쓸이 배치가 없어(W3D2 이월) 영영
-- 안 풀리므로 그 오차는 일시적이지도 않다.
--
-- ★ 라벨의 원천은 이 컬럼을 떠난다(W3D4): 확정 환불액은
--   SUM(refund.amount WHERE pg_status = 'COMPLETED') 로 파생한다. 저장하지 않는다 —
--   진실은 여전히 하나(refund 행)다.
--
-- 가드는 그대로 이 컬럼을 쓴다. 예약을 빼면 W1D2 레이스가 부활하므로 반드시 포함해야 한다.
-- 한 컬럼이 <가드(예약 포함해야 정확)>와 <라벨(예약 빠져야 정확)>을 겸하고 있었고,
-- 그 둘은 같은 값을 원하지 않는다. 라벨만 떠나보내고 컬럼은 가드에게 남긴다.
--
-- V1 을 직접 못 고치는 이유: Flyway 체크섬. 이미 적용된 마이그레이션을 수정하면 검증에 걸린다.
-- =========================================

ALTER TABLE payment
    RENAME COLUMN refunded_amount TO reserved_refund_amount;

-- CHECK 식은 컬럼 개명 시 Postgres 가 자동으로 따라 고친다. 제약 <이름>만 남아 옛 사실을
-- 가리키므로 같이 바꾼다. 그리고 이름이 바뀌면서 이 제약이 실제로 지키는 게 드러난다 —
-- "환불액이 결제액을 넘지 않는다"가 아니라 <예약 총액이 결제액을 넘지 않는다>이다.
ALTER TABLE payment
    RENAME CONSTRAINT chk_payment_refunded_amount TO chk_payment_reserved_refund_amount;
