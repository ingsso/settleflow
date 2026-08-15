package com.settleflow.payment.repository;

import com.settleflow.payment.domain.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    /**
     * 웹훅이 되돌려준 merchant_payment_id 로 결제를 찾는다(W1D3).
     * pg_transaction_id 는 PENDING 생성 시점엔 NULL 이라 매칭에 못 쓴다.
     */
    Optional<Payment> findByMerchantPaymentId(UUID merchantPaymentId);

    /**
     * 그 시간 범위에 <b>승인 분개가 찍힌</b> 결제들(W3D4 대사).
     *
     * <h3>왜 payment 의 시각 컬럼을 안 쓰나</h3>
     * 쓸 수 있는 게 없다. {@code created_at} 은 PENDING 생성 시점이라 승인 시각이 아니고,
     * {@code updated_at} 은 {@link #reserveRefund} 도 갱신하므로 <b>나중에 환불 요청이 들어오면
     * 그 결제의 시각이 환불일로 밀린다</b>(7/1 결제가 7/5 환불되면 7/1 대사에서 사라진다).
     * 이 테이블엔 "승인 시각" 컬럼이 없다.
     *
     * <p>그래서 <b>journal 을 경유해 시각만 빌린다</b>. 대사가 장부와 공유하는 건 여기까지 —
     * <i>어느 결제가 이 날짜에 속하는가</i>이고, <i>그 결제가 얼마인가</i>는 전부 독립 계산이다
     * ({@code ReconciliationService}). 그 대가로 <b>날짜 경계 로직의 버그는 잡히지 않는다</b>
     * (배치가 경계를 잘못 잡으면 대사도 같이 잘못 잡는다). 금액 계산 버그는 잡힌다.
     *
     * <p>PAID 결제만 {@code journal_id} 를 갖는다(W2D1 §3)므로 JOIN 이 상태 필터를 겸한다.
     */
    @Query(value = """
            SELECT p.*
              FROM payment p
              JOIN journal j ON j.id = p.journal_id
             WHERE j.created_at >= :from
               AND j.created_at <  :to
             ORDER BY p.id
            """, nativeQuery = true)
    List<Payment> findPaidInRange(@Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to);

    /**
     * 이 주기에서 <b>결말이 난</b> 청구 시도 수(W5D2). {@code DunningPolicy} 의 정의역이다.
     *
     * <h3>왜 UNKNOWN 을 세나</h3>
     * {@code FAILED} 만 세면 <b>타임아웃 시도가 공짜가 된다</b> — PG 가 느린 동안 시도 수가
     * 안 늘어 재시도가 줄지 않는다. W4D5 가 소비자 javadoc 에서 <i>"무한 재시도"</i> 를 찾아낸
     * 그 모양을 우리 손으로 만드는 셈이다.
     *
     * <p>대가는 있다: {@code UNKNOWN} 이 나중에 {@code PAID} 로 풀리면 이 셈은 틀렸던 게 된다.
     * 그 대가가 갈 자리는 이미 있다 — 상한 초과로 {@code ABANDONED} 된 주기에 성공이 도착하면
     * {@code BillingCycle.onPaymentSucceeded} 가 {@code CONFLICT} 를 낸다. 돈은
     * {@code payment} 행에 {@code PAID} 로 남고 어긋남만 기록된다(W1D1: 지우개가 아니라 빨간 펜).
     *
     * <h3>PENDING 은 안 센다 — 그리고 그게 한 칸 어긋나는 자리다</h3>
     * 진행 중인 시도는 결말이 아니므로 뺀다. 그래서 <b>실패 통보를 반영하기 직전에 이걸 부르면
     * 방금 실패한 그 시도가 아직 {@code PENDING} 이라 안 세어진다</b> — 호출부가 +1 해야 한다
     * ({@code SubscriptionChargeService.concludeFailure}).
     *
     * <p>결말 전에 프로세스가 죽으면 {@code PENDING} 행이 영영 남아 이 셈에 안 잡힌다.
     * 그것만으로 시도가 무한해지지는 않는다 — 시도를 걸기 직전에도 상한을 보기 때문이다
     * ({@code DunningPolicy.hasRetryLeft}). 다만 <b>그 고아 행이 쌓이는 것 자체는 안 치운다</b>
     * (W5D2 이월).
     */
    @Query(value = """
            SELECT count(*)
              FROM payment
             WHERE billing_cycle_id = :billingCycleId
               AND status IN ('FAILED', 'UNKNOWN')
            """, nativeQuery = true)
    int countConcludedAttempts(@Param("billingCycleId") Long billingCycleId);

    /**
     * 위와 같되 <b>지금 결말을 내려는 시도 한 건을 뺀다</b>(W5D3).
     *
     * <h3>왜 갈랐나 — {@code +1} 이 어긋나는 자리가 있다</h3>
     * 실패 결말 처리는 <i>"이번을 포함해 몇 번째냐"</i> 를 묻기 때문에 세어진 값에 1 을 더한다.
     * 그 계산은 <b>이번 시도가 아직 {@code PENDING} 일 때만</b> 맞다.
     *
     * <p>동기 경로(폴러)에서는 늘 맞았다 — 방금 만든 행이라 {@code PENDING} 이다. 그런데
     * <b>웹훅 경로가 생기면서 깨진다</b>: 시도가 먼저 타임아웃돼 {@code UNKNOWN} 이 된 뒤
     * 나중에 PG 가 거절 웹훅을 보내면, 그 행은 <b>이미 세어지는 쪽</b>에 있다.
     * 거기에 {@code +1} 을 하면 시도 하나를 두 번 세고 사다리가 한 칸 짧아진다.
     *
     * <p>그래서 결말 처리는 언제나 이 메서드를 쓴다 — 이번 시도를 빼고 세어 {@code +1} 하면
     * 현재 상태와 무관하게 항상 맞다. {@link #countConcludedAttempts} 는 <b>이번 시도가 없는</b>
     * 자리(집기 직전 백스톱)에서만 쓴다.
     */
    @Query(value = """
            SELECT count(*)
              FROM payment
             WHERE billing_cycle_id = :billingCycleId
               AND status IN ('FAILED', 'UNKNOWN')
               AND id <> :excludePaymentId
            """, nativeQuery = true)
    int countConcludedAttemptsExcluding(@Param("billingCycleId") Long billingCycleId,
                                        @Param("excludePaymentId") Long excludePaymentId);

    /**
     * 환불 금액을 원자적으로 <b>예약</b>한다(W2D4, 개명 W3D4). <b>판정과 실행이 한 문장이다</b> —
     * 반환된 영향 행 수가 곧 "환불 요청을 받아주는가"의 답이다.
     *
     * <p><b>이름이 {@code addRefundedAmount} 였다.</b> W2D4 엔 그게 맞았다 — 요청 = 반영이라 누적된
     * 값이 곧 환불액이었다. W3D2 가 환불을 Saga 로 쪼개면서 이 누적은 <i>확정</i>이 아니라 예약이
     * 됐는데(역분개는 PG 확인 뒤로 갔다) 이름만 옛 사실에 남아 있었다. 컬럼과 함께 고쳤다(V9).
     *
     * <p><b>왜 조회 후 판단이 아닌가.</b> "잔액을 읽고 → 충분한지 앱에서 판단하고 → UPDATE" 는
     * 읽기와 쓰기 사이에 틈이 있고, 동시 환불 둘이 그 틈에서 같은 잔액을 읽어 둘 다 통과한다
     * (lost update). W1D2 {@code RefundRaceTest} 에서 실측한 그대로 — 한도 조건을 WHERE 에서
     * 뺐더니 8,000 이어야 할 값이 <b>11,000</b> 이 됐다. 그 조건이 여기 {@code WHERE} 에 있는 게
     * 이 메서드의 존재 이유다.
     *
     * <p>JPQL 이 아니라 네이티브인 건 {@code reserved_refund_amount} 가 엔티티에서
     * {@code updatable = false} 이기 때문이다 — JPA 로는 못 쓰고 이 경로로만 쓴다(W1D2 설계).
     * 그래서 {@code @PreUpdate} 도 안 타므로 {@code updated_at} 을 직접 갱신한다.
     *
     * <p><b>{@code status} 조건이 없는 이유</b>: {@code PAID} 는 전이표의 종착지라 밖으로 나가는
     * 화살표가 없다({@code onApproved}/{@code onDeclined} 모두 PAID 를 안 움직인다). 그래서 앱 가드가
     * 확인한 PAID 가 이 UPDATE 사이에 바뀔 레이스가 없다. <b>PAID 를 떠나는 전이가 생기면
     * 여기에 {@code AND status = 'PAID'} 를 반드시 추가해야 한다.</b>
     *
     * @return 1 = 예약됨(요청 접수), 0 = 잔액 초과로 거부됨
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE payment
               SET reserved_refund_amount = reserved_refund_amount + :amount,
                   updated_at = now()
             WHERE id = :paymentId
               AND reserved_refund_amount + :amount <= amount
            """, nativeQuery = true)
    int reserveRefund(@Param("paymentId") Long paymentId, @Param("amount") long amount);

    /**
     * 예약을 푼다(W3D2). PG 가 환불을 <b>거절했을 때만</b> 부른다.
     *
     * <h3>이 메서드가 필요한 이유</h3>
     * {@link #reserveRefund} 가 쌓는 건 "환불된 금액"이 아니라 <b>"환불된 + 진행중인 금액"</b>이다.
     * 역분개 journal 을 PG 확인 뒤로 미뤘는데도 이 UPDATE 만은 요청 시점에 남겨뒀기
     * 때문이다 — 미루면 잔액을 판정할 근거가 아직 없어 동시 환불이 전부 통과한다(W1D2 실측).
     * 즉 요청 시점의 누적은 <b>예약</b>이고, 이건 그 예약의 해제다. W1D3 의 재고와 같은 모양이다
     * (주문 시 원자적 차감 → 결제 실패 시 배치가 {@code restore}).
     *
     * <h3>거절에만 부르는 이유</h3>
     * PG 무응답(UNKNOWN)에 부르면 <b>이중환불이 열린다</b> — PG 는 실제로 환불했는데 잔액만
     * 되살아나 같은 돈을 또 내보낼 수 있다. W1D5 에서 쓸이 배치가 재고를 성급히 복원해 이중판매
     * 구멍이 열렸던 것과 같은 실수고, 거기서 얻은 규칙이 그대로 적용된다: <b>복원은 시간의
     * 문제가 아니라 사실 확인의 문제</b>다. 그래서 UNKNOWN 은 아무것도 되돌리지 않고 보류한다.
     *
     * <p><b>{@code WHERE} 의 하한 조건</b>은 언더플로 백스톱이다. 정상 경로에선 자기가 올린 만큼만
     * 내리므로 음수가 될 수 없지만, 그 "정상"이 앱의 규율이라 스키마 쪽에도 벽을 세운다
     * ({@code chk_payment_reserved_refund_amount} 와 같은 결). 0 이 돌아오면 앱이 짝이 안 맞는 해제를
     * 시도한 것 — 프로그래밍 오류다.
     *
     * @return 1 = 예약 해제됨, 0 = 해제할 수 없음(언더플로 — 호출부의 버그)
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE payment
               SET reserved_refund_amount = reserved_refund_amount - :amount,
                   updated_at = now()
             WHERE id = :paymentId
               AND reserved_refund_amount - :amount >= 0
            """, nativeQuery = true)
    int releaseRefundReservation(@Param("paymentId") Long paymentId, @Param("amount") long amount);
}
