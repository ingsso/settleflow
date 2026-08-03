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

    /**
     * <b>예약</b>이다 — "환불된 금액"이 아니라 COMPLETED + REQUESTED + UNKNOWN 의 합이다(V9 주석).
     * W3D2 에서 환불이 Saga 로 갈라지면서 의미가 바뀌었고, W3D4 에 이름을 사실에 맞췄다.
     *
     * <p>이 값을 쓰는 건 <b>가드 하나뿐</b>이다({@code PaymentRepository.reserveRefund} 의
     * {@code + :amount <= amount}). 환불 라벨은 여기서 파생하지 않는다 — {@link #refundStatus(long)}.
     */
    @Column(name = "reserved_refund_amount", updatable = false)
    private Long reservedRefundAmount;

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
        this.reservedRefundAmount = 0L;
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
     * 환불 진행 라벨(W2D4, 원천 교체 W3D4). 저장하지 않는 이유와 원천이 바뀐 이유는
     * {@link RefundStatus} 참고.
     *
     * <h3>왜 합계를 인자로 받나</h3>
     * W2D4 엔 인자가 없었다 — {@code refunded_amount} 가 이 행 안에 있었기 때문이다. W3D4 에 원천이
     * {@code refund} 테이블의 <b>확정분 합</b>으로 옮겨가면서 이 엔티티 혼자서는 답할 수 없게 됐다.
     * 그렇다고 메서드를 통째로 서비스로 내리면 <b>아래 정의역 판정이 호출부마다 복제된다</b> —
     * {@link RefundStatus#of} 는 순수 함수라 {@link PaymentStatus} 를 모른다.
     * 그래서 <b>규칙은 {@code RefundStatus.of}, 정의역은 여기</b>로 갈랐다. 밖으로 나간 건 계산이 아니라
     * <i>데이터 출처</i>뿐이다.
     *
     * <p><b>정의역은 {@code PAID} 인 결제뿐이다.</b> 파생식은 금액 두 개만 보므로, PENDING 결제에
     * 물으면 확정 환불액이 0 이라 태연히 "환불 없음"을 돌려준다 — 사실이 아니다.
     * 애초에 받은 돈이 없어 환불이라는 축 자체가 성립하지 않는다. 정의역 밖 호출은
     * <b>프로그래밍 오류</b>이므로 조용한 거짓말 대신 예외로 터뜨린다.
     *
     * <p>대비: 환불 <i>요청</i>이 PENDING 결제로 들어오는 건 예상된 입력이라 예외가 아니라
     * {@link RefundResult#NOT_REFUNDABLE} 로 돌려준다. 조회는 던지고, 명령은 반환한다.
     *
     * @param completedAmount {@code RefundRepository.sumCompletedAmount} 가 준 <b>확정</b> 환불액 합.
     *                        예약({@link #reservedRefundAmount})을 넘기면 진행중인 환불이 환불된 것으로
     *                        세어진다 — W3D2 가 만들고 W3D4 가 닫은 바로 그 버그다.
     * @throws IllegalStateException 결제가 {@code PAID} 가 아닐 때
     */
    public RefundStatus refundStatus(long completedAmount) {
        requirePaid("환불 상태");
        return RefundStatus.of(this.amount, completedAmount);
    }

    /**
     * 지금 더 환불받을 수 있는 최대 금액. <b>예약 기준</b>이다 — 확정 기준이 아니다.
     *
     * <p>{@code PaymentRepository.reserveRefund} 의 {@code + :amount <= amount} 가 실제로 판정하는 값과
     * 같은 식이라, 여기 나온 수보다 큰 요청은 반드시 거부된다. <b>다만 이건 안내지 보증이 아니다</b> —
     * 읽은 뒤 UPDATE 사이에 다른 환불이 끼어들 수 있다. 정합성은 언제나 그 한 문장이 지킨다
     * (W1D2 실측: 조회 후 판단으로 바꾸면 8,000 이 11,000).
     *
     * <p>{@link #refundStatus(long)} 과 <b>다른 축</b>이다. 전액 환불을 요청만 해둔 상태면 라벨은
     * {@code NOT_REFUNDED}(아직 확정 안 됨) 인데 이 값은 0(더는 못 받음) 이다. 모순처럼 보이지만
     * 둘 다 참이고, 둘을 같이 보여줘야 "왜 409 가 나는지"가 설명된다(W3D4 결정 3-다).
     *
     * @throws IllegalStateException 결제가 {@code PAID} 가 아닐 때 — {@code refundStatus} 와 같은 정의역
     */
    public long remainingRefundable() {
        requirePaid("환불 가능액");
        return this.amount - this.reservedRefundAmount;
    }

    private void requirePaid(String what) {
        if (this.status != PaymentStatus.PAID) {
            throw new IllegalStateException(
                    what + "은 PAID 결제에만 정의된다. paymentId=" + this.id + " status=" + this.status);
        }
    }

    /**
     * PG 거래번호를 연결한다. 처음이면 채우고, 같은 값이 또 오면 그대로 둔다(멱등).
     *
     * 이미 <b>다른</b> 번호가 있는데 새 번호가 오면 모순이다(D3 미결 #4) — 같은 결제에
     * PG 거래번호가 둘일 리 없다. 조용히 덮어쓰지 않고 false 로 알린다. 호출부가 알람을 울린다.
     *
     * @return true = 연결됨/이미 같음(정상), false = 기존과 다른 번호(모순)
     */
    /**
     * 결제 확정 시 만들어진 분개(journal)를 연결한다. PENDING 동안엔 null 이고
     * TRANSITIONED 부수효과에서 한 번만 세팅된다(W2D1 §3). journal_id 는 UNIQUE(V3).
     */
    public void linkJournal(Journal journal) {
        this.journal = journal;
    }

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
