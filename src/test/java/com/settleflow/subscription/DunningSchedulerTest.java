package com.settleflow.subscription;

import com.settleflow.seller.domain.Seller;
import com.settleflow.subscription.domain.Subscription;
import com.settleflow.subscription.service.DunningScheduler;
import com.settleflow.support.IntegrationTestSupport;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * W5D2 실측 — Dunning 폴러 둘이 실제 DB·PG 스텁을 태워 돌아가는가.
 *
 * <p><b>시각을 인자로 받는 진입점을 쓴다</b>({@code runBillingDue(today)} /
 * {@code runRetryDue(now)}). 그래서 "1일 뒤"를 기다리지 않고 미래 시각을 넣어 사다리를
 * 통째로 재현할 수 있다. {@code @Scheduled} 는 테스트에서 꺼져 있다
 * ({@code settleflow.dunning.scheduler.enabled=false}).
 *
 * <p>PG 결과는 {@code StubPgClient} 의 매직 금액으로 고른다 —
 * {@code %1000==111} 거절, {@code %1000==222} 타임아웃, 그 외 승인.
 * 구독료가 고정이라 <b>한 구독의 모든 시도가 같은 결말</b>을 낸다.
 */
class DunningSchedulerTest extends IntegrationTestSupport {

    @Autowired DunningScheduler scheduler;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager txManager;

    private static final long AMOUNT_OK = 30_000L;
    private static final long AMOUNT_DECLINE = 30_111L;
    private static final long AMOUNT_TIMEOUT = 30_222L;

    private static final int INTERVAL_DAYS = 30;

    // ---------- 1. 성공 경로 — D1 이 "한 번도 실행 안 됨"으로 남긴 자리 ----------

    /**
     * ★ D1 §12 ⑤ 해소. {@code recordSubscriptionCharge} 가 만든 분개를 처음으로 실제로 본다 —
     * 그때까지는 <b>부르는 경로가 없어서</b> 한 줄도 실행된 적이 없었다.
     */
    @Test
    void 정기청구가_주기를_만들고_구독료_2줄_분개가_남는다() {
        LocalDate today = LocalDate.of(2026, 8, 15);
        long subId = createSubscription(AMOUNT_OK, today);

        scheduler.runBillingDue(today);

        long cycles = count("SELECT count(*) FROM billing_cycle");
        String cycleStatus = str("SELECT status FROM billing_cycle");
        long cycleNo = count("SELECT cycle_no FROM billing_cycle");
        long payments = count("SELECT count(*) FROM payment WHERE billing_cycle_id IS NOT NULL");
        long paymentsWithOrder = count("SELECT count(*) FROM payment WHERE order_id IS NOT NULL");
        long entries = count("SELECT count(*) FROM ledger_entry");
        long cash = count("SELECT coalesce(sum(amount),0) FROM ledger_entry WHERE account = 'CASH'");
        long subRevenue = count("SELECT coalesce(sum(amount),0) FROM ledger_entry WHERE account = 'SUBSCRIPTION_REVENUE'");
        String paymentStatus = str("SELECT status FROM payment");
        long journals = count("SELECT count(*) FROM journal");
        long revenueWithSeller =
                count("SELECT count(*) FROM ledger_entry WHERE account = 'SUBSCRIPTION_REVENUE' AND seller_id IS NOT NULL");
        String nextBilling = str("SELECT next_billing_date::text FROM subscription WHERE id = " + subId);

        System.out.printf("[실측] 정기청구 → 주기=%d(%s, no=%d) 시도=%d(%s) journal=%d 분개줄=%d 현금=%d 구독수익=%d 다음청구일=%s%n",
                cycles, cycleStatus, cycleNo, payments, paymentStatus, journals, entries, cash, subRevenue, nextBilling);

        assertThat(cycles).isEqualTo(1);
        assertThat(cycleStatus).isEqualTo("PAID");
        assertThat(cycleNo).isEqualTo(1);
        assertThat(payments).isEqualTo(1);
        assertThat(paymentsWithOrder).isZero();            // 배타적 아크 — 구독 청구엔 주문이 없다
        assertThat(entries).isEqualTo(2);                  // 안분 없음(판매 결제는 2N+1 줄)
        assertThat(cash).isEqualTo(AMOUNT_OK);
        assertThat(subRevenue).isEqualTo(AMOUNT_OK);
        assertThat(revenueWithSeller).isEqualTo(1);        // chk_ledger_seller_presence
        assertThat(nextBilling).isEqualTo(today.plusDays(INTERVAL_DAYS).toString());
    }

    // ---------- 2. 실패 → 1일 뒤 예약 ----------

    @Test
    void 청구가_실패하면_주기가_FAILED_로_가고_하루_뒤로_예약된다() {
        LocalDate today = LocalDate.of(2026, 8, 15);
        createSubscription(AMOUNT_DECLINE, today);

        OffsetDateTime before = OffsetDateTime.now();
        scheduler.runBillingDue(today);

        String cycleStatus = str("SELECT status FROM billing_cycle");
        String paymentStatus = str("SELECT status FROM payment");
        long hoursAhead = count("SELECT EXTRACT(EPOCH FROM (next_retry_at - now()))::bigint / 3600 FROM billing_cycle");
        long entries = count("SELECT count(*) FROM ledger_entry");

        System.out.printf("[실측] 청구 실패 → 주기=%s 시도=%s 다음재시도=%d시간 뒤 분개줄=%d (기준 %s)%n",
                cycleStatus, paymentStatus, hoursAhead, entries, before);

        assertThat(cycleStatus).isEqualTo("FAILED");
        assertThat(paymentStatus).isEqualTo("FAILED");
        assertThat(hoursAhead).isBetween(23L, 24L);        // 1일 뒤
        assertThat(entries).isZero();                      // 돈이 안 들어왔으니 분개도 없다
    }

    // ---------- 3. ★ 사다리 전체 — 계획서 "1일→3일→7일 후 3회"의 실측 ----------

    @Test
    void 재시도는_1일_3일_7일_뒤_세_번이고_그다음_포기한다() {
        LocalDate today = LocalDate.of(2026, 8, 15);
        createSubscription(AMOUNT_DECLINE, today);

        scheduler.runBillingDue(today);                                  // 원 청구 실패
        OffsetDateTime t0 = OffsetDateTime.now();

        int r1 = scheduler.runRetryDue(t0.plusDays(1).plusMinutes(1));   // 재시도1
        long after1 = attemptsOnCycle();
        int r2 = scheduler.runRetryDue(t0.plusDays(4).plusMinutes(1));   // 재시도2 (누적 4일)
        long after2 = attemptsOnCycle();
        int r3 = scheduler.runRetryDue(t0.plusDays(11).plusMinutes(1));  // 재시도3 (누적 11일)
        long after3 = attemptsOnCycle();

        String finalStatus = str("SELECT status FROM billing_cycle");
        long retryAtRows = count("SELECT count(*) FROM billing_cycle WHERE next_retry_at IS NOT NULL");

        int r4 = scheduler.runRetryDue(t0.plusDays(60));                 // 더는 안 집힌다

        System.out.printf("[실측] 사다리 → 집은수 %d/%d/%d/%d · 누적 시도 %d/%d/%d · 최종=%s · 예약남음=%d%n",
                r1, r2, r3, r4, after1, after2, after3, finalStatus, retryAtRows);

        assertThat(r1).isEqualTo(1);
        assertThat(r2).isEqualTo(1);
        assertThat(r3).isEqualTo(1);
        assertThat(after1).isEqualTo(2);          // 원 청구 + 재시도1
        assertThat(after2).isEqualTo(3);
        assertThat(after3).isEqualTo(4);          // 총 시도 4 = 재시도 3
        assertThat(finalStatus).isEqualTo("ABANDONED");
        assertThat(retryAtRows).isZero();         // 종착지에선 예약이 비워진다(D1 음성대조군 B)
        assertThat(r4).isZero();                  // 끝난 주기를 영원히 집지 않는다
    }

    /** 중간 시각에는 안 집힌다 — 간격이 실제로 지켜지는가. */
    @Test
    void 예약_시각_전에는_집히지_않는다() {
        LocalDate today = LocalDate.of(2026, 8, 15);
        createSubscription(AMOUNT_DECLINE, today);

        scheduler.runBillingDue(today);
        OffsetDateTime t0 = OffsetDateTime.now();

        int tooEarly = scheduler.runRetryDue(t0.plusHours(23));
        long attemptsAfterEarly = attemptsOnCycle();
        int onTime = scheduler.runRetryDue(t0.plusDays(1).plusMinutes(1));

        System.out.printf("[실측] 23시간 뒤 집은수=%d(시도 %d) · 1일 1분 뒤 집은수=%d%n",
                tooEarly, attemptsAfterEarly, onTime);

        assertThat(tooEarly).isZero();
        assertThat(attemptsAfterEarly).isEqualTo(1);
        assertThat(onTime).isEqualTo(1);
    }

    // ---------- 4. ★ 동시 폴링 — 리스가 실제로 배타적인가 ----------

    /**
     * 인스턴스 여럿이 같은 순간에 폴링하는 상황. 낙관적 락만으로는 <b>둘 다 PG 를 부른 뒤</b>
     * 하나가 롤백되므로 돈이 두 번 나간다 — 그래서 집는 단계에서 CAS 로 갈랐다.
     *
     * <p>계획서 W5D5 가 k6 로 재려는 시나리오의 축소판이다.
     */
    @Test
    void 열_개가_동시에_폴링해도_청구는_한_번만_나간다() throws Exception {
        LocalDate today = LocalDate.of(2026, 8, 15);
        createSubscription(AMOUNT_DECLINE, today);

        scheduler.runBillingDue(today);                     // 원 청구 실패 → FAILED + 예약
        OffsetDateTime due = OffsetDateTime.now().plusDays(1).plusMinutes(1);

        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    scheduler.runRetryDue(due);
                } catch (Exception ignored) {
                    // 진 쪽은 0 행을 잡고 조용히 물러난다 — 예외로 지지 않는다
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        done.await(30, TimeUnit.SECONDS);
        pool.shutdownNow();

        long attempts = attemptsOnCycle();
        String cycleStatus = str("SELECT status FROM billing_cycle");
        long cycles = count("SELECT count(*) FROM billing_cycle");

        System.out.printf("[실측] 동시 폴링 %d개 → 이 주기의 payment 행=%d (기대 2: 원청구+재시도1) 주기수=%d 상태=%s%n",
                threads, attempts, cycles, cycleStatus);

        assertThat(attempts).isEqualTo(2);
        assertThat(cycles).isEqualTo(1);
    }

    // ---------- 5. 타임아웃 — 주기를 안 건드린다 ----------

    /**
     * {@code UNKNOWN} 은 결말이 아니라 정보의 부재다. 주기는 {@code PENDING} 인 채로 남고,
     * {@code chk_billing_cycle_retry_shape} 때문에 예약 시각을 박을 수도 없다 —
     * <b>P2 의 부분 인덱스가 이 행을 담지 않는다.</b> 회수는 P1(구독 시계)이 한다.
     */
    @Test
    void 타임아웃_시도는_주기를_PENDING_으로_남기고_재시도_폴러가_못_집는다() {
        LocalDate today = LocalDate.of(2026, 8, 15);
        createSubscription(AMOUNT_TIMEOUT, today);

        scheduler.runBillingDue(today);

        String cycleStatus = str("SELECT status FROM billing_cycle");
        String paymentStatus = str("SELECT status FROM payment");
        long retryAtRows = count("SELECT count(*) FROM billing_cycle WHERE next_retry_at IS NOT NULL");

        int picked = scheduler.runRetryDue(OffsetDateTime.now().plusDays(365));

        System.out.printf("[실측] 타임아웃 → 주기=%s 시도=%s 예약행=%d · 1년 뒤 재시도폴러가 집은수=%d%n",
                cycleStatus, paymentStatus, retryAtRows, picked);

        assertThat(cycleStatus).isEqualTo("PENDING");
        assertThat(paymentStatus).isEqualTo("UNKNOWN");
        assertThat(retryAtRows).isZero();
        assertThat(picked).isZero();          // ★ 유실이 아니라 지연 — P2 는 영영 못 집는다
    }

    // ---------- 6. ★ UNKNOWN 은 더 이상 쌓이지 않는다 (W5D4 에서 뜻이 바뀐 테스트) ----------

    /**
     * ★ <b>이 테스트는 W5D4 에 뜻이 바뀌었다.</b> 기록해둘 값이 있어 경위를 남긴다.
     *
     * <h3>W5D2 에 겨누던 것</h3>
     * <i>집기 직전의 상한 판정(백스톱)</i>이었다. 그때는 {@code UNKNOWN} 이 날 때마다
     * <b>새 시도</b>가 붙었고, 그 시도들이 쌓여 4번째에 백스톱이 주기를 포기시켰다.
     * 단언은 {@code ABANDONED} · 시도 4개였다.
     *
     * <h3>W5D4 에 사라진 것</h3>
     * 재전송이 들어오면서 <b>{@code UNKNOWN} 은 새 시도를 만들지 않는다</b> — 결말이 안 난
     * 시도는 같은 키로 다시 물을 뿐이다. 그래서 시도가 안 쌓이고, 백스톱이 세는 값이
     * 1 에서 안 움직이며, 주기는 답을 얻을 때까지 {@code PENDING} 으로 남는다.
     * <b>그게 의도다</b>(W5D4: 무한 질의는 무한 청구와 다르다).
     *
     * <h3>★ 그래서 백스톱은 지금 아무도 안 재고 있다</h3>
     * 없애면 안 된다 — 실패가 <i>확정</i>된 시도가 쌓이는 경로에서는 여전히 마지막 벽이다.
     * 다만 <b>W5D2 대조군 B 가 red 로 잡아내던 그 시나리오가 사라졌으므로</b>, 지금 백스톱을
     * 지워도 red 를 낼 테스트가 없다. W5D2 대조군 D(P1 리스)와 같은 자리에 하나가 더 생겼다.
     *
     * <p>겹침 처리는 그대로 드러난다: 열린 주기를 회수하면서 <b>새 주기도 만든다</b>
     * (W5D2 결정 5 — 둘은 다른 기간의 돈이다).
     */
    @Test
    void UNKNOWN_은_재전송되므로_시도가_쌓이지_않는다() {
        LocalDate today = LocalDate.of(2026, 8, 15);
        createSubscription(AMOUNT_TIMEOUT, today);

        long firstCycleId = 0;
        for (int round = 0; round < 5; round++) {
            scheduler.runBillingDue(today.plusDays((long) INTERVAL_DAYS * round));
            if (round == 0) firstCycleId = count("SELECT min(id) FROM billing_cycle");

            System.out.printf("[실측] round=%d 주기수=%d · 1번주기 상태=%s 시도=%d%n",
                    round,
                    count("SELECT count(*) FROM billing_cycle"),
                    str("SELECT status FROM billing_cycle WHERE id = " + firstCycleId),
                    count("SELECT count(*) FROM payment WHERE billing_cycle_id = " + firstCycleId));
        }

        String firstStatus = str("SELECT status FROM billing_cycle WHERE id = " + firstCycleId);
        long firstAttempts = count("SELECT count(*) FROM payment WHERE billing_cycle_id = " + firstCycleId);
        long firstKeys = count(
                "SELECT count(DISTINCT merchant_payment_id) FROM payment WHERE billing_cycle_id = " + firstCycleId);
        long cycles = count("SELECT count(*) FROM billing_cycle");

        assertThat(firstStatus).isEqualTo("PENDING");   // 결말을 못 받았으니 미결이다 — 포기가 아니다
        assertThat(firstAttempts).isEqualTo(1);         // ★ W5D2 엔 4였다. 재전송이라 안 쌓인다
        assertThat(firstKeys).isEqualTo(1);             // PG 에겐 계속 같은 한 건이다
        assertThat(cycles).isEqualTo(5);                // 회수와 별개로 새 주기는 매번 생긴다
    }

    // ---------- helpers ----------

    private long createSubscription(long amount, LocalDate firstBillingDate) {
        return new TransactionTemplate(txManager).execute(s -> {
            Seller seller = new Seller("구독셀러", 500);
            em.persist(seller);
            Subscription subscription = new Subscription(seller, amount, INTERVAL_DAYS, firstBillingDate);
            em.persist(subscription);
            em.flush();
            return subscription.getId();
        });
    }

    private long attemptsOnCycle() {
        return count("SELECT count(*) FROM payment WHERE billing_cycle_id IS NOT NULL");
    }

    private long count(String sql) {
        Object v = em.createNativeQuery(sql).getSingleResult();
        return v == null ? 0 : ((Number) v).longValue();
    }

    private String str(String sql) {
        Object v = em.createNativeQuery(sql).getSingleResult();
        return v == null ? null : v.toString();
    }
}
