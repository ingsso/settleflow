package com.settleflow.product.service;

/**
 * 재고 부족으로 차감이 거부됐다(조건부 UPDATE의 영향 행 0).
 *
 * RuntimeException 이라 @Transactional 이 자동으로 롤백한다 —
 * 이미 만든 Order 와 앞서 깎은 다른 상품의 재고까지 함께 되돌아간다.
 */
public class OutOfStockException extends RuntimeException {

    private final Long productId;

    public OutOfStockException(Long productId, int requested) {
        super("재고 부족: productId=%d, 요청수량=%d".formatted(productId, requested));
        this.productId = productId;
    }

    public Long getProductId() {
        return productId;
    }
}
