package com.settleflow.payment;

import com.settleflow.order.domain.Order;
import com.settleflow.order.service.OrderService;
import com.settleflow.payment.domain.Payment;
import com.settleflow.payment.domain.PaymentStatus;
import com.settleflow.payment.domain.RefundResult;
import com.settleflow.payment.domain.RefundStatus;
import com.settleflow.payment.repository.PaymentRepository;
import com.settleflow.payment.service.PaymentTransitionService;
import com.settleflow.payment.service.RefundService;
import com.settleflow.product.domain.Product;
import com.settleflow.seller.domain.Seller;
import com.settleflow.support.IntegrationTestSupport;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * W2D4 실측 — 환불 요청 가드와 파생 라벨.
 *
 * <p>확인하는 것 두 축:
 * <ol>
 *   <li><b>가드</b> — 자격 없는 결제·잘못된 금액의 환불이 거부되고, 거부 시 <b>부작용이 0</b>인가
 *       (금액도 안 늘고 refund 행도 안 생기는가)</li>
 *   <li><b>파생 라벨</b> — 저장하지 않는 {@link RefundStatus} 가 {@code refunded_amount} 만으로
 *       NOT_REFUNDED → PARTIAL_REFUNDED → REFUNDED 를 정확히 따라가는가</li>
 * </ol>
 *
 * <p>거부 사유를 뭉치지 않고 {@code NOT_REFUNDABLE} 과 {@code PAYMENT_UNKNOWN} 을 구분해 검증한다 —
 * 둘은 재시도 가능성이 정반대라 호출부가 다르게 움직여야 한다(W1D1 §3).
 */
class RefundGuardTest extends IntegrationTestSupport {

    @Autowired OrderService orderService;
    @Autowired PaymentTransitionService transitions;
    @Autowired PaymentRepository paymentRepository;
    @Autowired RefundService refundService;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager txManager;

    TransactionTemplate tx() {
        return new TransactionTemplate(txManager);
    }

    // ---------- 1. 자격 가드 — PENDING / FAILED / UNKNOWN ----------

    @Test
    void PENDING_결제는_환불되지_않고_부작용도_없다() {
        Fixture f = paidOrder();
        Long pendingId = extraPayment(f.orderId(), 20_000L, PaymentStatus.PENDING);

        RefundResult result = refundService.requestRefund(pendingId, 5_000L);

        System.out.printf("[실측] PENDING 환불요청 → %s | refunded=%d refund행=%d%n",
                result, refundedAmount(pendingId), refundRows());

        assertThat(result).isEqualTo(RefundResult.NOT_REFUNDABLE);
        assertThat(refundedAmount(pendingId)).isZero();
        assertThat(refundRows()).isZero();
    }

    @Test
    void FAILED_결제는_환불되지_않는다() {
        Fixture f = paidOrder();
        Long failedId = extraPayment(f.orderId(), 20_000L, PaymentStatus.FAILED);

        RefundResult result = refundService.requestRefund(failedId, 5_000L);

        System.out.printf("[실측] FAILED 환불요청 → %s | refunded=%d refund행=%d%n",
                result, refundedAmount(failedId), refundRows());

        assertThat(result).isEqualTo(RefundResult.NOT_REFUNDABLE);
        assertThat(refundRows()).isZero();
    }

    @Test
    void UNKNOWN_결제는_NOT_REFUNDABLE_이_아니라_PAYMENT_UNKNOWN_으로_구분된다() {
        Fixture f = paidOrder();
        Long unknownId = extraPayment(f.orderId(), 20_000L, PaymentStatus.UNKNOWN);

        RefundResult result = refundService.requestRefund(unknownId, 5_000L);

        System.out.printf("[실측] UNKNOWN 환불요청 → %s (FAILED 와 다른 값이어야 함) | refund행=%d%n",
                result, refundRows());

        // 실패가 아니라 정보의 부재(W1D1 §3) — 조회 후 재시도 여지가 있어 사유를 나눈다
        assertThat(result).isEqualTo(RefundResult.PAYMENT_UNKNOWN);
        assertThat(result).isNotEqualTo(RefundResult.NOT_REFUNDABLE);
        assertThat(refundRows()).isZero();
    }

    // ---------- 2. 금액 가드 ----------

    @Test
    void 영원_이하_환불은_거부된다() {
        Fixture f = paidOrder();

        RefundResult zero = refundService.requestRefund(f.paymentId(), 0L);
        RefundResult negative = refundService.requestRefund(f.paymentId(), -5_000L);

        System.out.printf("[실측] 0원 → %s / 음수 → %s | refunded=%d refund행=%d%n",
                zero, negative, refundedAmount(f.paymentId()), refundRows());

        assertThat(zero).isEqualTo(RefundResult.INVALID_AMOUNT);
        assertThat(negative).isEqualTo(RefundResult.INVALID_AMOUNT);
        assertThat(refundedAmount(f.paymentId())).isZero();
        assertThat(refundRows()).isZero();
    }

    @Test
    void 잔액을_넘는_환불은_거부되고_금액도_안_늘어난다() {
        Fixture f = paidOrder();   // 20,000

        RefundResult first = refundService.requestRefund(f.paymentId(), 15_000L);
        RefundResult over = refundService.requestRefund(f.paymentId(), 6_000L);   // 잔액 5,000

        System.out.printf("[실측] 15,000 → %s / 잔액5,000 에 6,000 → %s | refunded=%d refund행=%d%n",
                first, over, refundedAmount(f.paymentId()), refundRows());

        assertThat(first).isEqualTo(RefundResult.ACCEPTED);
        assertThat(over).isEqualTo(RefundResult.EXCEEDS_REMAINING);
        assertThat(refundedAmount(f.paymentId())).isEqualTo(15_000L);   // 거부는 부작용 0
        assertThat(refundRows()).isEqualTo(1);
    }

    @Test
    void 전액환불된_결제의_추가환불은_별도_가드_없이_잔액조건에_막힌다() {
        Fixture f = paidOrder();   // 20,000

        RefundResult full = refundService.requestRefund(f.paymentId(), 20_000L);
        RefundResult more = refundService.requestRefund(f.paymentId(), 1L);

        System.out.printf("[실측] 전액환불 → %s / 그 뒤 1원 → %s | 라벨=%s%n",
                full, more, refundStatusOf(f.paymentId()));

        assertThat(full).isEqualTo(RefundResult.ACCEPTED);
        // "REFUNDED 에서 추가환불 금지"를 위한 상태 가드가 따로 없다 —
        // refunded_amount == amount 면 (+x <= amount) 가 어떤 양수 x 에도 거짓이라 자동으로 막힌다.
        assertThat(more).isEqualTo(RefundResult.EXCEEDS_REMAINING);
        assertThat(refundedAmount(f.paymentId())).isEqualTo(20_000L);
        assertThat(refundRows()).isEqualTo(1);
    }

    // ---------- 3. 파생 라벨 — 저장 안 하고 금액만으로 3단계를 따라가는가 ----------

    @Test
    void 부분환불_3번이면_라벨이_NOT_REFUNDED에서_PARTIAL을_거쳐_REFUNDED로_간다() {
        Fixture f = paidOrder();   // 20,000
        Long id = f.paymentId();

        RefundStatus s0 = refundStatusOf(id);
        refundService.requestRefund(id, 8_000L);
        RefundStatus s1 = refundStatusOf(id);
        refundService.requestRefund(id, 7_000L);
        RefundStatus s2 = refundStatusOf(id);
        refundService.requestRefund(id, 5_000L);
        RefundStatus s3 = refundStatusOf(id);

        System.out.printf("[실측] 라벨 추이 %s → %s → %s → %s | refunded=%d refund행=%d%n",
                s0, s1, s2, s3, refundedAmount(id), refundRows());

        assertThat(s0).isEqualTo(RefundStatus.NOT_REFUNDED);
        assertThat(s1).isEqualTo(RefundStatus.PARTIAL_REFUNDED);
        assertThat(s2).isEqualTo(RefundStatus.PARTIAL_REFUNDED);
        assertThat(s3).isEqualTo(RefundStatus.REFUNDED);
        assertThat(refundedAmount(id)).isEqualTo(20_000L);
        assertThat(refundRows()).isEqualTo(3);   // 같은 결제에 환불 3건 — 중복이 아니라 진짜 3건
    }

    @Test
    void 같은_금액_환불_두번은_중복이_아니라_환불_2건이다() {
        Fixture f = paidOrder();   // 20,000

        RefundResult first = refundService.requestRefund(f.paymentId(), 5_000L);
        RefundResult second = refundService.requestRefund(f.paymentId(), 5_000L);

        System.out.printf("[실측] 5,000 두 번 → %s, %s | refunded=%d refund행=%d%n",
                first, second, refundedAmount(f.paymentId()), refundRows());

        // 결제 승인이었다면 IGNORED 였을 자리 — 환불엔 그 대칭이 없다(RefundResult javadoc)
        assertThat(first).isEqualTo(RefundResult.ACCEPTED);
        assertThat(second).isEqualTo(RefundResult.ACCEPTED);
        assertThat(refundedAmount(f.paymentId())).isEqualTo(10_000L);
        assertThat(refundRows()).isEqualTo(2);
    }

    @Test
    void 파생함수는_PAID_밖에서_거짓말하지_않고_예외를_던진다() {
        Fixture f = paidOrder();
        Long pendingId = extraPayment(f.orderId(), 20_000L, PaymentStatus.PENDING);

        // refunded_amount == 0 이라 그냥 계산하면 "NOT_REFUNDED"(환불 없음)라 답한다 — 거짓말이다.
        assertThatThrownBy(() -> refundStatusOf(pendingId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PAID");

        System.out.println("[실측] PENDING 에 refundStatus() → IllegalStateException (조용한 거짓말 대신 예외)");
    }

    // ---------- helpers ----------

    record Fixture(Long orderId, Long paymentId) {}

    /** 상품 1개(10,000)×2 를 주문 → 20,000 PAID 결제 하나. */
    Fixture paidOrder() {
        long productId = tx().execute(s -> {
            Seller seller = new Seller("셀러A", 500);
            em.persist(seller);
            Product product = new Product(seller, "상품", 10_000L, 5);
            em.persist(product);
            em.flush();
            return product.getId();
        });

        OrderService.PlacedOrder placed = orderService.place(Map.of(productId, 2));
        Long paymentId = paymentRepository.findByMerchantPaymentId(placed.merchantPaymentId())
                .orElseThrow().getId();
        return new Fixture(placed.orderId(), paymentId);
    }

    /**
     * 같은 주문에 PAID 아닌 결제를 하나 더 만든다.
     * {@code uq_payment_order_paid} 는 PAID 에만 걸린 부분 유니크라 PENDING 은 여럿 가능하다.
     * 상태 이동은 SQL 로 조작하지 않고 실제 전이({@code applyDeclined}/{@code applyTimedOut})로 만든다.
     */
    Long extraPayment(Long orderId, long amount, PaymentStatus target) {
        UUID[] mpid = new UUID[1];
        Long paymentId = tx().execute(s -> {
            Order order = em.find(Order.class, orderId);
            Payment payment = new Payment(order, amount);
            em.persist(payment);
            em.flush();
            mpid[0] = payment.getMerchantPaymentId();
            return payment.getId();
        });

        switch (target) {
            case FAILED -> transitions.applyDeclined(mpid[0], null);
            case UNKNOWN -> transitions.applyTimedOut(mpid[0]);
            case PENDING -> { /* 생성 직후가 PENDING */ }
            default -> throw new IllegalArgumentException("PAID 는 paidOrder() 로 만든다: " + target);
        }
        return paymentId;
    }

    RefundStatus refundStatusOf(Long paymentId) {
        return tx().execute(s -> em.find(Payment.class, paymentId).refundStatus());
    }

    long refundedAmount(Long paymentId) {
        return ((Number) em.createNativeQuery(
                        "SELECT refunded_amount FROM payment WHERE id = " + paymentId)
                .getSingleResult()).longValue();
    }

    long refundRows() {
        return ((Number) em.createNativeQuery("SELECT count(*) FROM refund")
                .getSingleResult()).longValue();
    }
}
