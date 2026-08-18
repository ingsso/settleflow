package com.settleflow.subscription;

import com.settleflow.payment.pg.PgClient;
import com.settleflow.payment.pg.PgRefundResult;
import com.settleflow.payment.pg.PgResult;
import com.settleflow.payment.pg.PgTimeoutException;
import com.settleflow.seller.domain.Seller;
import com.settleflow.subscription.domain.Subscription;
import com.settleflow.subscription.service.DunningScheduler;
import com.settleflow.support.IntegrationTestSupport;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.willReturn;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * W5D4 — W5D3 이 반증한 이중 청구 창을 닫는다.
 *
 * <h3>어제 결론을 한 칸 좁혔다</h3>
 * W5D3 은 <i>"멱등키는 중복 청구를 막는 물건이 아니다"</i> 로 끝났는데, 그건 넓었다.
 * 정확히는 <b>"우리가 재전송해야 할 자리에서 새 시도를 만들었다"</b> 다. 시도 A 가 결말이
 * 안 났다는 건 <i>새 청구가 필요하다</i>가 아니라 <i>저 시도의 답을 아직 못 들었다</i> 이고,
 * 같은 키로 다시 보내는 건 <b>같은 시도의 재전송</b> — 멱등키가 정확히 그걸 위해 있다.
 *
 * <p>시도 층은 안 무너진다: 행이 안 늘고, 상한 계산도 안 흔들리며, 결과 통보는 그
 * {@code payment} 행의 전이표가 흡수한다. W5D3 §7 이 <i>"주기 단위 키를 주면 시도 층이
 * 무너진다 — 한쪽을 고치면 다른 쪽이 깨지는 자리"</i> 라고 적은 건 <b>세 번째 선택지를
 * 못 본 것</b>이었다.
 *
 * <h3>★ 이 파일은 두 종류의 단언을 갈라서 담는다</h3>
 * <pre>
 *   §1 우리가 보증하는 것   — 서로 다른 키를 몇 개 내보냈나. 전부 우리 쪽 사실이다
 *   §2 PG 에 기대는 것      — 같은 키면 한 번만 과금된다. 우리가 검증할 수 없는 외부 계약이라
 *                            멱등 스텁으로 "그 계약이 참이라면" 을 보인다
 * </pre>
 * §2 의 스텁은 <b>우리가 심은 가정</b>이다. 그 단언은 PG 가 아니라 우리 믿음을 확인한다
 * (W4 회고 §4-2). 경계를 지우지 않으려고 파일 안에서 절을 갈랐다.
 */
class SubscriptionResendTest extends IntegrationTestSupport {

    @MockitoBean(name = "stubPgClient") PgClient delegate;

    @Autowired DunningScheduler scheduler;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager txManager;

    private static final long AMOUNT = 30_000L;
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 16);

    @BeforeEach
    void stubApproves() {
        willReturn(PgResult.APPROVED).given(delegate).approve(any(), anyLong());
    }

    // =====================================================================
    // §1 우리가 보증하는 것 — 서로 다른 키를 몇 개 내보냈나
    // =====================================================================

    /**
     * ★ W5D3 의 크래시 시나리오를 그대로 다시 돌린다. 어제는 키가 <b>3개</b>였다.
     *
     * <p>PG 호출 횟수는 그대로 3회다 — 재전송도 호출이니까. 줄어드는 건 <b>키의 개수</b>이고,
     * 그게 "PG 가 별개 청구로 볼 것이 몇 건인가" 다.
     */
    @Test
    void 크래시로_리스만_남아도_재전송이라_새_키가_안_는다() {
        createSubscription();

        willReturn(PgResult.DECLINED).given(delegate).approve(any(), anyLong());
        scheduler.runBillingDue(TODAY);                       // 원 청구 실패 → 주기 FAILED
        OffsetDateTime t0 = OffsetDateTime.now();

        willThrow(new RuntimeException("process died after PG charge"))
                .given(delegate).approve(any(), anyLong());
        assertThatThrownBy(() -> scheduler.runRetryDue(t0.plusDays(1).plusMinutes(1)))
                .isInstanceOf(RuntimeException.class);

        String crashedKey = str("SELECT merchant_payment_id::text FROM payment WHERE status = 'PENDING'");

        willReturn(PgResult.APPROVED).given(delegate).approve(any(), anyLong());
        scheduler.runRetryDue(t0.plusDays(1).plusMinutes(30));   // 리스 만료 후 재집기

        long attempts = num("SELECT count(*) FROM payment");
        long distinctKeys = num("SELECT count(DISTINCT merchant_payment_id) FROM payment");
        String resolvedStatus = str("SELECT status FROM payment WHERE merchant_payment_id = '" + crashedKey + "'");
        String cycleStatus = str("SELECT status FROM billing_cycle");

        verify(delegate, times(3)).approve(any(), anyLong());   // 호출은 여전히 3회

        System.out.printf("[실측] 크래시 후 재집기 → PG approve 3회 · 시도 행 %d · 서로 다른 키 %d개 (W5D3 은 3개)%n",
                attempts, distinctKeys);
        System.out.printf("[실측] 크래시났던 시도가 재전송으로 결말남: %s · 주기=%s%n", resolvedStatus, cycleStatus);

        assertThat(attempts).isEqualTo(2);        // 새 행이 안 생겼다
        assertThat(distinctKeys).isEqualTo(2);    // ★ 어제 3개 → 오늘 2개
        assertThat(resolvedStatus).isEqualTo("PAID");
        assertThat(cycleStatus).isEqualTo("PAID");
    }

    /**
     * ★ 크래시보다 흔한 경우. 타임아웃은 {@code UNKNOWN} 을 남기는데 그것도 결말이 아니다.
     *
     * <p>W5D2 결정 (c)는 {@code UNKNOWN} 을 <b>시도로 센다</b>(무한 재시도를 막으려고).
     * 그런데 세는 것과 <i>새 청구를 내는 것</i>은 다른 문제다 — 세되, 새로 내지는 않는다.
     */
    @Test
    void 타임아웃난_시도도_새_청구가_아니라_재전송된다() {
        createSubscription();

        willThrow(new PgTimeoutException("PG 무응답")).given(delegate).approve(any(), anyLong());
        scheduler.runBillingDue(TODAY);   // 주기 PENDING · 시도 UNKNOWN

        String firstKey = str("SELECT merchant_payment_id::text FROM payment");
        String statusAfterTimeout = str("SELECT status FROM payment");

        // 다음 청구일에 P1 이 열린 주기를 회수한다 — 여기서 새 시도를 만들면 이중 청구다
        willReturn(PgResult.APPROVED).given(delegate).approve(any(), anyLong());
        scheduler.runBillingDue(TODAY.plusDays(30));

        long distinctKeys = num("SELECT count(DISTINCT merchant_payment_id) FROM payment");
        long cycles = num("SELECT count(*) FROM billing_cycle");
        String firstCycleStatus = str("SELECT status FROM billing_cycle ORDER BY cycle_no LIMIT 1");
        String resendResult = str("SELECT status FROM payment WHERE merchant_payment_id = '" + firstKey + "'");

        System.out.printf("[실측] 타임아웃 시도=%s → 회수 시 재전송 → %s · 1주기=%s · 주기수=%d · 키 %d개%n",
                statusAfterTimeout, resendResult, firstCycleStatus, cycles, distinctKeys);

        assertThat(statusAfterTimeout).isEqualTo("UNKNOWN");
        assertThat(resendResult).isEqualTo("PAID");        // 같은 시도가 결말을 받았다
        assertThat(firstCycleStatus).isEqualTo("PAID");
        assertThat(cycles).isEqualTo(2);                   // 회수와 별개로 새 주기는 생긴다(W5D2 결정 5)
        assertThat(distinctKeys).isEqualTo(2);             // 1주기 재전송 + 2주기 새 청구
    }

    /**
     * 재전송은 상한을 안 쓴다 — 청구를 안 늘리기 때문이다.
     * PG 가 계속 답을 안 주면 <b>무한 질의</b>가 되고, 그게 의도다(무한 청구와 다르다).
     */
    @Test
    void 답을_못_들으면_새_키를_만들지_않고_계속_같은_키로_묻는다() {
        createSubscription();
        willThrow(new PgTimeoutException("PG 무응답")).given(delegate).approve(any(), anyLong());

        scheduler.runBillingDue(TODAY);
        for (int round = 1; round <= 5; round++) {
            scheduler.runBillingDue(TODAY.plusDays(30L * round));
        }

        long attemptsOnFirstCycle = num("""
                SELECT count(*) FROM payment
                 WHERE billing_cycle_id = (SELECT min(id) FROM billing_cycle)
                """);
        String firstCycleStatus = str("SELECT status FROM billing_cycle ORDER BY cycle_no LIMIT 1");

        System.out.printf("[실측] 6라운드 무응답 → 1주기의 시도 행 = %d (상태 %s) — 새 키가 안 늘었다%n",
                attemptsOnFirstCycle, firstCycleStatus);

        assertThat(attemptsOnFirstCycle).isEqualTo(1);      // 계속 같은 시도를 묻는다
        assertThat(firstCycleStatus).isEqualTo("PENDING");  // 결말이 없으니 주기도 미결
    }

    // =====================================================================
    // §2 PG 에 기대는 것 — 같은 키면 한 번만 과금된다
    //
    // 아래 스텁은 우리가 심은 가정이다. 이 절의 단언은 PG 가 아니라
    // "그 계약이 참이라면 우리 설계가 이중 청구를 안 낸다" 를 보인다.
    // =====================================================================

    /**
     * 멱등키를 존중하는 PG — 같은 키에 두 번째 요청이 오면 <b>과금하지 않고</b> 첫 결과를 돌려준다.
     * 실제 PG 의 멱등키 계약을 흉내낸 것이다.
     */
    private static final class IdempotentPg implements PgClient {
        private final Map<UUID, PgResult> results = new ConcurrentHashMap<>();
        private final AtomicInteger charged = new AtomicInteger();
        private final AtomicInteger received = new AtomicInteger();
        private volatile PgResult next = PgResult.APPROVED;

        /** 켜면 <b>과금은 하고 응답만 잃는다</b> — 이 창이 이중 청구의 실제 발생지다. */
        private volatile boolean loseResponse = false;

        @Override
        public PgResult approve(UUID merchantPaymentId, long amount) {
            received.incrementAndGet();

            PgResult known = results.get(merchantPaymentId);
            if (known != null) {
                return known;   // ★ 같은 키 = 재전송. 과금을 안 늘리고 원래 결과를 돌려준다
            }

            charged.incrementAndGet();      // 새 키일 때만 돈이 움직인다
            results.put(merchantPaymentId, next);
            if (loseResponse) {
                throw new PgTimeoutException("과금은 됐고 응답만 잃었다 mpid=" + merchantPaymentId);
            }
            return next;
        }

        @Override
        public PgRefundResult refund(long refundId, long amount) {
            return new PgRefundResult.Approved("pgr-" + refundId);
        }
    }

    /**
     * ★ 이중 청구가 실제로 나는 창을 그대로 재현한다: <b>PG 는 과금했고 응답만 잃었다.</b>
     *
     * <pre>
     *   원 청구      새 키 → 거절            과금 1
     *   재시도       새 키 → 과금 후 응답 유실 → 우리 쪽 UNKNOWN   과금 2
     *   재전송       같은 키 → 원래 결과(승인) 반환                 과금 그대로 2
     * </pre>
     *
     * W5D3 대로 새 키를 만들었다면 과금이 <b>3</b> 이 된다. 그 1 이 이중 청구다.
     *
     * <p><b>이 단언이 무엇에 기대는지</b>: 위 스텁의 <i>"같은 키면 과금 안 함"</i> 은 우리가
     * 심은 가정이다. 이 테스트가 세우는 건 <b>PG 가 멱등키 계약을 지킨다면 우리 설계는
     * 이중 청구를 안 낸다</b> 까지고, PG 가 실제로 그런지는 우리 계기 밖이다.
     */
    @Test
    void 멱등_PG_라면_재전송은_과금을_안_늘린다() {
        IdempotentPg pg = new IdempotentPg();
        org.mockito.BDDMockito.willAnswer(inv -> pg.approve(inv.getArgument(0), inv.getArgument(1)))
                .given(delegate).approve(any(), anyLong());

        createSubscription();

        pg.next = PgResult.DECLINED;
        scheduler.runBillingDue(TODAY);                          // 과금 1 (거절)
        OffsetDateTime t0 = OffsetDateTime.now();

        pg.next = PgResult.APPROVED;
        pg.loseResponse = true;
        scheduler.runRetryDue(t0.plusDays(1).plusMinutes(1));     // 과금 2, 응답 유실 → UNKNOWN
        int chargedAfterLoss = pg.charged.get();
        String statusAfterLoss = str("SELECT status FROM payment WHERE status = 'UNKNOWN'");

        pg.loseResponse = false;
        scheduler.runRetryDue(t0.plusDays(1).plusMinutes(30));    // 리스 만료 → 같은 키 재전송

        long distinctKeys = num("SELECT count(DISTINCT merchant_payment_id) FROM payment");
        String cycleStatus = str("SELECT status FROM billing_cycle");

        System.out.printf("[실측·가정] 멱등 PG → 요청 %d회 · 실제 과금 %d회 · 우리가 낸 키 %d개%n",
                pg.received.get(), pg.charged.get(), distinctKeys);
        System.out.printf("[실측·가정] 응답 유실 직후 시도=%s(과금 %d) → 재전송 후 주기=%s%n",
                statusAfterLoss, chargedAfterLoss, cycleStatus);

        assertThat(statusAfterLoss).isEqualTo("UNKNOWN");
        assertThat(pg.received.get()).isEqualTo(3);      // 요청은 3번 갔다
        assertThat(pg.charged.get()).isEqualTo(2);       // ★ 과금은 2번 — 새 키였다면 3번
        assertThat(distinctKeys).isEqualTo(2);
        assertThat(cycleStatus).isEqualTo("PAID");       // 잃었던 승인을 재전송이 회수했다
    }

    // ---------- helpers ----------

    private void createSubscription() {
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            Seller seller = new Seller("구독셀러", 500);
            em.persist(seller);
            em.persist(new Subscription(seller, AMOUNT, 30, TODAY));
            em.flush();
        });
    }

    private String str(String sql) {
        List<?> rows = em.createNativeQuery(sql).getResultList();
        return rows.isEmpty() || rows.get(0) == null ? null : rows.get(0).toString();
    }

    private long num(String sql) {
        Object v = em.createNativeQuery(sql).getSingleResult();
        return v == null ? 0 : ((Number) v).longValue();
    }
}
