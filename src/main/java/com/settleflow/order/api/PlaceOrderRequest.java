package com.settleflow.order.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 주문 요청 바디(W1D5).
 *
 * <p>금액을 받지 않는다 — 가격은 서버가 Product 에서 직접 읽어 스냅샷을 뜬다(OrderItem).
 * 클라이언트가 금액을 불러주면 그대로 믿게 되고, 장부·대사의 기준값이 외부 입력이 된다.
 *
 * @param items 상품별 수량. 같은 productId 가 두 번 오면 뒤엣것이 이긴다(OrderService 에서 Map 으로 접힘).
 */
public record PlaceOrderRequest(
        @NotEmpty @Valid List<Item> items
) {

    public record Item(
            @NotNull Long productId,
            @NotNull @Min(1) Integer quantity
    ) {
    }
}
