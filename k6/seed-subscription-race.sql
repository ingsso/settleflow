-- 시나리오 ③(동시 재시도 레이스) 마스터 데이터 — 셀러 500명 (W5 마무리).
--
-- ★ 왜 셀러가 500명 필요한가: V13 이 (seller_id) WHERE status='ACTIVE' 로 셀러당 활성
--   구독을 하나로 묶었다. 구독 500개를 만들려면 셀러도 500명이어야 한다 —
--   중복 방어가 부하 설계의 제약이 된 자리다.
--
-- 구독 자체는 여기서 안 만든다. POST /subscriptions 로 만든다(k6/dunning-race.js) —
-- 부하가 프로덕션 경로를 타야 하고, 그게 이 시나리오에서 k6 가 맡은 역할이다.
--
-- 여러 번 돌려도 안전하다. 매 회차 앞에서 지난 회차를 지우므로 라운드 사이 리셋을 겸한다.

-- ── 1) 지난 회차 지우기 ────────────────────────────────────────────────
-- 자식부터. payment → billing_cycle → subscription 순(V12 배타적 아크).
-- 주문 결제(order_id 가 있는 행)는 건드리지 않는다 — 이 시나리오의 것만 지운다.
DELETE FROM ledger_entry
 WHERE journal_id IN (SELECT journal_id FROM payment
                       WHERE billing_cycle_id IS NOT NULL AND journal_id IS NOT NULL);

DELETE FROM settlement_pending
 WHERE journal_id IN (SELECT journal_id FROM payment
                       WHERE billing_cycle_id IS NOT NULL AND journal_id IS NOT NULL);

CREATE TEMP TABLE race_journals AS
SELECT journal_id FROM payment WHERE billing_cycle_id IS NOT NULL AND journal_id IS NOT NULL;

DELETE FROM payment WHERE billing_cycle_id IS NOT NULL;
DELETE FROM journal WHERE id IN (SELECT journal_id FROM race_journals);
DROP TABLE race_journals;

DELETE FROM billing_cycle;
DELETE FROM subscription;
DELETE FROM seller WHERE name LIKE 'k6 구독레이스 셀러 %';

-- ── 2) 셀러 500명 ─────────────────────────────────────────────────────
-- 이름에 번호를 박아 min(id)+n 으로 찾을 수 있게 한다. 한 문장으로 넣으므로 id 가 연속이다.
INSERT INTO seller (name, fee_rate)
SELECT 'k6 구독레이스 셀러 ' || lpad(n::text, 3, '0'), 500
  FROM generate_series(0, 499) AS n;

-- ── 3) k6 에 넘길 값 ──────────────────────────────────────────────────
-- SELLER_FROM 으로 넘긴다. 개수는 500 고정.
SELECT min(id) AS seller_from,
       max(id) AS seller_to,
       count(*) AS seller_count
  FROM seller
 WHERE name LIKE 'k6 구독레이스 셀러 %';
