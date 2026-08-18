package com.settleflow.subscription.service;

/**
 * 이 셀러에게 이미 활성 구독이 있다(W5D5).
 *
 * <p>서버 잘못이 아니라 <b>요청이 지금 성립하지 않는 것</b>이라 409 로 매핑된다 —
 * {@code OrderController} 의 재고 부족과 같은 결.
 */
public class ActiveSubscriptionExistsException extends RuntimeException {

    private final Long sellerId;

    public ActiveSubscriptionExistsException(Long sellerId) {
        super("이미 활성 구독이 있다. sellerId=" + sellerId);
        this.sellerId = sellerId;
    }

    public Long getSellerId() {
        return sellerId;
    }
}
