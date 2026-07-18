package com.settleflow.order.domain;

/**
 * 주문 전이표의 결과. {@code payment.domain.TransitionResult} 와 값이 같지만 일부러 분리했다.
 *
 * <p>의존 방향이 payment → order 라서(Payment 가 Order 를 참조), Order 가 payment 의 enum 을
 * 반환하면 순환이 된다. 또 PAID+취소 칸이 아직 미정이라 나중에 네 번째 값이 붙을 수 있는데,
 * 공용 타입이면 그 값이 결제 어휘로 새어 들어간다.
 */
public enum OrderTransitionResult {
    TRANSITIONED, IGNORED, CONFLICT
}
