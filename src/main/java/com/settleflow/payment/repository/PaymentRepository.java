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
}
