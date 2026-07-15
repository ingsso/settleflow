package com.settleflow.payment.repository;

import com.settleflow.payment.domain.Payment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    /**
     * 웹훅이 되돌려준 merchant_payment_id 로 결제를 찾는다(W1D3).
     * pg_transaction_id 는 PENDING 생성 시점엔 NULL 이라 매칭에 못 쓴다.
     */
    Optional<Payment> findByMerchantPaymentId(UUID merchantPaymentId);
}
