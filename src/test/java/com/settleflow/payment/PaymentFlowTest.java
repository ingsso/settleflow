package com.settleflow.payment;

import com.settleflow.order.domain.Order;
import com.settleflow.order.repository.OrderRepository;
import com.settleflow.payment.api.PgEventType;
import com.settleflow.payment.domain.Payment;
import com.settleflow.payment.domain.PaymentStatus;
import com.settleflow.payment.domain.TransitionResult;
import com.settleflow.payment.repository.PaymentRepository;
import com.settleflow.payment.service.PaymentService;
import com.settleflow.payment.service.PaymentTransitionService;
import com.settleflow.product.domain.Product;
import com.settleflow.seller.domain.Seller;
import com.settleflow.support.IntegrationTestSupport;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntUnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * W1D4 통합 실측 — 주문→결제→웹훅 전체 플로우와 웹훅 멱등성.
 *
 * <p><b>관측을 두 축으로</b> 나눈다(설계 결정):
 * <ul>
 *   <li>스파이 {@code applyApproved} 호출 수 = <b>fast-path 축</b>. SETNX 가 중복을 몇 개 쳐냈나.
 *   <li>{@code payment.version} = <b>correctness 축</b>. 실제 전이(부수효과)가 몇 번 일어났나.
 * </ul>
 * 음성대조군 A(eventId 만 다르게)가 이 둘을 분리해 보여준다 — SETNX 는 효율일 뿐 정합성이 아님.
 *
 * <p>웹훅은 실제 HTTP 엔드포인트로 쏜다(JDK HttpClient, 스레드 안전). 동기 스텁과 달리
 * WebhookController + Redis SETNX 를 진짜로 태워야 멱등이 검증된다.
 */
class PaymentFlowTest extends IntegrationTestSupport {

    @Autowired PaymentService paymentService;
    @Autowired PaymentRepository paymentRepository;
    @Autowired OrderRepository orderRepository;
    @Autowired StringRedisTemplate redis;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager txManager;

    @MockitoSpyBean PaymentTransitionService transitions;

    @LocalServerPort int port;

    final HttpClient http = HttpClient.newHttpClient();
    TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        tx.executeWithoutResult(s -> {
            em.createNativeQuery("DELETE FROM refund").executeUpdate();
            em.createNativeQuery("DELETE FROM payment").executeUpdate();
            em.createNativeQuery("DELETE FROM order_item").executeUpdate();
            em.createNativeQuery("DELETE FROM orders").executeUpdate();
            em.createNativeQuery("DELETE FROM product").executeUpdate();
            em.createNativeQuery("DELETE FROM seller").executeUpdate();
        });
        redis.execute((RedisCallback<Object>) c -> {
            c.serverCommands().flushDb();
            return null;
        });
    }

    // ---------- 1. 정상 승인 ----------

    @Test
    void 정상승인_pay는_PAID_뒤늦은_승인웹훅은_IGNORED() {
        long orderId = createOrder(10_000L);        // %1000 != 111,222 → 승인
        UUID mpid = paymentService.pay(orderId);

        Payment afterPay = reload(mpid);
        assertThat(afterPay.getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(orderStatus(orderId)).isEqualTo("PAID");
        assertThat(afterPay.getPgTransactionId()).isNull();   // 동기 경로엔 pgTx가 없다

        // 뒤늦은 승인 웹훅: 상태 전이는 IGNORED(PAID 유지)지만,
        // pg 거래번호는 이 웹훅이 처음 채운다 — "전이 IGNORED ≠ 아무 일도 안 함".
        int status = postWebhook("evt-1", PgEventType.APPROVED, mpid, "pg_tx_1", 10_000L);
        assertThat(status).isEqualTo(200);

        Payment afterWebhook = reload(mpid);
        assertThat(afterWebhook.getStatus()).isEqualTo(PaymentStatus.PAID);       // 안 뒤집힘
        assertThat(afterWebhook.getPgTransactionId()).isEqualTo("pg_tx_1");       // pgTx는 링크됨
    }

    // ---------- 2. 타임아웃 2단계 ----------

    @Test
    void 타임아웃은_UNKNOWN_그다음_웹훅승인이_PAID로() {
        long orderId = createOrder(10_222L);        // %1000==222 → 타임아웃
        UUID mpid = paymentService.pay(orderId);

        assertThat(reload(mpid).getStatus()).isEqualTo(PaymentStatus.UNKNOWN);

        int status = postWebhook("evt-2", PgEventType.APPROVED, mpid, "pg_tx_2", 10_222L);
        assertThat(status).isEqualTo(200);

        assertThat(reload(mpid).getStatus()).isEqualTo(PaymentStatus.PAID);
    }

    // ---------- 3. 같은 eventId 중복 동시 → 1건만 ----------

    @Test
    void 같은_eventId_중복웹훅_동시20개는_1건만_처리된다() throws Exception {
        long orderId = createOrder(10_000L);
        UUID mpid = createPendingPayment(orderId, 10_000L);

        int n = 20;
        int ok = blast(n, i -> postWebhook("evt-same", PgEventType.APPROVED, mpid, "pg_tx_3", 10_000L));

        Payment p = reload(mpid);
        System.out.printf("[실측] 같은 eventId %d개 동시 → HTTP200 %d개, 최종 status=%s version=%d%n",
                n, ok, p.getStatus(), p.getVersion());

        verify(transitions, times(1)).applyApproved(eq(mpid), any());   // SETNX 관문: 1건만 통과
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(p.getVersion()).isEqualTo(1L);                       // 실제 전이 1회
    }

    // ---------- 4. 음성대조군 A: eventId 다르면 SETNX가 못 막는다(그래도 정합) ----------

    @Test
    void 음성대조군A_eventId가_다르면_전이시도는_N회지만_version은_1이다() throws Exception {
        long orderId = createOrder(10_000L);
        UUID mpid = createPendingPayment(orderId, 10_000L);

        int n = 20;
        int ok = blast(n, i -> postWebhook("evt-" + i, PgEventType.APPROVED, mpid, "pg_tx_4", 10_000L));

        Payment p = reload(mpid);
        System.out.printf("[실측:음성대조군A] 다른 eventId %d개 동시 → HTTP200 %d개, 최종 status=%s version=%d%n",
                n, ok, p.getStatus(), p.getVersion());

        verify(transitions, times(n)).applyApproved(eq(mpid), any());   // 관문 못 막음: N건 다 통과
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(p.getVersion()).isEqualTo(1L);                       // 그래도 backbone이 1회로 수렴
    }

    // ====================================================================
    // 음성대조군 B (수동, "화재감지기에 연기") — 본인이 직접 돌려볼 것:
    //   backbone 을 잠깐 없애면 어떻게 되나.
    //   방법: Payment.onApproved() 의 `if (status==PAID) return IGNORED;` 가드를 잠깐 주석 처리
    //         (또는 Payment 의 @Version 을 잠깐 제거) 하고 시나리오 4를 다시 실행.
    //   기대: version 이 1이 아니라 N 근처로 튄다 = 이중 부수효과(정합성 붕괴).
    //   확인했으면 반드시 원복. "SETNX가 아니라 backbone이 정합성을 지킨다"의 증거.
    // ====================================================================

    @Test
    void 음성대조군B_동시_중복이어도_실제_전이는_1회() throws Exception {
        long orderId = createOrder(10_000L);
        UUID mpid = createPendingPayment(orderId, 10_000L);

        AtomicInteger transitioned = new AtomicInteger();
        doAnswer(inv -> {
            var r = (TransitionResult) inv.callRealMethod();
            if (r == TransitionResult.TRANSITIONED) transitioned.incrementAndGet();
            return r;
        }).when(transitions).applyApproved(any(), any());

        int n = 20;
        int ok = blast(n, i -> postWebhook("evt-" + i, PgEventType.APPROVED, mpid, "pg_tx_4", 10_000L));

        Payment p = reload(mpid);
        System.out.printf("[실측:음성대조군B] 다른 eventId %d개 동시 → HTTP200 %d개, 최종 status=%s version=%d%n",
                n, ok, p.getStatus(), p.getVersion());

        assertThat(transitioned.get()).isEqualTo(1);
    }

    // ---------- helpers ----------

    long createOrder(long unitPrice) {
        return tx.execute(s -> {
            Seller seller = new Seller("셀러");
            em.persist(seller);
            Product product = new Product(seller, "상품", unitPrice, 100);
            em.persist(product);
            Order order = Order.create();
            order.addItem(product, 1);
            em.persist(order);
            em.flush();
            return order.getId();
        });
    }

    UUID createPendingPayment(long orderId, long amount) {
        return tx.execute(s -> {
            Order order = em.find(Order.class, orderId);
            Payment p = new Payment(order, amount);
            em.persist(p);
            em.flush();
            return p.getMerchantPaymentId();
        });
    }

    /** 실제 HTTP 로 웹훅을 쏜다. 반환값은 HTTP 상태코드. */
    int postWebhook(String eventId, PgEventType type, UUID mpid, String pgTx, long amount) {
        String json = String.format(
                "{\"eventId\":\"%s\",\"eventType\":\"%s\",\"merchantPaymentId\":\"%s\","
                        + "\"pgTransactionId\":\"%s\",\"amount\":%d}",
                eventId, type, mpid, pgTx, amount);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/webhooks/pg"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        try {
            return http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    Payment reload(UUID mpid) {
        return tx.execute(s -> paymentRepository.findByMerchantPaymentId(mpid).orElseThrow());
    }

    String orderStatus(long orderId) {
        return tx.execute(s -> orderRepository.findById(orderId).orElseThrow().getStatus().name());
    }

    /** n개 스레드를 CyclicBarrier 로 동시 발사한다. call 은 상태코드를 돌려주고, 반환값은 200 개수. */
    int blast(int n, IntUnaryOperator call) throws Exception {
        CyclicBarrier gate = new CyclicBarrier(n);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        AtomicInteger ok = new AtomicInteger();
        try {
            List<Callable<Void>> tasks = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                final int idx = i;
                tasks.add(() -> {
                    gate.await();
                    if (call.applyAsInt(idx) == 200) ok.incrementAndGet();
                    return null;
                });
            }
            for (Future<Void> f : pool.invokeAll(tasks)) f.get();
        } finally {
            pool.shutdown();
        }
        return ok.get();
    }
}
