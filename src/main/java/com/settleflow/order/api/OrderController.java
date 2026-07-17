package com.settleflow.order.api;

import com.settleflow.order.domain.Order;
import com.settleflow.order.repository.OrderRepository;
import com.settleflow.order.service.OrderService;
import com.settleflow.product.service.OutOfStockException;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 주문 입구(W1D5). 사람이 눌러서 주문→재고차감→결제→웹훅 흐름을 태우는 자리.
 *
 * <p>웹훅과 달리 <b>항상 200 을 주지 않는다.</b> 웹훅의 200 은 PG 재전송을 멈추려는 것이고
 * (W1D1 §4), 여기 호출자는 사람이다 — 재고가 없으면 없다고 알려줘야 한다.
 */
@RestController
@RequestMapping("/orders")
public class OrderController {

    private final OrderService orderService;
    private final OrderRepository orderRepository;

    public OrderController(OrderService orderService, OrderRepository orderRepository) {
        this.orderService = orderService;
        this.orderRepository = orderRepository;
    }

    @PostMapping
    public ResponseEntity<PlaceOrderResponse> place(@Valid @RequestBody PlaceOrderRequest req) {
        Map<Long, Integer> quantities = new LinkedHashMap<>();
        for (PlaceOrderRequest.Item item : req.items()) {
            quantities.put(item.productId(), item.quantity());
        }

        OrderService.PlacedOrder placed = orderService.place(quantities);

        // 결제까지 끝난 뒤의 주문 상태를 실어준다 — 승인이면 PAID, 거절·타임아웃이면 CREATED.
        Order order = orderRepository.findById(placed.orderId()).orElseThrow();
        return ResponseEntity.status(HttpStatus.CREATED).body(new PlaceOrderResponse(
                placed.orderId(),
                placed.merchantPaymentId().toString(),
                order.getStatus().name(),
                order.getTotalAmount()
        ));
    }

    /** 재고 부족은 서버 잘못이 아니라 요청이 지금 성립하지 않는 것 → 409. */
    @ExceptionHandler(OutOfStockException.class)
    public ResponseEntity<Map<String, Object>> outOfStock(OutOfStockException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "OUT_OF_STOCK",
                        "productId", e.getProductId(),
                        "message", e.getMessage()));
    }

    public record PlaceOrderResponse(Long orderId, String merchantPaymentId,
                                     String orderStatus, Long totalAmount) {
    }
}
