package com.settleflow.order;

import com.settleflow.order.repository.OrderRepository;
import com.settleflow.order.service.OrderService;
import com.settleflow.payment.domain.Payment;
import com.settleflow.payment.domain.PaymentStatus;
import com.settleflow.payment.repository.PaymentRepository;
import com.settleflow.product.domain.Product;
import com.settleflow.product.repository.ProductRepository;
import com.settleflow.product.service.OutOfStockException;
import com.settleflow.seller.domain.Seller;
import com.settleflow.support.IntegrationTestSupport;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * W1D5 배선 실측 — 주문→재고차감→결제가 한 진입점으로 관통하는가.
 *
 * <p>{@code PaymentFlowTest} 는 재고를 타지 않는다(주문을 직접 만들어 넣는다). 여기서만
 * {@code OrderService.place} 를 통해 <b>재고 차감이 실제로 흐름 안에 있는지</b>를 본다.
 */
class OrderPlacementTest extends IntegrationTestSupport {

    @Autowired OrderService orderService;
    @Autowired OrderRepository orderRepository;
    @Autowired PaymentRepository paymentRepository;
    @Autowired ProductRepository productRepository;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager txManager;

    TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        // DB 초기화는 IntegrationTestSupport.cleanDatabase() 가 먼저 처리한다.
    }

    // ---------- 1. 관통 ----------

    @Test
    void 주문하면_재고가_깎이고_결제까지_PAID로_관통한다() {
        long productId = createProduct(10_000L, 5);      // %1000 != 111,222 → 승인

        OrderService.PlacedOrder placed = orderService.place(Map.of(productId, 2));

        Payment p = reload(placed);
        System.out.printf("[실측] 주문 관통 → order=%s payment=%s 재고 5-2=%d 총액=%d%n",
                orderStatus(placed.orderId()), p.getStatus(), stock(productId), totalAmount(placed.orderId()));

        assertThat(orderStatus(placed.orderId())).isEqualTo("PAID");
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(stock(productId)).isEqualTo(3);                  // ← 재고가 흐름 안에 있다
        assertThat(totalAmount(placed.orderId())).isEqualTo(20_000L);
    }

    // ---------- 2. 재고 부족이면 주문도 안 남는다 (tx1 경계) ----------

    @Test
    void 재고가_모자라면_주문도_결제도_남지_않는다() {
        long productId = createProduct(10_000L, 1);

        assertThatThrownBy(() -> orderService.place(Map.of(productId, 2)))
                .isInstanceOf(OutOfStockException.class);

        System.out.printf("[실측] 재고부족 거부 → 주문 %d건, 결제 %d건, 재고=%d%n",
                orderRepository.count(), paymentRepository.count(), stock(productId));

        assertThat(orderRepository.count()).isZero();      // tx1 롤백 — 주문이 안 남는다
        assertThat(paymentRepository.count()).isZero();    // PG 를 부르지도 않았다
        assertThat(stock(productId)).isEqualTo(1);         // 재고 그대로
    }

    // ---------- 3. 거절돼도 재고는 안 돌아온다 (설계) ----------

    /**
     * 매직 금액은 <b>주문 총액</b>에 걸린다(payment.amount = order.totalAmount) — 단가가 아니다.
     * 그래서 수량까지 곱한 값이 111 로 끝나야 거절이 난다. 수량 2 로는 불가능하다(짝수라 111 이 안 나옴).
     */
    @Test
    void 결제가_거절돼도_재고는_복원되지_않고_주문은_CREATED로_남는다() {
        long productId = createProduct(10_111L, 5);      // 총액 10_111 → %1000==111 → 거절

        OrderService.PlacedOrder placed = orderService.place(Map.of(productId, 1));

        Payment p = reload(placed);
        System.out.printf("[실측] 거절 → order=%s payment=%s 재고=%d%n",
                orderStatus(placed.orderId()), p.getStatus(), stock(productId));

        assertThat(p.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(orderStatus(placed.orderId())).isEqualTo("CREATED");   // 재결제 가능한 상태로 남는다
        assertThat(stock(productId)).isEqualTo(4);                        // 복원 안 함 — 쓸이 배치의 몫
    }

    // ====================================================================
    // 음성대조군 D (수동) — 본인이 직접 돌려볼 것.
    //   시나리오 2의 "재고 부족이면 주문도 안 남는다"가 진짜 tx1 덕인지 확인한다.
    //   방법: OrderService.place 에서 inventoryService.decrease(ordered) 를 txTemplate.execute 블록
    //         '밖'으로 (return 뒤, pay 앞으로) 옮기고 시나리오 2를 재실행.
    //   기대: 주문 1건이 남아 빨간불 = 주문과 재고가 다른 트랜잭션이라 롤백이 같이 안 일어난 것.
    //   확인했으면 반드시 원복.
    // ====================================================================

    // ---------- helpers ----------

    long createProduct(long price, int stock) {
        return tx.execute(s -> {
            Seller seller = new Seller("셀러");
            em.persist(seller);
            Product product = new Product(seller, "상품", price, stock);
            em.persist(product);
            em.flush();
            return product.getId();
        });
    }

    Payment reload(OrderService.PlacedOrder placed) {
        return tx.execute(s -> paymentRepository.findByMerchantPaymentId(placed.merchantPaymentId()).orElseThrow());
    }

    int stock(long productId) {
        return tx.execute(s -> productRepository.findById(productId).orElseThrow().getStock());
    }

    String orderStatus(long orderId) {
        return tx.execute(s -> orderRepository.findById(orderId).orElseThrow().getStatus().name());
    }

    long totalAmount(long orderId) {
        return tx.execute(s -> orderRepository.findById(orderId).orElseThrow().getTotalAmount());
    }
}
