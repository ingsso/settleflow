#!/usr/bin/env bash
# 시나리오 ③ 판정 (k6/dunning-race.md §4·§6).
#
# 사용: bash k6/verify-dunning.sh [state|verdict]
#   state    지금 상태를 본다 — 라운드가 끝났는지(미결 0) 보려고 반복해서 부른다
#   verdict  ★ 판정. 중복 청구 위반 · 계기 둘 · 항등식
#
# 중복 청구의 정의는 "payment 행 수"가 아니다(W5D3 이 정정한 자리) —
# 한 billing_cycle 이 받은 서로 다른 merchant_payment_id 가 2개 이상인 것.

set -euo pipefail
PSQL="docker exec settleflow-postgres-1 psql -U myuser -d mydatabase -tAc"
MODE="${1:-verdict}"

q() { $PSQL "$1"; }

case "$MODE" in
  state)
    echo "구독=$(q "SELECT count(*) FROM subscription")" \
         "주기=$(q "SELECT count(*) FROM billing_cycle")" \
         "시도=$(q "SELECT count(*) FROM payment WHERE billing_cycle_id IS NOT NULL")" \
         "미결=$(q "SELECT count(*) FROM payment WHERE billing_cycle_id IS NOT NULL AND status IN ('PENDING','UNKNOWN')")"
    echo "주기 상태: $(q "SELECT string_agg(status || '=' || n, ' ') FROM (SELECT status, count(*) n FROM billing_cycle GROUP BY status ORDER BY status) t")"
    ;;

  verdict)
    echo "── 계기 ────────────────────────────────────────────"
    echo "구독              : $(q "SELECT count(*) FROM subscription")"
    echo "주기              : $(q "SELECT count(*) FROM billing_cycle")"
    echo "시도(payment 행)  : $(q "SELECT count(*) FROM payment WHERE billing_cycle_id IS NOT NULL")"
    echo "★ 서로 다른 키    : $(q "SELECT count(DISTINCT merchant_payment_id) FROM payment WHERE billing_cycle_id IS NOT NULL")"
    echo ""
    echo "── ★ 중복 청구 (한 주기가 서로 다른 키를 2개 이상 받은 것) ──"
    VIOL=$(q "SELECT count(*) FROM (SELECT billing_cycle_id FROM payment WHERE billing_cycle_id IS NOT NULL GROUP BY billing_cycle_id HAVING count(DISTINCT merchant_payment_id) > 1) t")
    echo "위반 주기 수      : $VIOL"
    if [ "$VIOL" != "0" ]; then
      echo "위반 상세(상위 10):"
      $PSQL "SELECT billing_cycle_id || ' → 키 ' || count(DISTINCT merchant_payment_id) FROM payment WHERE billing_cycle_id IS NOT NULL GROUP BY billing_cycle_id HAVING count(DISTINCT merchant_payment_id) > 1 ORDER BY 1 LIMIT 10"
    fi
    echo ""
    echo "── 주기 상태 ───────────────────────────────────────"
    $PSQL "SELECT '  ' || status || ' = ' || count(*) FROM billing_cycle GROUP BY status ORDER BY status"
    echo ""
    echo "── 구독 시계가 한 번만 밀렸나 (P1 CAS) ─────────────"
    echo "구독당 주기 2개 이상: $(q "SELECT count(*) FROM (SELECT subscription_id FROM billing_cycle GROUP BY subscription_id HAVING count(*) > 1) t")"
    echo "cycle_no 최대       : $(q "SELECT coalesce(max(cycle_no),0) FROM billing_cycle")"
    echo ""
    echo "── 장부 ────────────────────────────────────────────"
    echo "journal           : $(q "SELECT count(*) FROM journal")"
    echo "구독수익 합        : $(q "SELECT coalesce(sum(amount),0) FROM ledger_entry WHERE account='SUBSCRIPTION_REVENUE'")"
    ;;

  *)
    echo "모드는 state 또는 verdict"; exit 1;;
esac
