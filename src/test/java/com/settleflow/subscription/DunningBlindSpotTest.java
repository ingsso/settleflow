package com.settleflow.subscription;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.settleflow.payment.pg.PgClient;
import com.settleflow.payment.pg.PgResult;
import com.settleflow.payment.pg.PgTimeoutException;
import com.settleflow.seller.domain.Seller;
import com.settleflow.subscription.domain.Subscription;
import com.settleflow.subscription.service.DunningScheduler;
import com.settleflow.subscription.service.SubscriptionChargeService;
import com.settleflow.support.IntegrationTestSupport;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.willAnswer;

/**
 * W5D5 — <b>있는데 아무도 안 재던 자리 셋</b>을 잰다.
 *
 * <p>W5D4 §11 이 목록을 이렇게 남겼다:
 *
 * <pre>
 *   W5D2 대조군 D   P1 리스(구독 시계 CAS)      — 지워도 21건 전부 green
 *   W5D4 §8         집기 직전 백스톱             — 발화 조건을 잃었다
 *   W5D4 대조군 D   STALE 관측                  — 호출을 지워도 red 0건
 * </pre>
 *
 * 셋의 공통 성질이 클래스를 가른 기준이다 — <b>없어도 그날의 스위트가 전부 green</b> 이었다.
 * 주제가 아니라 <i>계기의 부재</i>로 묶인 파일이라, 각 절이 겨누는 것은 서로 다르다.
 *
 * <h3>계기 — 헤드라인은 키 개수, 교차검증은 PG 호출 수</h3>
 * W5D3 이 계기를 우리 DB 에서 PG 호출 수로 바꾼 이유가 여기서도 그대로다: <i>"한 시도 =
 * 한 PG 호출"</i> 은 검증 대상 코드 자신의 성질이라 {@code payment} 행 수를 <i>"청구 횟수"</i>
 * 라 부를 수 없다. 그래서 둘을 나란히 놓는다 —
 *
 * <pre>
 *   헤드라인   서로 다른 merchant_payment_id 개수   = PG 가 별개 청구로 볼 것의 수 (W5D4 축)
 *   교차검증   {@code stubPgClient} delegate 호출 수 = 실제로 PG 에 간 횟수 (W5D3 축)
 * </pre>
 *
 * 둘 중 하나만으로는 부족하다. 키만 보면 우리 DB 안에서 자족하고, 호출 수만 보면
 * <b>재전송과 중복 청구를 못 가른다</b>(W5D4 §7: 재전송은 호출을 늘리면서 키를 안 늘린다).
 *
 * <h3>백스톱은 로그를 계기로 쓴다</h3>
 * {@code SubscriptionChargeService.abandon} 의 <i>"시도를 걸기 전 상한 판정"</i> 로그는
 * <b>집기 직전 백스톱에서만</b> 찍힌다. 통보 경로의 포기({@code applySubscriptionFailure})는
 * 이 줄을 안 남긴다. 그래서 이 문자열의 등장 횟수가 곧 <b>백스톱 발화 횟수</b>다 —
 * 프로덕션 코드를 안 바꾸고 얻은 계기라, W4D1 이 회로를 데코레이터로 뺀 것과 같은 계열이다.
 */
class DunningBlindSpotTest extends IntegrationTestSupport {

    /** 갈아끼우는 건 delegate 뿐 — 회로를 가진 {@code ResilientPgClient} 는 그대로 통과한다. */
    @MockitoBean(name = "stubPgClient") PgClient delegate;

    @Autowired DunningScheduler scheduler;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager txManager;

    @LocalServerPort int port;

    private final HttpClient http = HttpClient.newHttpClient();
    private final AtomicInteger pgCalls = new AtomicInteger();

    private ListAppender<ILoggingEvent> chargeLog;
    private ListAppender<ILoggingEvent> schedulerLog;

    private static final long AMOUNT = 30_000L;
    private static final int INTERVAL_DAYS = 30;
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 15);

    /** 집기 직전 백스톱에서만 찍히는 줄({@code SubscriptionChargeService.abandon}). */
    private static final String BACKSTOP = "시도를 걸기 전 상한 판정";
    private static final String RESEND = "미결 시도 재전송";
    private static final String STALE = "[STALE]";

    @BeforeEach
    void setUp() {
        pgCalls.set(0);
        approves();
        chargeLog = attach(SubscriptionChargeService.class);
        schedulerLog = attach(DunningScheduler.class);
    }

    @AfterEach
    void tearDown() {
        detach(SubscriptionChargeService.class, chargeLog);
        detach(DunningScheduler.class, schedulerLog);
    }

    // ---------- ① P1 리스 — W5D2 대조군 D 가 "아무도 안 잰다"로 끝난 자리 ----------

    /**
     * 인스턴스 여럿이 <b>같은 정기 청구일</b>에 동시에 폴링한다.
     *
     * <p>이미 있는 {@code DunningSchedulerTest.열_개가_동시에_폴링해도_청구는_한_번만_나간다} 는
     * <b>P2</b>({@code runRetryDue} → {@code claimForRetry})를 때린다. 이건 그 <b>거울</b>이고,
     * P1({@code runBillingDue} → {@code claimBillingDate})을 때린다. W5D2 대조군 D 가
     * <i>"P1 리스를 지워도 21건이 전부 green"</i> 으로 나온 이유가 이 짝의 부재였다.
     *
     * <h3>왜 예외가 아니라 키 개수로 단언하나</h3>
     * CAS 가 없으면 진 스레드도 새 주기를 만들려 들고 {@code uq_billing_cycle_subscription_no}
     * 가 일부를 거절한다. 그 예외를 단언에 쓰면 <b>DB 제약이 잡아준 것</b>을 재게 된다.
     * 우리가 주장하려는 건 <i>"돈이 한 번만 나갔다"</i> 이므로, 계기는 <b>PG 로 나간 키의 수</b>여야
     * 한다 — 그래서 스레드 예외는 삼키고 단언은 키와 호출 수가 낸다.
     */
    @Test
    void 열_개가_동시에_정기청구를_폴링해도_키는_하나다() throws Exception {
        createSubscription();

        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    scheduler.runBillingDue(TODAY);
                } catch (Exception ignored) {
                    // 진 쪽은 0 행을 잡고 조용히 물러난다 — 예외로 지지 않는다(위 javadoc).
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        done.await(30, TimeUnit.SECONDS);
        pool.shutdownNow();

        long keys = num("SELECT count(DISTINCT merchant_payment_id) FROM payment");
        int calls = pgCalls.get();
        long cycles = num("SELECT count(*) FROM billing_cycle");
        long attempts = num("SELECT count(*) FROM payment WHERE billing_cycle_id IS NOT NULL");
        String nextBilling = str("SELECT next_billing_date::text FROM subscription");

        System.out.printf("[실측] P1 동시 폴링 %d개 → 서로 다른 키=%d · PG approve 호출=%d · 주기=%d · 시도=%d · 다음청구일=%s%n",
                threads, keys, calls, cycles, attempts, nextBilling);

        assertThat(keys).isEqualTo(1);        // ★ 헤드라인 — PG 가 별개 청구로 볼 것이 하나다
        assertThat(calls).isEqualTo(1);       // ★ 교차검증 — 실제로 PG 에 한 번 갔다
        assertThat(cycles).isEqualTo(1);
        assertThat(attempts).isEqualTo(1);
        assertThat(nextBilling).isEqualTo(TODAY.plusDays(INTERVAL_DAYS).toString());
    }

    // ---------- ② 백스톱 — 도달 경로가 실제로 있는가 ----------

    /**
     * 사다리를 끝까지 태운다. <b>포기는 통보 경로가 집행하고 백스톱은 안 걸린다.</b>
     *
     * <p>{@code DunningSchedulerTest.재시도는_1일_3일_7일_뒤_세_번이고_그다음_포기한다} 와 경로가
     * 같지만 <b>겨누는 명제가 다르다</b>: 저쪽은 사다리의 모양(간격·시도 수·최종 상태)을 재고,
     * 여기는 <b>그 포기가 어느 자리에서 나왔는가</b>를 잰다. 4번째 실패가 통보되는 순간
     * {@code applySubscriptionFailure} 가 사다리 끝을 보고 {@code ABANDONED} 로 보내므로,
     * 이 주기는 <b>집히는 집합을 먼저 떠난다</b> — 백스톱이 볼 기회가 없다.
     */
    @Test
    void 사다리를_끝까지_태워도_백스톱은_발화하지_않는다() {
        createSubscription();
        declines();

        scheduler.runBillingDue(TODAY);                             // 시도1
        OffsetDateTime t0 = OffsetDateTime.now();
        scheduler.runRetryDue(t0.plusDays(1).plusMinutes(1));       // 시도2
        scheduler.runRetryDue(t0.plusDays(4).plusMinutes(1));       // 시도3
        scheduler.runRetryDue(t0.plusDays(11).plusMinutes(1));      // 시도4 → 통보에서 포기
        int afterAbandon = scheduler.runRetryDue(t0.plusDays(60));

        String status = str("SELECT status FROM billing_cycle");
        long concluded = num("SELECT count(*) FROM payment WHERE status IN ('FAILED','UNKNOWN')");
        long backstop = lines(chargeLog, BACKSTOP);

        System.out.printf("[실측] 사다리 소진 → 주기=%s · 결말난시도=%d · ★백스톱 발화=%d회 · 이후 집은수=%d · PG호출=%d%n",
                status, concluded, backstop, afterAbandon, pgCalls.get());

        assertThat(status).isEqualTo("ABANDONED");
        assertThat(concluded).isEqualTo(4);
        assertThat(backstop).isZero();        // ★ 상한을 집행한 건 통보 경로다
        assertThat(afterAbandon).isZero();
    }

    /**
     * ★ <b>상한을 넘긴 주기가 다시 집혀도 백스톱에 안 닿는다.</b> 재전송 분기가 그 앞에 있다.
     *
     * <p>백스톱에 닿으려면 <i>"결말난 시도 4개 + 미결 시도 없음 + 아직 집히는 주기"</i> 가 동시에
     * 서야 한다. 4번째 시도를 타임아웃시키면 앞의 둘 중 첫째는 서고({@code FAILED} 3 +
     * {@code UNKNOWN} 1 = 4) 주기도 {@code FAILED} 로 남아 P2 가 다시 집는다 — 그런데
     * <b>둘째가 안 선다</b>. {@code UNKNOWN} 이 곧 미결이라
     * {@code findUnresolvedAttemptId} 가 먼저 잡아 재전송으로 빠진다.
     *
     * <p>모순이 아니라 W5D4 의 설계다: 상한은 <i>"청구를 몇 번 걸었나"</i> 를 세는데 재전송은
     * 청구를 안 늘린다. 다만 그 결과로 <b>백스톱은 이 경로에서 영영 실행되지 않는다</b>.
     */
    @Test
    void 마지막_시도가_미결이면_재집기는_백스톱을_지나쳐_재전송한다() {
        createSubscription();
        declines();

        scheduler.runBillingDue(TODAY);                             // 시도1
        OffsetDateTime t0 = OffsetDateTime.now();
        scheduler.runRetryDue(t0.plusDays(1).plusMinutes(1));       // 시도2
        scheduler.runRetryDue(t0.plusDays(4).plusMinutes(1));       // 시도3

        timesOut();
        scheduler.runRetryDue(t0.plusDays(11).plusMinutes(1));      // 시도4 → UNKNOWN

        long concluded = num("SELECT count(*) FROM payment WHERE status IN ('FAILED','UNKNOWN')");
        int picked = scheduler.runRetryDue(t0.plusDays(11).plusMinutes(10));   // 리스 만료 후 재집기

        String status = str("SELECT status FROM billing_cycle");
        long attempts = num("SELECT count(*) FROM payment");
        long keys = num("SELECT count(DISTINCT merchant_payment_id) FROM payment");
        long resend = lines(chargeLog, RESEND);
        long backstop = lines(chargeLog, BACKSTOP);

        System.out.printf("[실측] 상한 초과 뒤 재집기 → 결말난시도=%d · 집은수=%d · 시도=%d · 키=%d · 재전송=%d회 · ★백스톱=%d회 · PG호출=%d · 주기=%s%n",
                concluded, picked, attempts, keys, resend, backstop, pgCalls.get(), status);

        assertThat(concluded).isEqualTo(4);   // 상한(재시도 3회)을 이미 넘긴 주기다
        assertThat(picked).isEqualTo(1);      // 그런데도 다시 집힌다
        assertThat(attempts).isEqualTo(4);    // 5번째 시도가 안 생겼다
        assertThat(keys).isEqualTo(4);        // ★ 새 키가 안 나갔다 = 새 청구가 아니라 재전송
        assertThat(resend).isEqualTo(1);
        assertThat(backstop).isZero();        // ★ 재전송 분기가 백스톱 앞에 있다
        assertThat(status).isEqualTo("FAILED");
    }

    /**
     * 미결이 웹훅으로 결말나는 세 번째 길. 여기서도 <b>통보 경로가 먼저 포기시킨다.</b>
     *
     * <p>{@code UNKNOWN} 시도에 거절 웹훅이 도착하면 {@code applySubscriptionFailure} 가
     * <i>이번 시도를 뺀</i> 결말 수에 +1 해서 사다리를 보고({@code countConcludedAttemptsExcluding},
     * W5D3 의 off-by-one 수리) 4번째임을 알아 {@code ABANDONED} 로 보낸다.
     * <b>미결이 사라지는 유일한 길이 곧 주기가 종착지로 가는 길</b>이라, 백스톱이 요구하는
     * 상태(미결 없음 + 아직 집힘)가 여기서도 안 선다.
     */
    @Test
    void 미결이_웹훅으로_결말나도_주기는_통보에서_포기된다() {
        createSubscription();
        declines();

        scheduler.runBillingDue(TODAY);
        OffsetDateTime t0 = OffsetDateTime.now();
        scheduler.runRetryDue(t0.plusDays(1).plusMinutes(1));
        scheduler.runRetryDue(t0.plusDays(4).plusMinutes(1));

        timesOut();
        scheduler.runRetryDue(t0.plusDays(11).plusMinutes(1));      // 시도4 → UNKNOWN

        String mpid = str("SELECT merchant_payment_id::text FROM payment WHERE status = 'UNKNOWN'");
        postWebhook("evt-" + UUID.randomUUID(), "DECLINED", mpid);

        String status = str("SELECT status FROM billing_cycle");
        long unresolved = num("SELECT count(*) FROM payment WHERE status IN ('PENDING','UNKNOWN')");
        long backstop = lines(chargeLog, BACKSTOP);
        int afterward = scheduler.runRetryDue(t0.plusDays(60));

        System.out.printf("[실측] 웹훅이 미결을 닫음 → 주기=%s · 남은 미결=%d · ★백스톱=%d회 · 이후 집은수=%d%n",
                status, unresolved, backstop, afterward);

        assertThat(status).isEqualTo("ABANDONED");
        assertThat(unresolved).isZero();      // 미결이 사라졌다 — 그런데 그 순간 주기도 떠났다
        assertThat(backstop).isZero();
        assertThat(afterward).isZero();
    }

    // ---------- ③ STALE 관측 — W5D4 대조군 D 가 red 0 으로 끝난 자리 ----------

    /**
     * 오래 결말이 안 난 시도가 <b>드러나는가</b>. 계기는 경고 로그다.
     *
     * <p>W5D4 대조군 D 는 {@code reportStaleUnresolved} 호출을 지웠는데 <b>red 가 0건</b>이었다 —
     * 관측을 넣고 재지는 않았기 때문이다. {@code countStaleUnresolvedAttempts} 쿼리만 테스트하면
     * 그 대조군은 여전히 green 이므로(호출이 사라져도 쿼리는 멀쩡하다), <b>호출의 결과</b>를
     * 재야 한다.
     *
     * <p>양쪽을 다 본다: 갓 만들어진 미결은 조용해야 하고, 6시간을 넘긴 미결은 소리를 내야 한다.
     * 앞쪽이 없으면 <i>"항상 경고한다"</i> 는 구현도 통과한다.
     */
    @Test
    void 여섯시간_넘은_미결_시도만_STALE_로_드러난다() {
        createSubscription();
        timesOut();
        scheduler.runBillingDue(TODAY);       // 시도1 → UNKNOWN, created_at = 지금

        scheduler.runRetryDue(OffsetDateTime.now());
        long fresh = lines(schedulerLog, STALE);

        backdateAttempts(7);
        scheduler.runRetryDue(OffsetDateTime.now());
        long aged = lines(schedulerLog, STALE);

        long unresolved = num("SELECT count(*) FROM payment WHERE status IN ('PENDING','UNKNOWN')");

        System.out.printf("[실측] 미결 %d건 → 갓 만들어졌을 때 STALE 경고=%d회 · 7시간 지난 뒤 누적=%d회%n",
                unresolved, fresh, aged);

        assertThat(unresolved).isEqualTo(1);
        assertThat(fresh).isZero();           // 6시간 안쪽은 조용하다
        assertThat(aged).isEqualTo(1);        // ★ 넘기면 드러난다
    }

    // ---------- helpers ----------

    private void createSubscription() {
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            Seller seller = new Seller("구독셀러", 500);
            em.persist(seller);
            em.persist(new Subscription(seller, AMOUNT, INTERVAL_DAYS, TODAY));
            em.flush();
        });
    }

    /** 미결 시도를 과거로 밀어 STALE 임계를 넘긴다 — 6시간을 실제로 기다릴 수는 없다. */
    private void backdateAttempts(int hours) {
        new TransactionTemplate(txManager).executeWithoutResult(s ->
                em.createNativeQuery("UPDATE payment SET created_at = now() - make_interval(hours => :h)")
                        .setParameter("h", hours)
                        .executeUpdate());
    }

    private void approves() {
        pgAnswer(() -> PgResult.APPROVED);
    }

    private void declines() {
        pgAnswer(() -> PgResult.DECLINED);
    }

    private void timesOut() {
        pgAnswer(() -> {
            throw new PgTimeoutException("W5D5 주입");
        });
    }

    /**
     * <b>{@code will…given} 형태만 쓴다</b>({@code SubscriptionIdempotencyTest} 와 같은 이유):
     * {@code given(mock.method())} 은 스텁을 걸면서 그 메서드를 실제로 호출하는데, 직전에
     * 예외를 던지도록 스텁돼 있으면 <b>스텁을 다시 거는 줄에서 터진다</b>.
     * 이 클래스는 거절 → 타임아웃으로 갈아끼우므로 그 함정에 정확히 걸린다.
     */
    private void pgAnswer(Supplier<PgResult> outcome) {
        willAnswer(invocation -> {
            pgCalls.incrementAndGet();
            return outcome.get();
        }).given(delegate).approve(any(), anyLong());
    }

    private ListAppender<ILoggingEvent> attach(Class<?> type) {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        ((Logger) LoggerFactory.getLogger(type)).addAppender(appender);
        return appender;
    }

    private void detach(Class<?> type, ListAppender<ILoggingEvent> appender) {
        ((Logger) LoggerFactory.getLogger(type)).detachAppender(appender);
        appender.stop();
    }

    private long lines(ListAppender<ILoggingEvent> appender, String needle) {
        return appender.list.stream()
                .filter(event -> event.getFormattedMessage().contains(needle))
                .count();
    }

    private void postWebhook(String eventId, String type, String mpid) {
        String json = String.format(
                "{\"eventId\":\"%s\",\"eventType\":\"%s\",\"merchantPaymentId\":\"%s\","
                        + "\"pgTransactionId\":\"%s\",\"amount\":%d}",
                eventId, type, mpid, "pg-tx-" + eventId, AMOUNT);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/webhooks/pg"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        try {
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            assertThat(res.statusCode()).isEqualTo(200);
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
