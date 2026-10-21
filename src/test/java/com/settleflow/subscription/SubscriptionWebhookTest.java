package com.settleflow.subscription;

import com.settleflow.order.service.OrderService;
import com.settleflow.product.domain.Product;
import com.settleflow.seller.domain.Seller;
import com.settleflow.subscription.domain.Subscription;
import com.settleflow.subscription.service.DunningScheduler;
import com.settleflow.support.IntegrationTestSupport;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * W5D3 — 구독 청구에 PG 웹훅이 도착하면 무슨 일이 나는가.
 *
 * <p>W1D3 설계에서 크래시·타임아웃을 회수하는 경로는 <b>웹훅</b>이다. 구독 청구도 같은
 * {@code payment} 행을 쓰므로 같은 웹훅이 온다 — 그런데
 * {@code WebhookController.dispatch} 는 형제 진입점({@code applySubscriptionApproved})을
 * 모르고 언제나 {@code applyApproved} 를 부른다.
 */
class SubscriptionWebhookTest extends IntegrationTestSupport {

    @Autowired DunningScheduler scheduler;
    @Autowired OrderService orderService;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager txManager;
    @Autowired StringRedisTemplate redis;

    private static final long AMOUNT_TIMEOUT = 30_222L;

    /**
     * ★ W5D2 까지 여기서 HTTP 500 + NPE 가 났다. 고치기 전 [실측]:
     *
     * <pre>
     *   NullPointerException: Cannot invoke "Order.getItems()"
     *     because "Payment.getOrder()" is null
     *     at LedgerService.sharesOf(LedgerService.java:186)
     *     at LedgerService.recordPayment(LedgerService.java:44)
     *   멱등키 = null (지워짐) → PG 재전송 → 영구 결함이라 매번 다시 500
     * </pre>
     *
     * <p>타임아웃으로 {@code UNKNOWN} 이 된 시도를 웹훅이 회수하는 경로이기도 하다 —
     * W1D3 설계가 크래시·무응답에 대해 기대하던 그 회복이 구독에서만 끊겨 있었다.
     */
    @Test
    void 구독청구_승인_웹훅이_주기까지_확정한다() {
        LocalDate today = LocalDate.of(2026, 8, 15);
        createSubscription(AMOUNT_TIMEOUT, today);
        scheduler.runBillingDue(today);   // 타임아웃 → payment UNKNOWN, 주기 PENDING

        String mpid = str("SELECT merchant_payment_id::text FROM payment");
        String eventId = "evt-" + UUID.randomUUID();

        HttpResponse<String> res = postWebhook(eventId, "APPROVED", mpid, "pg-tx-1", AMOUNT_TIMEOUT);

        String dedupKey = redis.opsForValue().get("webhook:dedup:" + eventId);
        String paymentStatus = str("SELECT status FROM payment");
        String cycleStatus = str("SELECT status FROM billing_cycle");
        long journals = num("SELECT count(*) FROM journal");
        long entries = num("SELECT count(*) FROM ledger_entry");
        long subRevenue = num("SELECT coalesce(sum(amount),0) FROM ledger_entry WHERE account = 'SUBSCRIPTION_REVENUE'");

        System.out.printf("[실측] 구독 승인 웹훅 → HTTP %d · 멱등키=%s · payment=%s · 주기=%s · journal=%d · 분개줄=%d%n",
                res.statusCode(), dedupKey, paymentStatus, cycleStatus, journals, entries);

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(dedupKey).isEqualTo("1");          // 지워지지 않았다 = PG 재전송 안 함
        assertThat(paymentStatus).isEqualTo("PAID");  // UNKNOWN 을 웹훅이 회수했다
        assertThat(cycleStatus).isEqualTo("PAID");
        assertThat(journals).isEqualTo(1);
        assertThat(entries).isEqualTo(2);             // 구독 분개는 안분 없이 2줄
        assertThat(subRevenue).isEqualTo(AMOUNT_TIMEOUT);
    }

    /**
     * ★ 거절 웹훅은 대칭이 아니다 — 주기의 재시도 시각까지 정해야 한다.
     *
     * <h3>이 테스트가 잠그는 off-by-one</h3>
     * 이 시나리오의 시도는 <b>이미 {@code UNKNOWN}</b> 이라 "결말난 시도"로 세어지는 쪽에 있다.
     * 결말 처리가 <i>이번 시도를 빼고</i> 세지 않으면 1 을 두 번 세어 {@code concluded=2} 가 되고,
     * 사다리가 한 칸 건너뛰어 <b>3일 뒤</b>가 예약된다. 24시간을 단언해 그걸 잠근다.
     */
    @Test
    void 구독청구_거절_웹훅이_주기를_FAILED_로_돌리고_하루_뒤를_예약한다() {
        LocalDate today = LocalDate.of(2026, 8, 15);
        createSubscription(AMOUNT_TIMEOUT, today);
        scheduler.runBillingDue(today);   // 타임아웃 → payment UNKNOWN, 주기 PENDING

        String mpid = str("SELECT merchant_payment_id::text FROM payment");

        HttpResponse<String> res = postWebhook("evt-" + UUID.randomUUID(), "DECLINED",
                mpid, "pg-tx-2", AMOUNT_TIMEOUT);

        String paymentStatus = str("SELECT status FROM payment");
        String cycleStatus = str("SELECT status FROM billing_cycle");
        long hoursAhead = num("SELECT EXTRACT(EPOCH FROM (next_retry_at - now()))::bigint / 3600 FROM billing_cycle");
        long entries = num("SELECT count(*) FROM ledger_entry");

        System.out.printf("[실측] 구독 거절 웹훅 → HTTP %d · payment=%s · 주기=%s · 다음재시도=%d시간 뒤 · 분개줄=%d%n",
                res.statusCode(), paymentStatus, cycleStatus, hoursAhead, entries);

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(paymentStatus).isEqualTo("FAILED");
        assertThat(cycleStatus).isEqualTo("FAILED");
        assertThat(hoursAhead).isBetween(23L, 24L);   // 1일 뒤 — 3일이면 이번 시도를 두 번 센 것
        assertThat(entries).isZero();
    }

    /**
     * 주문 결제 웹훅이 그대로인지 — 라우팅을 넣으면서 원래 경로가 안 바뀌었나.
     */
    @Test
    void 주문결제_승인_웹훅은_그대로_3줄_분개를_만든다() {
        long productId = createProduct();
        var placed = orderService.place(java.util.Map.of(productId, 2));   // 이미 PAID + journal 1

        long journalsBefore = num("SELECT count(*) FROM journal");
        HttpResponse<String> res = postWebhook("evt-" + UUID.randomUUID(), "APPROVED",
                placed.merchantPaymentId().toString(), "pg-tx-3", 20_000L);

        long journals = num("SELECT count(*) FROM journal");
        long entries = num("SELECT count(*) FROM ledger_entry");

        System.out.printf("[실측] 주문 승인 웹훅(중복) → HTTP %d · journal %d→%d · 분개줄=%d%n",
                res.statusCode(), journalsBefore, journals, entries);

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(journals).isEqualTo(1);   // 이미 PAID 라 IGNORED — 부수효과 없음
        assertThat(entries).isEqualTo(3);    // 2N+1, N=1
    }

    // ---------- helpers ----------

    private void createSubscription(long amount, LocalDate firstBillingDate) {
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            Seller seller = new Seller("구독셀러", 500);
            em.persist(seller);
            em.persist(new Subscription(seller, amount, 30, firstBillingDate));
            em.flush();
        });
    }

    private HttpResponse<String> postWebhook(String eventId, String type, String mpid,
                                             String pgTx, long amount) {
        String json = String.format(
                "{\"eventId\":\"%s\",\"eventType\":\"%s\",\"merchantPaymentId\":\"%s\","
                        + "\"pgTransactionId\":\"%s\",\"amount\":%d}",
                eventId, type, mpid, pgTx, amount);
        return postSignedWebhook(json);
    }

    private long createProduct() {
        return new TransactionTemplate(txManager).execute(s -> {
            Seller seller = new Seller("판매셀러", 500);
            em.persist(seller);
            Product product = new Product(seller, "상품", 10_000L, 5);
            em.persist(product);
            em.flush();
            return product.getId();
        });
    }

    private String str(String sql) {
        Object v = em.createNativeQuery(sql).getSingleResult();
        return v == null ? null : v.toString();
    }

    private long num(String sql) {
        Object v = em.createNativeQuery(sql).getSingleResult();
        return v == null ? 0 : ((Number) v).longValue();
    }
}
