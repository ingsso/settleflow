package com.settleflow.payment.repository;

import com.settleflow.payment.domain.Refund;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 환불 Saga 의 상태 전이(W3D2). 셋 다 <b>{@code WHERE ... AND pg_status = 'REQUESTED'}</b> 를 달고
 * 있고, 그 영향 행 수가 곧 <b>"내가 이 이벤트를 처음 처리하는가"</b>의 답이다.
 *
 * <h3>이게 소비자 멱등키다</h3>
 * 아웃박스 릴레이는 at-least-once 라 같은 {@code refund.requested} 가 두 번 배달될 수 있다
 * (W3D1 §7 — 배치 tx 롤백 시 중복 폭이 배치 크기까지). 그때 두 번째 처리는 여기서 0 행을 받고 멈춘다.
 *
 * <p><b>왜 {@code event_id} UNIQUE 가 아닌가.</b> UNIQUE 는 INSERT 충돌을 막는 장치인데, 소비자는
 * INSERT 할 게 없다 — {@code refund} 행은 요청 시점에 이미 만들어져 있고 소비자는 그걸 <i>갱신</i>할
 * 뿐이다. W1D4 의 SETNX 가 통했던 건 웹훅 핸들러가 키를 <b>직접 만들었기</b> 때문이고, 여기엔 그
 * 대칭이 없다. 그래서 멱등을 INSERT 충돌이 아니라 <b>조건부 UPDATE 의 영향 행 수</b>에 건다 —
 * {@code reserveRefund}·{@code decreaseStock} 이 쓴 것과 같은 패턴이다.
 *
 * <p>네이티브인 이유는 대상 컬럼들이 엔티티에서 {@code updatable = false} 이기 때문이다
 * ({@link Refund} 주석) — JPA 로는 못 쓰고 이 경로로만 쓴다. {@code reserveRefund} 와 같은 설계.
 */
public interface RefundRepository extends JpaRepository<Refund, Long> {

    /**
     * 이 결제의 <b>확정된</b> 환불액 합(W3D4). {@link com.settleflow.payment.domain.RefundStatus} 라벨의
     * 원천이다.
     *
     * <h3>왜 payment 컬럼이 아니라 여기서 세나</h3>
     * {@code payment.reserved_refund_amount}(구 {@code refunded_amount})는 <b>예약</b>이라
     * COMPLETED + REQUESTED + UNKNOWN 을 다 담는다(V9 주석). 그 값으로 라벨을 만들면 진행중인 환불이
     * {@code PARTIAL_REFUNDED} 로 보이고, {@code UNKNOWN} 은 쓸이 배치가 없어 영영 안 풀리므로
     * 그 오차가 <b>영구적</b>이다(W3D2 이월).
     *
     * <p>가드는 예약을 <b>포함해야</b> 정확하고(빼면 W1D2 레이스 부활 — 8,000 이 11,000) 라벨은
     * 예약이 <b>빠져야</b> 정확하다. 한 컬럼이 둘을 겸할 수 없어 <b>라벨이 원천을 옮겼다</b>.
     *
     * <h3>왜 저장하지 않나</h3>
     * {@code payment} 에 확정분 컬럼을 하나 더 두는 길도 있었다(W3D3 §1 의 <i>"복제가 아니라 별개의
     * 사실"</i> 논리로 정당화된다). 파생을 골랐다 — 이러면 진실은 여전히 하나({@code refund} 행)고,
     * 그 행은 이미 append-only + 조건부 UPDATE 로만 바뀌므로 드리프트할 경로가 없다.
     *
     * <p><b>장부에서 직접 세지 못하는 이유</b>: {@link com.settleflow.ledger.domain.Journal} 에
     * payment 참조가 없다(필드는 {@code type}/{@code createdAt}/{@code entries} 뿐). 결제↔장부 연결은
     * {@code payment.journal_id} 와 {@code refund.journal_id} 쪽이 들고 있어, 결국 {@code refund} 행을
     * 경유해야 한다. W3D3 정산이 "장부에서 바로" 집계한 것과 대비되는 자리다.
     *
     * <p>{@code COALESCE}: 환불 이력이 없으면 {@code SUM} 이 NULL 이다. 0 이 아니라 NULL 이라
     * 안 씌우면 라벨 계산이 터진다.
     *
     * <p><b>예약과의 차이가 곧 W3D4 대사의 한 축이다</b>:
     * {@code reserved_refund_amount − 이 합 = PG 확인 대기중 + UNKNOWN 보류중}. 이게 오래 0 이 아니면
     * UNKNOWN 에 물린 예약이고, 쓸이 배치가 없는 지금 그걸 드러내는 유일한 장치가 대사다.
     */
    @Query(value = """
            SELECT COALESCE(SUM(r.amount), 0)
              FROM refund r
             WHERE r.payment_id = :paymentId
               AND r.pg_status = 'COMPLETED'
            """, nativeQuery = true)
    long sumCompletedAmount(@Param("paymentId") Long paymentId);

    /**
     * PG 환불 성공 → COMPLETED. 역분개 journal·PG 번호·확정시각을 <b>한 문장에서 함께</b> 채운다.
     *
     * <p>넷을 쪼개 쓰면 그 사이에 V7 의 {@code chk_refund_journal_matches_status}(COMPLETED ⇔
     * journal 있음)를 위반하는 중간 상태가 생긴다. 한 UPDATE 라 그런 순간이 존재하지 않는다.
     *
     * @return 1 = 이번 호출이 확정시켰다, 0 = 이미 처리된 이벤트다(중복 배달 — 건너뛴다)
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE refund
               SET pg_status    = 'COMPLETED',
                   journal_id   = :journalId,
                   pg_refund_id = :pgRefundId,
                   confirmed_at = now()
             WHERE id = :refundId
               AND pg_status = 'REQUESTED'
            """, nativeQuery = true)
    int markCompleted(@Param("refundId") Long refundId,
                      @Param("journalId") Long journalId,
                      @Param("pgRefundId") String pgRefundId);

    /**
     * PG 환불 거절 → FAILED. 장부는 건드리지 않는다 — 애초에 아무것도 안 적혔고, 안 일어난 일은
     * 적지 않는 게 맞다(그래서 보상 분개가 필요 없다).
     *
     * @return 1 = 이번 호출이 확정시켰다, 0 = 이미 처리된 이벤트다
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE refund
               SET pg_status    = 'FAILED',
                   confirmed_at = now()
             WHERE id = :refundId
               AND pg_status = 'REQUESTED'
            """, nativeQuery = true)
    int markFailed(@Param("refundId") Long refundId);

    /**
     * PG 무응답 → UNKNOWN. <b>예약도 장부도 건드리지 않는다.</b> 정보의 부재라 되돌릴 근거가 없다 —
     * 여기서 예약을 풀면 PG 는 환불했는데 잔액만 되살아나 이중환불이 열린다(W1D5 이중판매와 같은 구멍).
     *
     * <p>REQUESTED 에서만 넘어간다: 이미 COMPLETED/FAILED 로 확정된 건을 나중 배달이 UNKNOWN 으로
     * 되돌리면 확정된 진실이 흐려진다.
     *
     * @return 1 = 보류로 표시했다, 0 = 이미 확정된 건이다
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE refund
               SET pg_status    = 'UNKNOWN',
                   confirmed_at = now()
             WHERE id = :refundId
               AND pg_status = 'REQUESTED'
            """, nativeQuery = true)
    int markUnknown(@Param("refundId") Long refundId);
}
