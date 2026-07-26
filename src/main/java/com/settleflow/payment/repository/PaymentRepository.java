package com.settleflow.payment.repository;

import com.settleflow.payment.domain.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    /**
     * 웹훅이 되돌려준 merchant_payment_id 로 결제를 찾는다(W1D3).
     * pg_transaction_id 는 PENDING 생성 시점엔 NULL 이라 매칭에 못 쓴다.
     */
    Optional<Payment> findByMerchantPaymentId(UUID merchantPaymentId);

    /**
     * 환불액을 원자적으로 누적한다(W2D4). <b>판정과 실행이 한 문장이다</b> —
     * 반환된 영향 행 수가 곧 "환불이 허용됐는가"의 답이다.
     *
     * <p><b>왜 조회 후 판단이 아닌가.</b> "잔액을 읽고 → 충분한지 앱에서 판단하고 → UPDATE" 는
     * 읽기와 쓰기 사이에 틈이 있고, 동시 환불 둘이 그 틈에서 같은 잔액을 읽어 둘 다 통과한다
     * (lost update). W1D2 {@code RefundRaceTest} 에서 실측한 그대로 — 한도 조건을 WHERE 에서
     * 뺐더니 8,000 이어야 할 값이 <b>11,000</b> 이 됐다. 그 조건이 여기 {@code WHERE} 에 있는 게
     * 이 메서드의 존재 이유다.
     *
     * <p>JPQL 이 아니라 네이티브인 건 {@code refunded_amount} 가 엔티티에서
     * {@code updatable = false} 이기 때문이다 — JPA 로는 못 쓰고 이 경로로만 쓴다(W1D2 설계).
     * 그래서 {@code @PreUpdate} 도 안 타므로 {@code updated_at} 을 직접 갱신한다.
     *
     * <p><b>{@code status} 조건이 없는 이유</b>: {@code PAID} 는 전이표의 종착지라 밖으로 나가는
     * 화살표가 없다({@code onApproved}/{@code onDeclined} 모두 PAID 를 안 움직인다). 그래서 앱 가드가
     * 확인한 PAID 가 이 UPDATE 사이에 바뀔 레이스가 없다. <b>PAID 를 떠나는 전이가 생기면
     * 여기에 {@code AND status = 'PAID'} 를 반드시 추가해야 한다.</b>
     *
     * @return 1 = 환불 반영됨, 0 = 잔액 초과로 거부됨
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE payment
               SET refunded_amount = refunded_amount + :amount,
                   updated_at = now()
             WHERE id = :paymentId
               AND refunded_amount + :amount <= amount
            """, nativeQuery = true)
    int addRefundedAmount(@Param("paymentId") Long paymentId, @Param("amount") long amount);

    /**
     * 예약을 푼다(W3D2). PG 가 환불을 <b>거절했을 때만</b> 부른다.
     *
     * <h3>이 메서드가 필요한 이유</h3>
     * {@link #addRefundedAmount} 는 이제 "환불된 금액"이 아니라 <b>"환불된 + 진행중인 금액"</b>을
     * 누적한다. 역분개 journal 을 PG 확인 뒤로 미뤘는데도 이 UPDATE 만은 요청 시점에 남겨뒀기
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
     * ({@code chk_payment_refunded_amount} 와 같은 결). 0 이 돌아오면 앱이 짝이 안 맞는 해제를
     * 시도한 것 — 프로그래밍 오류다.
     *
     * @return 1 = 예약 해제됨, 0 = 해제할 수 없음(언더플로 — 호출부의 버그)
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE payment
               SET refunded_amount = refunded_amount - :amount,
                   updated_at = now()
             WHERE id = :paymentId
               AND refunded_amount - :amount >= 0
            """, nativeQuery = true)
    int releaseRefundedAmount(@Param("paymentId") Long paymentId, @Param("amount") long amount);
}
