#!/usr/bin/env bash
# k6 실행 결과 검증(W4D3). k6 는 HTTP 만 보고, 우리 명제는 DB·Redis 에 있다.
#
# 시나리오 ②는 <b>검증 시점에 앱이 죽어 있다</b>. 그래서 앱을 통한 검증(액추에이터·전용
# 엔드포인트)은 애초에 못 쓴다 — Postgres·Redis 를 직접 본다.
#
# 사용:
#   bash k6/verify.sh baseline              # ① 실행 전 기준선
#   bash k6/verify.sh duplicate <mpid>      # ① 실행 후 대조
#   bash k6/verify.sh reset-orphan          # ② 이전 회차 키 정리
#   bash k6/verify.sh orphan                # ② 실행 후 고아 키 계수
set -euo pipefail

PG=settleflow-postgres-1
REDIS=settleflow-redis-1
DB=(-U myuser -d mydatabase)
BASELINE="$(dirname "$0")/.baseline"

psql_q() { docker exec -i "$PG" psql "${DB[@]}" -tA -c "$1"; }

case "${1:-}" in

  # ── ① 기준선 ────────────────────────────────────────────────────────────────
  # journal 에는 payment 로 돌아오는 참조가 없다(FK 방향이 payment.journal_id → journal.id).
  # 그래서 "장부 1줄"은 소유 관계로 못 세고 <b>증가분</b>으로 잰다.
  baseline)
    j=$(psql_q "SELECT count(*) FROM journal WHERE type='PAYMENT'")
    o=$(psql_q "SELECT count(*) FROM outbox")
    l=$(psql_q "SELECT count(*) FROM ledger_entry")
    printf 'journal_payment=%s\noutbox=%s\nledger_entry=%s\n' "$j" "$o" "$l" > "$BASELINE"
    echo "기준선 기록: $BASELINE"
    cat "$BASELINE"
    ;;

  # ── ① 중복 폭주 대조 ────────────────────────────────────────────────────────
  duplicate)
    mpid="${2:?사용: verify.sh duplicate <merchantPaymentId>}"
    [ -f "$BASELINE" ] || { echo "기준선이 없다. 먼저 'verify.sh baseline' 을 돌릴 것"; exit 1; }
    # shellcheck disable=SC1090
    source "$BASELINE"

    j=$(psql_q "SELECT count(*) FROM journal WHERE type='PAYMENT'")
    o=$(psql_q "SELECT count(*) FROM outbox")
    l=$(psql_q "SELECT count(*) FROM ledger_entry")
    read -r status version <<<"$(psql_q "SELECT status, version FROM payment WHERE merchant_payment_id='$mpid'" | tr '|' ' ')"
    keys=$(docker exec "$REDIS" redis-cli --scan --pattern "webhook:dedup:k6-dup-$mpid" | wc -l | tr -d ' ')

    echo "=== 시나리오 ① 중복 폭주 — mpid=$mpid ==="
    printf '  payment.status        = %s\n' "$status"
    printf '  payment.version       = %s      (UNKNOWN→PAID 경로라 2 가 정상)\n' "$version"
    printf '  ★ journal(PAYMENT) 증가 = %s      ← 헤드라인. 1 이어야 한다\n' "$((j - journal_payment))"
    printf '  outbox 증가            = %s\n' "$((o - outbox))"
    printf '  ledger_entry 증가      = %s      (분개 1건당 3줄)\n' "$((l - ledger_entry))"
    printf '  Redis 멱등키           = %s      (같은 eventId 라 1개)\n' "$keys"
    ;;

  # ── ② 이전 회차 키 정리 ─────────────────────────────────────────────────────
  # 고아 계수는 누적이라, 안 지우면 지난 회차 고아가 이번 수치에 섞인다.
  reset-orphan)
    n=$(docker exec "$REDIS" redis-cli --scan --pattern 'webhook:dedup:k6-orphan-*' \
        | tee /tmp/k6_orphan_keys.txt | wc -l | tr -d ' ')
    if [ "$n" -gt 0 ]; then
      xargs -a /tmp/k6_orphan_keys.txt -r docker exec "$REDIS" redis-cli DEL >/dev/null
    fi
    echo "k6-orphan 키 $n 개 삭제"
    ;;

  # ── ② 고아 키 계수 ──────────────────────────────────────────────────────────
  # 고아 = 키는 있는데 그 결제가 <b>아직 UNKNOWN</b>. 성공한 웹훅의 키도 24시간
  # 남으므로(설계상 TTL) 키 개수만으로는 못 센다.
  #
  # ★ "PAID 가 아님"으로 세면 안 된다(W4D3 실측). FAILED 는 <b>정당한 종착지</b>다 —
  # 회로가 열려 PG 에 안 보낸 결제이거나 PG 가 거절한 결제이고, 거기에 웹훅이 와도
  # onApproved 가 CONFLICT 로 거부하는 게 설계대로다("종착지를 안 떠난다"). 유실이 아니다.
  # 첫 대조군에서 이 정의 때문에 정상 44건을 고아로 셌다.
  #
  # UNKNOWN 만이 "웹훅이 옮겨줬어야 하는데 안 옮겨진" 상태다.
  orphan)
    docker exec "$REDIS" redis-cli --scan --pattern 'webhook:dedup:k6-orphan-*' \
      | sed 's/^webhook:dedup:k6-orphan-//' | sort -u > /tmp/k6_mpids.txt
    total=$(wc -l < /tmp/k6_mpids.txt | tr -d ' ')

    if [ "$total" -eq 0 ]; then
      echo "k6-orphan 키가 없다. 시나리오 ②를 먼저 실행할 것"; exit 0
    fi

    docker exec -i "$PG" psql "${DB[@]}" -tA <<SQL > /tmp/k6_orphan_result.txt
CREATE TEMP TABLE k6_keys(mpid uuid);
COPY k6_keys FROM STDIN;
$(cat /tmp/k6_mpids.txt)
\.
SELECT 'total',  count(*) FROM k6_keys
UNION ALL SELECT 'paid',   count(*) FROM k6_keys k JOIN payment p ON p.merchant_payment_id=k.mpid WHERE p.status='PAID'
UNION ALL SELECT 'failed', count(*) FROM k6_keys k JOIN payment p ON p.merchant_payment_id=k.mpid WHERE p.status='FAILED'
UNION ALL SELECT 'orphan', count(*) FROM k6_keys k JOIN payment p ON p.merchant_payment_id=k.mpid WHERE p.status='UNKNOWN'
UNION ALL SELECT 'other',  count(*) FROM k6_keys k JOIN payment p ON p.merchant_payment_id=k.mpid WHERE p.status NOT IN ('PAID','FAILED','UNKNOWN')
UNION ALL SELECT 'nopay',  count(*) FROM k6_keys k LEFT JOIN payment p ON p.merchant_payment_id=k.mpid WHERE p.id IS NULL;
SQL

    echo "=== 시나리오 ② 결함 주입 — 고아 멱등키 ==="
    awk -F'|' '
      $1=="total"  {printf "  키 총계              = %s\n", $2}
      $1=="paid"   {printf "  정상 처리됨(PAID)    = %s\n", $2}
      $1=="failed" {printf "  FAILED               = %s   (회로 개방·PG 거절 — 정당한 종착지, 고아 아님)\n", $2}
      $1=="orphan" {printf "  ★ 고아(UNKNOWN)      = %s   ← 웹훅이 옮겼어야 하는데 안 옮겨짐\n", $2}
      $1=="other"  {printf "  기타 상태            = %s   (0 이 아니면 정의를 다시 볼 것)\n", $2}
      $1=="nopay"  {printf "  결제 행 없음         = %s   (0 이 아니면 주문 tx 도 못 커밋한 것)\n", $2}
    ' /tmp/k6_orphan_result.txt
    echo
    echo "  고아는 PG 가 재전송해도 ①(SETNX)에 막혀 200 만 받는다 — 조용한 유실이다."
    echo "  회수는 TTL(24h) 만료 + 미결제 쓸이 배치(W1D3)로 넘어간다."
    ;;

  *)
    echo "사용: bash k6/verify.sh {baseline|duplicate <mpid>|reset-orphan|orphan}" >&2
    exit 1
    ;;
esac
