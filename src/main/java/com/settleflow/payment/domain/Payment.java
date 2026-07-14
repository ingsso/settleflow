package com.settleflow.payment.domain;

import com.settleflow.order.domain.Order;
import jakarta.persistence.*;
import lombok.Getter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Entity
@Table(name = "payment")
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Column(name = "merchant_payment_id", nullable = false, unique = true, updatable = false)
    private UUID merchantPaymentId;

    @Column(name = "pg_transaction_id", unique = true)
    private String pgTransactionId;

    @Column(name = "status")
    @Enumerated(EnumType.STRING)
    private PaymentStatus status;

    @Column(name = "amount", nullable = false)
    private Long amount;

    @Column(name = "refunded_amount", updatable = false)
    private Long refundedAmount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected Payment() {}

    public Payment(Order order, Long amount) {
        this.order = order;
        this.amount = amount;
        this.merchantPaymentId = UUID.randomUUID();
        this.status = PaymentStatus.PENDING;
        this.refundedAmount = 0L;
    }

    public TransitionResult onApproved() {
        if (this.status == PaymentStatus.PAID) return TransitionResult.IGNORED;
        else if (this.status == PaymentStatus.FAILED) return TransitionResult.CONFLICT;

        this.status = PaymentStatus.PAID;
        return TransitionResult.TRANSITIONED;
    }

    public TransitionResult onDeclined() {
        if (this.status == PaymentStatus.FAILED) return TransitionResult.IGNORED;
        else if (this.status == PaymentStatus.PAID) return TransitionResult.CONFLICT;

        this.status = PaymentStatus.FAILED;
        return TransitionResult.TRANSITIONED;
    }

    public TransitionResult onTimedOut() {
        if (this.status != PaymentStatus.PENDING) return TransitionResult.IGNORED;

        this.status = PaymentStatus.UNKNOWN;
        return TransitionResult.TRANSITIONED;
    }

    public void linkPgTransaction(String pgTransactionId) {
        this.pgTransactionId = pgTransactionId;
    }

    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = OffsetDateTime.now();
    }
}
