package com.settleflow.subscription;

import com.settleflow.seller.domain.Seller;
import com.settleflow.subscription.domain.Subscription;
import com.settleflow.subscription.service.DunningScheduler;
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
import java.time.LocalDate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * W5D5 — <b>구독 생성 경로</b>. D1 이 엔티티를, D2 가 스케줄러를 만들어놓고
 * <b>아무도 구독을 만들 수 없었다</b>(D3→D4→D5 네 번 이월). 그 미룸이 결국 k6 시나리오 ③
 * 실행을 막았고, 그래서 여기가 이번 주의 마지막 구멍이다.
 *
 * <h3>이 클래스가 겨누는 두 문장</h3>
 * <pre>
 *   ① 생성은 청구하지 않는다 — next_billing_date 를 오늘로 두고, 청구는 P1 폴러가 한다
 *   ② 셀러당 활성 구독은 하나다 — 앱이 거절하고, 경합은 부분 유니크 인덱스가 잡는다
 * </pre>
 *
 * <p>①이 설계의 알맹이다. 주문({@code POST /orders})은 생성과 동시에 PG 를 부르는데 구독이
 * 그걸 따라 하면 <b>청구 경로가 둘</b>이 되고, 그 두 번째 경로에는 리스가 없다 —
 * W5D2 가 세운 배타성이 한쪽에서만 성립하게 된다. 그래서 여기서는 <b>201 을 받은 직후에
 * 청구가 없다는 것</b>과 <b>폴러를 돌리면 그제야 나간다는 것</b>을 나란히 잰다.
 */
class SubscriptionCreationTest extends IntegrationTestSupport {

    @Autowired DunningScheduler scheduler;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager txManager;

    @LocalServerPort int port;

    private final HttpClient http = HttpClient.newHttpClient();

    /** {@code StubPgClient} 의 매직 금액(%1000 이 111·222)을 피한 승인 금액. */
    private static final long AMOUNT = 30_000L;
    private static final int INTERVAL_DAYS = 30;

    // ---------- ① 생성은 청구하지 않는다 ----------

    /**
     * ★ 201 을 받은 시점에 <b>주기도 시도도 없다.</b> 폴러를 한 번 돌려야 청구가 나간다.
     *
     * <p>이 테스트가 없으면 나중에 누군가 <i>"생성할 때 첫 결제를 바로 하자"</i> 로 바꿔도
     * 아무 red 가 안 난다 — 그건 편의가 아니라 <b>리스 없는 두 번째 청구 경로</b>를 여는 일이다.
     */
    @Test
    void 생성은_청구하지_않고_다음_폴링이_집는다() {
        long sellerId = createSeller();
        LocalDate today = LocalDate.now();

        HttpResponse<String> res = postSubscription(sellerId, AMOUNT, INTERVAL_DAYS);

        long subscriptions = num("SELECT count(*) FROM subscription");
        String nextBilling = str("SELECT next_billing_date::text FROM subscription");
        String status = str("SELECT status FROM subscription");
        long cyclesBefore = num("SELECT count(*) FROM billing_cycle");
        long attemptsBefore = num("SELECT count(*) FROM payment");

        scheduler.runBillingDue(today);

        long cyclesAfter = num("SELECT count(*) FROM billing_cycle");
        long attemptsAfter = num("SELECT count(*) FROM payment");
        String cycleStatus = str("SELECT status FROM billing_cycle");
        long entries = num("SELECT count(*) FROM ledger_entry");
        String movedTo = str("SELECT next_billing_date::text FROM subscription");

        System.out.printf("[실측] 구독 생성 → HTTP %d · 구독=%d(%s) 첫청구일=%s · 폴링 전 주기=%d 시도=%d → 폴링 후 주기=%d(%s) 시도=%d 분개줄=%d 다음청구일=%s%n",
                res.statusCode(), subscriptions, status, nextBilling,
                cyclesBefore, attemptsBefore, cyclesAfter, cycleStatus, attemptsAfter, entries, movedTo);

        assertThat(res.statusCode()).isEqualTo(201);
        assertThat(subscriptions).isEqualTo(1);
        assertThat(status).isEqualTo("ACTIVE");
        assertThat(nextBilling).isEqualTo(today.toString());

        assertThat(cyclesBefore).isZero();      // ★ 201 시점에 청구가 없다
        assertThat(attemptsBefore).isZero();

        assertThat(cyclesAfter).isEqualTo(1);   // ★ 폴러가 집고 나서야 생긴다
        assertThat(attemptsAfter).isEqualTo(1);
        assertThat(cycleStatus).isEqualTo("PAID");
        assertThat(entries).isEqualTo(2);       // 구독료 2줄 분개
        assertThat(movedTo).isEqualTo(today.plusDays(INTERVAL_DAYS).toString());
    }

    @Test
    void 없는_셀러로_만들면_404_다() {
        HttpResponse<String> res = postSubscription(999_999L, AMOUNT, INTERVAL_DAYS);

        System.out.printf("[실측] 없는 셀러 → HTTP %d · 구독=%d%n",
                res.statusCode(), num("SELECT count(*) FROM subscription"));

        assertThat(res.statusCode()).isEqualTo(404);
        assertThat(res.body()).contains("SELLER_NOT_FOUND");
        assertThat(num("SELECT count(*) FROM subscription")).isZero();
    }

    // ---------- ② 셀러당 활성 구독은 하나다 ----------

    @Test
    void 같은_셀러에_두_번_만들면_409_이고_구독은_하나다() {
        long sellerId = createSeller();

        HttpResponse<String> first = postSubscription(sellerId, AMOUNT, INTERVAL_DAYS);
        HttpResponse<String> second = postSubscription(sellerId, AMOUNT, INTERVAL_DAYS);

        long subscriptions = num("SELECT count(*) FROM subscription");

        System.out.printf("[실측] 같은 셀러 2회 → HTTP %d, %d · 구독=%d%n",
                first.statusCode(), second.statusCode(), subscriptions);

        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(second.statusCode()).isEqualTo(409);
        assertThat(second.body()).contains("ACTIVE_SUBSCRIPTION_EXISTS");
        assertThat(subscriptions).isEqualTo(1);
    }

    /**
     * ★ 동시 요청 열 개. 앱의 사전 확인은 <b>읽고 판단하고 쓰는</b> 모양이라 이 창에서 여럿이
     * 함께 통과하고, 그때 막는 건 {@code uq_subscription_active_seller}(V13)다.
     *
     * <p>W1D2 가 <i>"조회 후 앱 판단은 그 틈에서 둘 다 통과한다"</i> 로 기각한 자리와 같은
     * 구조인데, 여기서는 <b>둘 다 둔다</b> — 정상 경로는 앱이 예외 없이 거절하고, 경합은
     * 인덱스가 잡아 같은 409 로 접힌다. 그러므로 <b>단언은 HTTP 상태가 아니라 행 수가 낸다.</b>
     */
    @Test
    void 열_개가_동시에_만들어도_활성_구독은_하나다() throws Exception {
        long sellerId = createSeller();

        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger created = new AtomicInteger();
        AtomicInteger conflicted = new AtomicInteger();
        AtomicInteger other = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    int code = postSubscription(sellerId, AMOUNT, INTERVAL_DAYS).statusCode();
                    if (code == 201) created.incrementAndGet();
                    else if (code == 409) conflicted.incrementAndGet();
                    else other.incrementAndGet();
                } catch (Exception e) {
                    other.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        done.await(30, TimeUnit.SECONDS);
        pool.shutdownNow();

        long subscriptions = num("SELECT count(*) FROM subscription");

        System.out.printf("[실측] 동시 생성 %d개 → 201=%d · 409=%d · 그 외=%d · 활성 구독 행=%d%n",
                threads, created.get(), conflicted.get(), other.get(), subscriptions);

        assertThat(subscriptions).isEqualTo(1);     // ★ 헤드라인 — 매달 청구될 계약이 하나다
        assertThat(created.get()).isEqualTo(1);
        assertThat(other.get()).isZero();           // 500 이 섞이면 매핑이 새는 것이다
        assertThat(conflicted.get()).isEqualTo(threads - 1);
    }

    /**
     * 해지하면 다시 구독할 수 있다 — <b>부분</b> 유니크 인덱스를 고른 이유가 이 칸이다.
     * 조건 없는 UNIQUE 였다면 해지한 셀러가 영영 재구독을 못 한다.
     *
     * <p>{@code cancel()} 의 호출부(해지 API)는 아직 없다 — 그걸 열면 <b>해지 사유 구분</b>
     * (D1 이래 이월)을 함께 정해야 해서 오늘 범위 밖이다. 그래서 여기서는 엔티티를 직접
     * 해지시켜 <b>스키마의 주장</b>만 잰다.
     */
    @Test
    void 해지하면_같은_셀러가_다시_구독할_수_있다() {
        long sellerId = createSeller();
        assertThat(postSubscription(sellerId, AMOUNT, INTERVAL_DAYS).statusCode()).isEqualTo(201);

        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            Subscription active = em.createQuery(
                            "SELECT s FROM Subscription s WHERE s.seller.id = :id", Subscription.class)
                    .setParameter("id", sellerId)
                    .getSingleResult();
            active.cancel();
        });

        HttpResponse<String> again = postSubscription(sellerId, AMOUNT, INTERVAL_DAYS);

        long total = num("SELECT count(*) FROM subscription");
        long active = num("SELECT count(*) FROM subscription WHERE status = 'ACTIVE'");

        System.out.printf("[실측] 해지 후 재구독 → HTTP %d · 구독 행=%d(활성 %d)%n",
                again.statusCode(), total, active);

        assertThat(again.statusCode()).isEqualTo(201);
        assertThat(total).isEqualTo(2);
        assertThat(active).isEqualTo(1);
    }

    // ---------- helpers ----------

    private long createSeller() {
        return new TransactionTemplate(txManager).execute(s -> {
            Seller seller = new Seller("구독셀러", 500);
            em.persist(seller);
            em.flush();
            return seller.getId();
        });
    }

    private HttpResponse<String> postSubscription(long sellerId, long amount, int intervalDays) {
        String json = String.format(
                "{\"sellerId\":%d,\"amount\":%d,\"intervalDays\":%d}", sellerId, amount, intervalDays);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/subscriptions"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        try {
            return http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
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
