package com.settleflow.payment.domain;

import com.settleflow.ledger.domain.Journal;
import jakarta.persistence.*;
import lombok.Getter;

import java.time.OffsetDateTime;

/**
 * 환불 1건. append-only — 한 번 쓰면 고치지 않는다(W1D1 §5: 지우개가 아니라 빨간 펜).
 * 잘못된 환불은 이 행을 수정하는 게 아니라 정정 기록을 덧붙여 표현한다.
 *
 * 과다환불 방어는 이 테이블이 아니라 payment.refunded_amount 의
 * 원자적 조건부 UPDATE 가 한다(W1D1 §1, W1D2 RefundRaceTest 로 실측).
 * 따라서 INSERT 는 반드시 그 UPDATE 와 같은 트랜잭션에 묶여야 한다 —
 * 그래야 둘이 갈라지는(드리프트) 일이 애초에 생기지 않는다.
 *
 * <h3>W3D2 — Saga 가 되면서 열린 문 하나</h3>
 * 환불이 요청과 PG 확인 둘로 갈라지면서 {@link #pgStatus}·{@link #journal}·{@link #pgRefundId}·
 * {@link #confirmedAt} 네 칸이 <b>나중에 채워진다</b>. append-only 규율과 부딪히지 않는 이유는
 * {@code outbox.published_at} 을 열어준 것과 같다(V6 주석): <b>재무 진실은 {@link Journal} 이
 * 갖고, 이 칸들은 그 진실이 언제·어떻게 확정됐는지를 나르는 메커니즘</b>이다. 금액·결제·요청시각 —
 * 즉 "무슨 환불이 요청됐나"라는 사실 자체는 여전히 한 번 쓰고 고치지 않는다.
 *
 * <p><b>그래서 세터를 열지 않았다.</b> 상태 전이는 전부 {@code RefundRepository} 의 조건부 UPDATE
 * 한 문장으로만 일어난다 — 판정(이미 처리됐나)과 실행이 갈라질 수 없고, 엔티티에는 여전히
 * "고칠 수 있는 문"이 없다. W2D5 에서 journal 을 세터가 아니라 생성자로 받은 판단의 연장이다.
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

    /**
     * 역분개. <b>PG 확인 뒤에야 채워진다</b>(W3D2) — 요청 시점엔 null 이다.
     *
     * <p>W2D5 에선 생성자로 받았다. 그때는 요청 = 반영이라 journal 이 먼저 존재할 수 있었는데,
     * 이제 환불이 확정되기 전엔 장부에 적을 사실이 없다. {@code updatable} 을 열지 않은 건 이 칸도
     * 조건부 UPDATE(네이티브)로만 채우기 때문 — JPA 경로로는 쓸 수 없다.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "journal_id", unique = true, updatable = false)
    private Journal journal;

    @Column(name = "amount", nullable = false)
    private Long amount;

    /** Saga 진행 상태. 조건부 UPDATE 로만 바뀐다 — 그래서 {@code updatable = false}. */
    @Enumerated(EnumType.STRING)
    @Column(name = "pg_status", nullable = false, updatable = false)
    private RefundPgStatus pgStatus;

    /** PG 가 환불 확정 시 발급하는 번호. COMPLETED 에서만 채워진다(V7 CHECK). */
    @Column(name = "pg_refund_id", updatable = false)
    private String pgRefundId;

    /** 확정 시각(성공·거절·무응답 모두). {@code createdAt} 과의 간격이 Saga 지연. */
    @Column(name = "confirmed_at", updatable = false)
    private OffsetDateTime confirmedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected Refund() {   // JPA 전용
    }

    /**
     * 환불 요청 1건을 만든다. <b>journal 을 받지 않는다</b> — 요청 시점엔 아직 장부에 적을 사실이
     * 없기 때문이다(W3D2). {@link RefundPgStatus#REQUESTED} 로 시작한다.
     */
    public Refund(Payment payment, Long amount) {
        this.payment = payment;
        this.amount = amount;
        this.pgStatus = RefundPgStatus.REQUESTED;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = OffsetDateTime.now();
    }
}
