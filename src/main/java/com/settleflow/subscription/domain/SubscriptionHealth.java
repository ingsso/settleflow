package com.settleflow.subscription.domain;

/**
 * 구독의 <b>청구 건강도</b>. 계획서 enum 에서 {@code PAST_DUE} 를 뽑아낸 자리다.
 *
 * <p>저장하지 않는다. {@link SubscriptionStatus} 와 <b>다른 축</b>이고, 원천은
 * {@code billing_cycle} 행들이다 — 컬럼으로 두면 원천이 둘이 되어 어긋날 수 있고,
 * 그게 W3D4 가 값을 치르고 닫은 병이다.
 *
 * <p>구조는 {@code RefundStatus} 와 같다: <b>규칙은 여기(순수 함수), 정의역은 엔티티</b>
 * ({@link Subscription#health(long)}). 이 enum 은 {@link SubscriptionStatus} 를 모른다.
 */
public enum SubscriptionHealth {

    /** 미납 주기가 없다. 계약이 살아있다는 뜻은 아니다 — 그건 다른 축이다. */
    CURRENT,

    /** 미납 주기가 하나 이상 있다. */
    PAST_DUE;

    /**
     * @param unpaidCycleCount {@code status IN ('FAILED','ABANDONED')} 인 주기 수.
     *                         {@code PENDING} 은 세지 않는다 — 결과를 기다리는 중이지
     *                         밀린 게 아니다. 이 구분이 무너지면 청구를 거는 순간
     *                         모든 구독이 연체가 된다.
     */
    public static SubscriptionHealth of(long unpaidCycleCount) {
        if (unpaidCycleCount < 0) {
            throw new IllegalArgumentException("미납 주기 수는 음수일 수 없다: " + unpaidCycleCount);
        }
        return unpaidCycleCount == 0 ? CURRENT : PAST_DUE;
    }
}
