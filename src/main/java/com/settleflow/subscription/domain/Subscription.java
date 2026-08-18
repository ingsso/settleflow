package com.settleflow.subscription.domain;

import com.settleflow.seller.domain.Seller;
import jakarta.persistence.*;
import lombok.Getter;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 구독 계약. 상태 축은 {@code ACTIVE → CANCELED} 단방향뿐이고, 연체는 여기 없다
 * ({@link SubscriptionStatus} · {@link SubscriptionHealth}).
 *
 * <p><b>주기 목록을 들고 있지 않다.</b> {@code @OneToMany} 를 걸면 연체 판정 한 번에 전 주기를
 * 끌어오게 되고, 구독은 수명이 길어 그 목록이 무한히 자란다. {@code Order↔OrderItem} 과
 * 다른 판단인데 이유가 그것이다 — 주문 항목은 생성 시 확정되고 끝나지만 주기는 매달 는다.
 * 그래서 파생 라벨({@link #health(long)})이 집계값을 <b>인자로</b> 받는다.
 */
@Getter
@Entity
@Table(name = "subscription")
public class Subscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 구독료를 <b>내는</b> 셀러. 판매 분개에서 셀러가 "돈을 받을 상대"였던 것과 방향이 반대다.
     * V4 가 {@code seller_id} 를 <i>"거래상대방이 아니라 분석 차원"</i> 으로 정의해둔 덕에
     * 장부 스키마를 안 건드리고 그대로 쓴다.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "seller_id", nullable = false, updatable = false)
    private Seller seller;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private SubscriptionStatus status;

    /**
     * <b>정책</b>이다 — 앞으로 얼마를 청구할 것인가. 변할 수 있다.
     * {@code BillingCycle.amount}(그때 얼마를 청구했나)와 겹쳐 보이지만 복제가 아니다:
     * 요금제를 바꿔도 지난 주기 금액은 그대로여야 한다.
     */
    @Column(name = "amount", nullable = false)
    private Long amount;

    @Column(name = "interval_days", nullable = false)
    private Integer intervalDays;

    /**
     * 다음 정기 청구일. <b>시계 둘 중 하나다</b> — 다른 하나는
     * {@link BillingCycle#getNextRetryAt()} 이고, 그 둘이 겹치는 자리가 W5 의 본 주제다.
     *
     * <p>파생 가능해 보이지만(마지막 주기 + interval) 저장한다. W5D4 가 <i>"PG 장애(OPEN) 시
     * 재시도 스킵, 다음 주기로 연기"</i> 를 요구하므로 이 값은 조정되고, 조정되는 순간
     * 계산식으로 돌아갈 수 없다(V8 의 "복제냐 별개의 사실이냐" 기준).
     *
     * <p>{@code LocalDate} 인 이유는 {@code settlement_date} 와 같다: 청구 단위는 시각이
     * 아니라 날짜다. 대가도 같다 — 이 값 안에 타임존이 없다.
     *
     * <p>CANCELED 면 null. 스키마가 {@code chk_subscription_canceled_shape} 로 강제한다.
     */
    @Column(name = "next_billing_date")
    private LocalDate nextBillingDate;

    @Column(name = "canceled_at")
    private OffsetDateTime canceledAt;

    /**
     * 낙관적 락. 전이는 단방향이라 그것만으로는 과하지만, {@link #nextBillingDate} 갱신을
     * 정기 청구 스케줄러와 재시도 연기 경로가 함께 노린다(W1D4 와 같은 역할).
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected Subscription() {   // JPA 전용
    }

    public Subscription(Seller seller, Long amount, Integer intervalDays, LocalDate firstBillingDate) {
        this.seller = seller;
        this.status = SubscriptionStatus.ACTIVE;
        this.amount = amount;
        this.intervalDays = intervalDays;
        this.nextBillingDate = firstBillingDate;
    }

    /**
     * 해지. 전이표:
     *
     * <pre>
     *   현재 \ 사건 | 해지
     *   ACTIVE     | → CANCELED
     *   CANCELED   | IGNORED
     * </pre>
     *
     * <p>CONFLICT 칸이 없다. 되돌아오는 화살표가 없으니 "모순되게 도착한 사건"이라는 게
     * 성립하지 않는다 — {@code Payment.onApproved} 가 FAILED 에서 CONFLICT 를 내는 것과
     * 대비된다. 축을 가른 대가가 여기서 이자로 돌아온다.
     *
     * <p><b>★ 미결(W5D2)</b>: 해지 사유를 구분하지 않는다. 사람이 해지한 것과 재시도 상한
     * 초과로 끊긴 것({@link BillingCycleStatus#ABANDONED} 의 귀결)이 같은 값이 된다. 지금은
     * 호출부가 없어 결정을 미룬다 — {@code Order.cancel()} 이 <i>"호출부가 생길 때 함께
     * 확정한다"</i> 로 남겨둔 것과 같은 이유다.
     */
    public SubscriptionTransitionResult cancel() {
        if (this.status == SubscriptionStatus.CANCELED) {
            return SubscriptionTransitionResult.IGNORED;
        }
        this.status = SubscriptionStatus.CANCELED;
        this.canceledAt = OffsetDateTime.now();
        this.nextBillingDate = null;   // chk_subscription_canceled_shape
        return SubscriptionTransitionResult.TRANSITIONED;
    }

    /**
     * 다음 청구일을 옮긴다. 정기 청구가 나간 뒤(한 주기 전진)와 W5D4 의 연기가 함께 쓴다.
     *
     * <p>날짜를 <b>인자로 받는다</b> — {@code intervalDays} 를 더하는 계산을 여기 두면
     * "연기"가 그 식을 우회하는 두 번째 경로가 되고, 다음 청구일을 정하는 규칙이 두 군데로
     * 흩어진다. 규칙은 호출부(W5D2 {@code DunningScheduler})에 하나로 둔다.
     *
     * @throws IllegalStateException 해지된 구독일 때. 해지 후 청구일을 옮기는 건 프로그래밍
     *                               오류다 — 스키마 CHECK 가 어차피 거절하므로 조용한 실패
     *                               대신 여기서 터뜨린다({@code Payment.requirePaid} 와 같은 태도).
     */
    public void moveBillingDateTo(LocalDate next) {
        if (this.status != SubscriptionStatus.ACTIVE) {
            throw new IllegalStateException(
                    "청구일 이동은 ACTIVE 구독에만 정의된다. subscriptionId=" + this.id + " status=" + this.status);
        }
        this.nextBillingDate = next;
    }

    /**
     * 청구 건강도(연체 여부). 저장하지 않는 이유와 규칙은 {@link SubscriptionHealth} 참고.
     *
     * <p><b>정의역은 ACTIVE 구독뿐이다.</b> 해지된 구독에 "연체인가"를 물으면 미납 주기가
     * 0 이라 태연히 {@code CURRENT} 를 돌려주는데, 사실이 아니라 <b>질문이 성립하지 않는</b>
     * 것이다. {@code Payment.refundStatus} 가 PAID 밖에서 예외를 던지는 것과 같은 구조 —
     * 조회는 던지고, 명령은 반환한다.
     *
     * @param unpaidCycleCount 이 구독의 {@code FAILED}·{@code ABANDONED} 주기 수.
     *                         집계 쿼리가 원천이다(위 "주기 목록을 들고 있지 않다" 참고).
     * @throws IllegalStateException 구독이 {@code ACTIVE} 가 아닐 때
     */
    public SubscriptionHealth health(long unpaidCycleCount) {
        if (this.status != SubscriptionStatus.ACTIVE) {
            throw new IllegalStateException(
                    "청구 건강도는 ACTIVE 구독에만 정의된다. subscriptionId=" + this.id + " status=" + this.status);
        }
        return SubscriptionHealth.of(unpaidCycleCount);
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
