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
 * {@code addRefundedAmount}·{@code decreaseStock} 이 쓴 것과 같은 패턴이다.
 *
 * <p>네이티브인 이유는 대상 컬럼들이 엔티티에서 {@code updatable = false} 이기 때문이다
 * ({@link Refund} 주석) — JPA 로는 못 쓰고 이 경로로만 쓴다. {@code addRefundedAmount} 와 같은 설계.
 */
public interface RefundRepository extends JpaRepository<Refund, Long> {

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
