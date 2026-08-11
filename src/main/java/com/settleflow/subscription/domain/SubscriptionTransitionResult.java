package com.settleflow.subscription.domain;

/**
 * 구독 컨텍스트의 전이 결과. {@link Subscription} 과 {@link BillingCycle} 이 함께 쓴다.
 *
 * <p>order·payment 는 값이 같은 enum 을 <b>일부러 둘로 나눴다</b>({@code OrderTransitionResult}
 * javadoc). 이유가 의존 방향의 순환이었는데(Payment 가 Order 를 참조), 여기 둘은 같은 패키지
 * 같은 애그리거트라 그 사정이 없다. 값 집합이 갈릴 이유가 생기면 그때 나눈다.
 *
 * <p><b>STALE 이 없는 게 설계다.</b> 낡은 사건을 따로 표시할 필요가 없다 — 사건은 구독이 아니라
 * <b>자기 주기 행</b>으로 가고, 그 행이 이미 종착지면 {@code IGNORED} 가 나온다. "낡음"이
 * 상태 판정으로 환원되는 것, 그게 주기를 행으로 내린 목적이다.
 */
public enum SubscriptionTransitionResult {
    TRANSITIONED, IGNORED, CONFLICT
}
