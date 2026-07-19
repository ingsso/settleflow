package com.settleflow.payment.domain;

import com.settleflow.ledger.domain.Journal;
import jakarta.persistence.*;
import lombok.Getter;

import java.time.OffsetDateTime;

/**
 * 환불 1건. append-only — 한 번 쓰면 고치지 않는다(W1D1 §5: 지우개가 아니라 빨간 펜).
 * 잘못된 환불은 이 행을 수정하는 게 아니라 정정 기록을 덧붙여 표현한다.
 * updated_at 이 없는 것도 그래서다.
 *
 * 과다환불 방어는 이 테이블이 아니라 payment.refunded_amount 의
 * 원자적 조건부 UPDATE 가 한다(W1D1 §1, W1D2 RefundRaceTest 로 실측).
 * 따라서 INSERT 는 반드시 그 UPDATE 와 같은 트랜잭션에 묶여야 한다 —
 * 그래야 둘이 갈라지는(드리프트) 일이 애초에 생기지 않는다.
 */
@Getter
@Entity
@Table(name = "refund")
public class Refund {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_id", nullable = false)
    private Payment payment;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "journal_id", unique = true)
    private Journal journal;

    @Column(name = "amount", nullable = false)
    private Long amount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected Refund() {   // JPA 전용
    }

    public Refund(Payment payment, Long amount) {
        this.payment = payment;
        this.amount = amount;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = OffsetDateTime.now();
    }
}
