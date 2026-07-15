-- =========================================
-- V2__add_payment_version.sql
-- 낙관적 락(@Version)용 버전 컬럼.
--
-- 동기 승인(pay())과 웹훅 승인이 동시에 같은 PENDING 결제를 PAID 로 전이하려 하면
-- 둘 다 읽을 땐 PENDING 이라 인메모리 상태 가드가 둘 다 안 걸린다(W1D4 §backbone).
-- 버전 충돌로 지는 쪽 save 를 튕겨, 재조회 → 종착지(PAID) → onApproved()가 IGNORED 를
-- 돌려주게 만든다. 전이표의 진짜 backbone.
-- =========================================

ALTER TABLE payment
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
