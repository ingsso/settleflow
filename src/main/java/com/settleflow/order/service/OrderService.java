package com.settleflow.order.service;

import com.settleflow.order.domain.Order;
import com.settleflow.order.repository.OrderRepository;
import com.settleflow.payment.service.PaymentService;
import com.settleflow.product.domain.Product;
import com.settleflow.product.repository.ProductRepository;
import com.settleflow.product.service.InventoryService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 주문의 유일한 진입점 — 주문 생성 + 재고 차감 + 결제를 한 흐름으로 묶는다(W1D5).
 *
 * <pre>
 *   tx1: 주문 생성 + 재고 차감 + 커밋
 *   ----- 트랜잭션 경계 -----
 *   결제 (PaymentService.pay — 그 안에서 다시 PENDING 선커밋 → PG 호출 → 결과 반영)
 * </pre>
 *
 * <p><b>이 메서드에 {@code @Transactional} 을 붙이면 안 된다.</b> 붙이면 {@code pay()} 안의
 * {@code TransactionTemplate}(전파 REQUIRED)이 새 트랜잭션을 여는 대신 바깥 것에 껴든다.
 * 그러면 PENDING 행이 커밋되지 않은 채로 PG 를 부르게 되고, PG 호출이 터지면 통째로 롤백되어
 * PENDING 이 사라진다 — 나중에 도착하는 웹훅이 매칭할 행이 없어진다(W1D1 §2 함정1).
 *
 * <p>tx1 을 {@code @Transactional} 메서드로 빼서 자기가 부르는 것도 안 된다. Spring 은 프록시로
 * 트랜잭션을 거는데 자기호출은 프록시를 안 거친다. {@code PaymentService} 와 같은 이유로
 * {@code TransactionTemplate} 을 직접 쓴다(W1D4 §5).
 *
 * <p><b>결제가 거절돼도 재고를 되돌리지 않는다.</b> 주문은 CREATED 로 남고 재결제가 가능한데
 * (Order↔Payment 가 1:N 인 이유), 여기서 재고를 복원해버리면 재결제 승인 시 재고를 안 깎은 채로
 * 주문만 PAID 가 된다 — 이중판매다. 복원은 미결제 쓸이 배치가 PG 에 조회해 확인한 뒤에 한다
 * (W1D3 §2: 재고 복원은 시간의 문제가 아니라 사실 확인의 문제).
 */
@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final InventoryService inventoryService;
    private final PaymentService paymentService;
    private final TransactionTemplate txTemplate;

    public OrderService(OrderRepository orderRepository,
                        ProductRepository productRepository,
                        InventoryService inventoryService,
                        PaymentService paymentService,
                        PlatformTransactionManager txManager) {
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
        this.inventoryService = inventoryService;
        this.paymentService = paymentService;
        this.txTemplate = new TransactionTemplate(txManager);
    }

    /**
     * 주문하고 곧바로 결제한다.
     *
     * @param quantities productId → 수량
     * @return 주문 id 와 merchant_payment_id — 호출부가 이후 웹훅·조회에 쓴다
     * @throws com.settleflow.product.service.OutOfStockException 재고 부족 시. tx1 전체가 롤백되어
     *         주문도 남지 않는다(앞서 깎은 다른 상품 재고도 되돌아간다).
     */
    public PlacedOrder place(Map<Long, Integer> quantities) {
        if (quantities.isEmpty()) {
            throw new IllegalArgumentException("빈 주문");
        }

        // --- tx1: 주문 생성 + 재고 차감을 한 트랜잭션으로. 재고가 모자라면 주문도 함께 롤백된다 ---
        Long orderId = txTemplate.execute(tx -> {
            // productId 오름차순 — 락 획득 순서를 InventoryService 와 통일한다(W1D3 §5 데드락 방지).
            Map<Long, Integer> ordered = new TreeMap<>(quantities);

            Order order = Order.create();
            for (Map.Entry<Long, Integer> e : ordered.entrySet()) {
                Product product = productRepository.findById(e.getKey())
                        .orElseThrow(() -> new IllegalArgumentException("no product " + e.getKey()));
                order.addItem(product, e.getValue());   // 스냅샷 + 총액 누적
            }
            orderRepository.save(order);

            inventoryService.decrease(ordered);   // 전파 REQUIRED — tx1 에 껴든다(같은 트랜잭션)
            return order.getId();
        });

        // --- 트랜잭션 밖에서 결제. pay() 가 자기 안에서 PENDING 선커밋 → PG 호출 → 결과 반영 ---
        UUID merchantPaymentId = paymentService.pay(orderId);

        return new PlacedOrder(orderId, merchantPaymentId);
    }

    public record PlacedOrder(Long orderId, UUID merchantPaymentId) {
    }
}
