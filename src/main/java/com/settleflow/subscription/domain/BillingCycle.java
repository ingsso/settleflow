package com.settleflow.subscription.domain;

import jakarta.persistence.*;
import lombok.Getter;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 청구 주기 1회. <b>W5D1 의 핵심 산출물이고, 상태머신의 실체다</b>({@link BillingCycleStatus}).
 *
 * <p>이 엔티티가 존재하는 이유는 구독 상태머신이 되돌아오기 때문이다. 되돌아오는 상태머신에서는
 * 상태가 사건의 기억을 못 하고, 그러면 낡은 사건이 실재하는 사실을 지운다:
 *
 * <pre>
 *   3주기 성공 → ACTIVE / 4주기 실패 → PAST_DUE / 3주기의 지연된 성공 웹훅 도착
 *   → PAST_DUE 는 종착지가 아니므로 전이 → ACTIVE → 4주기 연체가 사라진다 (ABA)
 * </pre>
 *
 * <p>주기를 행으로 내리면 그 웹훅은 구독이 아니라 <b>3주기 행</b>으로 가고, 그 행은 이미
 * {@code PAID} 라 {@code IGNORED} 다. 4주기 행은 손도 안 탄다. W1D4 멱등 backbone 이
 * 다시 사는 게 이 지점이다.
 */
@Getter
@Entity
@Table(name = "billing_cycle")
public class BillingCycle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "subscription_id", nullable = false)
    private Subscription subscription;

    /**
     * 1 부터 단조증가. <b>되돌아오지 않는 축</b>이라 {@code (status, cycleNo)} 쌍 위에서 보면
     * 전이표가 다시 DAG 다.
     *
     * <p>다음 번호를 여기서 계산하지 않는다 — 동시에 두 스케줄러가 같은 번호를 뽑는 걸 막는 건
     * 애플리케이션 판정이 아니라 {@code uq_billing_cycle_subscription_no} 다
     * (W1D2: <i>"정합성은 언제나 그 한 문장이 지킨다"</i>).
     */
    @Column(name = "cycle_no", nullable = false, updatable = false)
    private Integer cycleNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private BillingCycleStatus status;

    /** 청구 시점 <b>스냅샷</b>. {@code Subscription.amount}(정책)를 참조하지 않는다. */
    @Column(name = "amount", nullable = false, updatable = false)
    private Long amount;

    @Column(name = "period_start", nullable = false, updatable = false)
    private LocalDate periodStart;

    @Column(name = "period_end", nullable = false, updatable = false)
    private LocalDate periodEnd;

    /**
     * 다음 재시도 시각. 시계 둘 중 나머지 하나({@code Subscription.nextBillingDate} 와 짝).
     *
     * <p><b>타입이 다른 게 실수가 아니다.</b> 청구일은 날짜 단위지만 재시도는 "1일 뒤 이 시각"이라
     * 하루 안에서 순서가 뜻을 갖는다.
     *
     * <p>{@code FAILED} 인 동안에만 값이 있다 — {@code chk_billing_cycle_retry_shape} 가
     * 스키마 차원에서 강제한다. 종착지로 가면서 비우는 걸 잊으면 스케줄러가 끝난 주기를
     * 계속 집는데, 그 실수를 코드 리뷰가 아니라 DB 가 잡는다.
     */
    @Column(name = "next_retry_at")
    private OffsetDateTime nextRetryAt;

    /**
     * 낙관적 락. 같은 주기에 두 사건이 동시에 닿을 때 부수효과 1회를 보증한다
     * ({@code Payment.version} 과 같은 역할, W1D4).
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected BillingCycle() {   // JPA 전용
    }

    public BillingCycle(Subscription subscription, int cycleNo, long amount,
                        LocalDate periodStart, LocalDate periodEnd) {
        this.subscription = subscription;
        this.cycleNo = cycleNo;
        this.status = BillingCycleStatus.PENDING;
        this.amount = amount;
        this.periodStart = periodStart;
        this.periodEnd = periodEnd;
    }

    /**
     * 이 주기의 청구가 성공했다. 전이표:
     *
     * <pre>
     *   현재 \ 사건 | 성공      | 실패(재시도 남음) | 실패(상한 초과)
     *   PENDING    | → PAID   | → FAILED         | → ABANDONED
     *   FAILED     | → PAID   | → FAILED (연기)   | → ABANDONED
     *   PAID       | IGNORED  | CONFLICT         | CONFLICT
     *   ABANDONED  | CONFLICT | CONFLICT         | IGNORED
     * </pre>
     *
     * <p>대각선의 두 {@code IGNORED} 가 종착지 둘이다 — "이미 그 결말에 도달했다". 나머지
     * 종착지 칸은 전부 {@code CONFLICT} 다: 결말이 이미 정해진 주기에 다른 결말이 도착했다.
     *
     * <p><b>ABANDONED + 성공 = CONFLICT.</b> 포기 처리한 뒤 마지막 시도가 늦게 성공한 경우다.
     * 돈은 실제로 들어왔으니 {@code PAID} 로 옮기고 싶어지지만, 그 사이 구독이 해지됐을 수
     * 있고 그걸 조용히 되돌리는 건 더 나쁘다. 상태를 안 바꾸고 호출부가 기록·알람한다
     * (W1D1: <i>"지우개가 아니라 빨간 펜"</i>). {@code payment} 행에는 {@code PAID} 로
     * 남아 있으므로 <b>돈의 기록이 사라지는 건 아니다</b> — 어긋남만 남는다.
     */
    public SubscriptionTransitionResult onPaymentSucceeded() {
        if (this.status == BillingCycleStatus.PAID) return SubscriptionTransitionResult.IGNORED;
        if (this.status == BillingCycleStatus.ABANDONED) return SubscriptionTransitionResult.CONFLICT;

        this.status = BillingCycleStatus.PAID;
        this.nextRetryAt = null;   // chk_billing_cycle_retry_shape
        return SubscriptionTransitionResult.TRANSITIONED;
    }

    /**
     * 이 주기의 청구 시도가 실패했고, <b>재시도가 남았다</b>. 상태는 {@code FAILED} 로 가거나
     * 이미 {@code FAILED} 면 그대로 머물면서 {@link #nextRetryAt} 만 밀린다.
     *
     * <h3>여기엔 IGNORED 가 없다 — 그리고 그게 위험한 자리다</h3>
     * 시도 2회가 연달아 실패하면 상태는 둘 다 {@code FAILED} 지만 <b>서로 다른 사건</b>이다.
     * 그래서 이 메서드는 상태만 보고 "이미 반영됨"을 판정할 수 없고, 같은 실패가 두 번
     * 통보되면 재시도 시각을 두 번 민다.
     *
     * <p><b>전제: 시도 1회당 정확히 한 번 불린다.</b> 그 보증은 이 엔티티가 아니라 시도 층에
     * 있다 — 시도 하나 = {@code payment} 행 하나이고, {@code payment} 의 전이표가 중복 통보를
     * {@code IGNORED} 로 흡수한 뒤 {@code TRANSITIONED} 인 1회에만 여기까지 온다
     * ({@code PaymentTransitionService} 의 부수효과 자리와 같은 구조).
     *
     * <p>시도를 카운터 한 칸으로 뒀다면 이 전제를 세울 곳이 없었다. <b>"시도마다 행"이
     * 선택이 아니라 필수인 이유가 이 메서드다.</b> (연결 방식은 W5D3 미결 — V12 주석 참고.)
     *
     * @param nextRetryAt 다음 재시도 시각. 간격 규칙(1일→3일→7일)은 호출부가 소유한다 —
     *                    엔티티는 시도 횟수를 모르기 때문이다({@code attempt_count} 를 두지
     *                    않은 결과). {@code Payment.refundStatus(long)} 가 합계를 인자로 받는
     *                    것과 같은 모양: <b>원천은 밖으로, 규칙은 엔티티에</b>.
     * @throws IllegalArgumentException {@code nextRetryAt} 이 null 일 때. null 을 "포기"로
     *                                  쓰지 않는다 — 한 인자가 시각과 포기 여부를 겸하는 순간
     *                                  오늘 하루 걷어낸 그 모양이 된다. 포기는
     *                                  {@link #onRetriesExhausted()} 라는 별도 사건이다.
     */
    public SubscriptionTransitionResult onPaymentFailed(OffsetDateTime nextRetryAt) {
        if (nextRetryAt == null) {
            throw new IllegalArgumentException(
                    "재시도 시각이 없다. 포기라면 onRetriesExhausted() 를 부를 것. cycleId=" + this.id);
        }
        if (this.status == BillingCycleStatus.PAID
                || this.status == BillingCycleStatus.ABANDONED) {
            return SubscriptionTransitionResult.CONFLICT;
        }

        this.status = BillingCycleStatus.FAILED;
        this.nextRetryAt = nextRetryAt;
        return SubscriptionTransitionResult.TRANSITIONED;
    }

    /**
     * 재시도 상한을 넘겼다 — 이 주기는 포기한다. 종착지로 간다.
     *
     * <p>{@link #onPaymentFailed} 와 갈라둔 이유는 위 {@code @throws} 참고. 갈라두면
     * 호출부에서 <b>정책 결정이 이름으로 드러난다</b> — {@code DunningScheduler}(W5D2) 가
     * 상한을 판정했다는 사실이 호출 한 줄에 보인다.
     *
     * <p>이미 {@code ABANDONED} 면 {@code IGNORED}. {@code PAID} 면 {@code CONFLICT} 다 —
     * 받은 주기를 포기할 수는 없다.
     */
    public SubscriptionTransitionResult onRetriesExhausted() {
        if (this.status == BillingCycleStatus.ABANDONED) return SubscriptionTransitionResult.IGNORED;
        if (this.status == BillingCycleStatus.PAID) return SubscriptionTransitionResult.CONFLICT;

        this.status = BillingCycleStatus.ABANDONED;
        this.nextRetryAt = null;   // chk_billing_cycle_retry_shape
        return SubscriptionTransitionResult.TRANSITIONED;
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
