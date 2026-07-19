package com.settleflow.payment.domain;

import com.settleflow.ledger.domain.Journal;
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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "journal_id", unique = true)
    private Journal journal;

    @Column(name = "merchant_payment_id", nullable = false, unique = true, updatable = false)
    private UUID merchantPaymentId;

    @Column(name = "pg_transaction_id", unique = true)
    private String pgTransactionId;

    @Column(name = "status")
    @Enumerated(EnumType.STRING)
    private PaymentStatus status;

    /**
     * 낙관적 락. 전이표의 backbone(W1D4).
     * 동시에 두 사건이 같은 PENDING 을 전이시키려 하면, 먼저 커밋한 쪽이 version 을 올리고
     * 나중 쪽 save 는 version 불일치로 OptimisticLockException — 재조회하면 이미 종착지라
     * on*() 가 IGNORED 를 돌려준다. 부수효과(분개 등)가 한 번만 실행됨을 보장한다.
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

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

    /**
     * PG 거래번호를 연결한다. 처음이면 채우고, 같은 값이 또 오면 그대로 둔다(멱등).
     *
     * 이미 <b>다른</b> 번호가 있는데 새 번호가 오면 모순이다(D3 미결 #4) — 같은 결제에
     * PG 거래번호가 둘일 리 없다. 조용히 덮어쓰지 않고 false 로 알린다. 호출부가 알람을 울린다.
     *
     * @return true = 연결됨/이미 같음(정상), false = 기존과 다른 번호(모순)
     */
    public boolean linkPgTransaction(String pgTransactionId) {
        if (pgTransactionId == null || pgTransactionId.equals(this.pgTransactionId)) {
            return true;
        }
        if (this.pgTransactionId == null) {
            this.pgTransactionId = pgTransactionId;
            return true;
        }
        return false;   // 기존에 다른 번호가 있다 — 덮어쓰지 않는다
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
