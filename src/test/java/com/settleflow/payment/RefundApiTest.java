package com.settleflow.payment;

import com.settleflow.order.domain.Order;
import com.settleflow.order.service.OrderService;
import com.settleflow.payment.domain.Payment;
import com.settleflow.payment.domain.PaymentStatus;
import com.settleflow.payment.repository.PaymentRepository;
import com.settleflow.payment.service.PaymentTransitionService;
import com.settleflow.product.domain.Product;
import com.settleflow.seller.domain.Seller;
import com.settleflow.support.IntegrationTestSupport;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * W2 점검(A) 실측 — 환불 엔드포인트 {@code POST /payments/{id}/refunds} 의 HTTP 매핑.
 *
 * <p>Week 2 산출물(가드·역분개)이 여기 생기기 전까지 앱 밖에서 도달 불가능했다 — 프로덕션 코드가
 * {@code RefundService.requestRefund} 를 부르지 않았다. 이 테스트가 실제 HTTP 로 그 입구를 두드려
 * {@link com.settleflow.payment.domain.RefundOutcome} → 상태코드 매핑을 밟는다.
 *
 * <pre>
 *   Accepted                    201  { refundId, amount, refundStatus }
 *   Rejected(NOT_REFUNDABLE)    409
 *   Rejected(EXCEEDS_REMAINING) 409
 *   Rejected(PAYMENT_UNKNOWN)   503 + Retry-After
 *   @Positive(0/음수)           400  (서비스 도달 전 Bean Validation)
 *   없는 결제 id                 404
 * </pre>
 */
class RefundApiTest extends IntegrationTestSupport {

    @Autowired OrderService orderService;
    @Autowired PaymentTransitionService transitions;
    @Autowired PaymentRepository paymentRepository;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager txManager;

    @LocalServerPort int port;
    final HttpClient http = HttpClient.newHttpClient();

    @Test
    void 정상_부분환불은_201과_refund요약을_돌려준다() {
        Long paymentId = paidOrder();   // 20,000

        HttpResponse<String> res = postRefund(paymentId, "{\"amount\":5000}");

        System.out.printf("[실측] 5,000 부분환불 → HTTP%d body=%s%n", res.statusCode(), res.body());

        assertThat(res.statusCode()).isEqualTo(201);
        assertThat(res.body()).contains("\"amount\":5000");
        assertThat(res.body()).contains("\"refundStatus\":\"PARTIAL_REFUNDED\"");
        assertThat(res.body()).contains("\"refundId\":");
    }

    @Test
    void PENDING_결제_환불은_409_NOT_REFUNDABLE() {
        Long orderId = paidOrderId();
        Long pendingId = extraPayment(orderId, 20_000L, PaymentStatus.PENDING);

        HttpResponse<String> res = postRefund(pendingId, "{\"amount\":5000}");

        System.out.printf("[실측] PENDING 환불 → HTTP%d body=%s%n", res.statusCode(), res.body());

        assertThat(res.statusCode()).isEqualTo(409);
        assertThat(res.body()).contains("NOT_REFUNDABLE");
    }

    @Test
    void 잔액초과_환불은_409_EXCEEDS_REMAINING() {
        Long paymentId = paidOrder();   // 20,000

        HttpResponse<String> res = postRefund(paymentId, "{\"amount\":20001}");

        System.out.printf("[실측] 잔액초과 환불 → HTTP%d body=%s%n", res.statusCode(), res.body());

        assertThat(res.statusCode()).isEqualTo(409);
        assertThat(res.body()).contains("EXCEEDS_REMAINING");
    }

    @Test
    void UNKNOWN_결제_환불은_503과_RetryAfter() {
        Long orderId = paidOrderId();
        Long unknownId = extraPayment(orderId, 20_000L, PaymentStatus.UNKNOWN);

        HttpResponse<String> res = postRefund(unknownId, "{\"amount\":5000}");

        System.out.printf("[실측] UNKNOWN 환불 → HTTP%d Retry-After=%s body=%s%n",
                res.statusCode(), res.headers().firstValue("Retry-After").orElse("(없음)"), res.body());

        assertThat(res.statusCode()).isEqualTo(503);
        assertThat(res.headers().firstValue("Retry-After")).isPresent();   // "잠시 후 다시"
        assertThat(res.body()).contains("PAYMENT_UNKNOWN");
    }

    @Test
    void 영원_이하_금액은_Bean_Validation이_400으로_끊는다() {
        Long paymentId = paidOrder();

        HttpResponse<String> zero = postRefund(paymentId, "{\"amount\":0}");
        HttpResponse<String> negative = postRefund(paymentId, "{\"amount\":-5000}");

        System.out.printf("[실측] 0원 → HTTP%d / 음수 → HTTP%d%n", zero.statusCode(), negative.statusCode());

        assertThat(zero.statusCode()).isEqualTo(400);
        assertThat(negative.statusCode()).isEqualTo(400);
        // @Positive 가 서비스 도달 전에 끊었으므로 환불은 반영되지 않는다
        assertThat(refundRows()).isZero();
    }

    @Test
    void 없는_결제_id는_404() {
        HttpResponse<String> res = postRefund(999_999L, "{\"amount\":5000}");

        System.out.printf("[실측] 없는 결제 환불 → HTTP%d body=%s%n", res.statusCode(), res.body());

        assertThat(res.statusCode()).isEqualTo(404);
        assertThat(res.body()).contains("PAYMENT_NOT_FOUND");
    }

    // ---------- helpers ----------

    HttpResponse<String> postRefund(Long paymentId, String json) {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/payments/" + paymentId + "/refunds"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        try {
            return http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    TransactionTemplate tx() {
        return new TransactionTemplate(txManager);
    }

    /** 상품 1개(10,000)×2 를 주문 → 20,000 PAID 결제 하나. paymentId 반환. */
    Long paidOrder() {
        long productId = tx().execute(s -> {
            Seller seller = new Seller("셀러A", 500);
            em.persist(seller);
            Product product = new Product(seller, "상품", 10_000L, 5);
            em.persist(product);
            em.flush();
            return product.getId();
        });
        OrderService.PlacedOrder placed = orderService.place(Map.of(productId, 2));
        return paymentRepository.findByMerchantPaymentId(placed.merchantPaymentId())
                .orElseThrow().getId();
    }

    Long paidOrderId() {
        long productId = tx().execute(s -> {
            Seller seller = new Seller("셀러A", 500);
            em.persist(seller);
            Product product = new Product(seller, "상품", 10_000L, 5);
            em.persist(product);
            em.flush();
            return product.getId();
        });
        return orderService.place(Map.of(productId, 2)).orderId();
    }

    /** 같은 주문에 PAID 아닌 결제를 하나 더 만든다(RefundGuardTest 와 같은 방식). */
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

    long refundRows() {
        return ((Number) em.createNativeQuery("SELECT count(*) FROM refund")
                .getSingleResult()).longValue();
    }
}
