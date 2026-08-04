package com.settleflow.observability;

import com.settleflow.order.service.OrderService;
import com.settleflow.product.domain.Product;
import com.settleflow.reconciliation.repository.ReconciliationRunRepository;
import com.settleflow.reconciliation.service.ReconciliationRunner;
import com.settleflow.seller.domain.Seller;
import com.settleflow.settlement.service.SettlementRunner;
import com.settleflow.settlement.service.SettlementService;
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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * W4D2 실측 — 정산·대사 배치 지표가 <b>실제 스크레이프 응답에서</b> 맞는 값을 낸다.
 *
 * <h3>왜 레지스트리를 직접 안 읽고 HTTP 로 긁는가</h3>
 * {@code MeterRegistry.get(...).gauge().value()} 로 재면 <b>등록됐다</b>까지만 증명된다.
 * 오늘 {@code ObservabilityConfig} 에서 겪은 게 정확히 그 틈이다 — 회로 메트릭은 레지스트리에
 * 있을 법한데 <b>노출 경로에서 빠져 있었다</b>. 밖에서 보이는 것이 관측이므로 밖에서 잰다.
 *
 * <h3>이 클래스의 축 — 값이 아니라 <b>안 오르는 것</b>이다</h3>
 * 케이스 ①이 오늘의 핵심 판단을 직접 겨눈다: 같은 불일치를 다시 대사해도 게이지가 안 오른다.
 * Counter 였다면 2 가 됐을 자리에서 1 이 나와야 한다 — <b>재대사는 새 불일치가 아니라 같은
 * 불일치를 다시 본 것</b>이고, 그래서 <i>관측이 아니라 대상</i>을 센다.
 */
class SettlementMetricsTest extends IntegrationTestSupport {

    private static final String UNRESOLVED = "settleflow_reconciliation_unresolved_discrepancies";
    private static final String LAST_SUCCESS = "settleflow_reconciliation_last_success_timestamp_seconds";
    private static final String BATCH_DURATION = "settleflow_settlement_batch_duration_seconds";
    private static final String BATCH_DATES = "settleflow_settlement_batch_dates";
    private static final String DATE_DURATION_COUNT = "settleflow_settlement_date_duration_seconds_count";
    private static final String DATE_DURATION_SUM = "settleflow_settlement_date_duration_seconds_sum";

    @LocalServerPort int port;
    @Autowired OrderService orderService;
    @Autowired SettlementService settlementService;
    @Autowired SettlementRunner settlementRunner;
    @Autowired ReconciliationRunner reconciliationRunner;
    @Autowired ReconciliationRunRepository runRepository;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager txManager;

    final HttpClient http = HttpClient.newHttpClient();
    final LocalDate today = LocalDate.now(SettlementService.ZONE);

    // ---------- ① ★ 재대사해도 안 오른다 — Counter 를 안 쓴 판단의 직접 증거 ----------

    @Test
    void 같은_불일치를_다시_대사해도_미해소_게이지는_안_오른다() {
        seedOneDiscrepancy();

        reconciliationRunner.run(List.of(today));           // 1차 — 불일치 1건
        double after1 = metric(UNRESOLVED);

        reconciliationRunner.run(List.of());               // 2차 — 출처 ②가 같은 날짜를 다시 올린다
        double after2 = metric(UNRESOLVED);

        long runs = runRepository.findBySettlementDateOrderByIdDesc(today).size();
        System.out.printf("[실측] 재대사 → run=%d건 관측=누적 / 게이지 %.0f → %.0f "
                        + "(Counter 였다면 %.0f)%n", runs, after1, after2, after1 + after2);

        assertThat(runs).isEqualTo(2);                     // 관측은 두 번 쌓였다
        assertThat(after1).isEqualTo(1.0);
        assertThat(after2).isEqualTo(1.0);                 // ★ 그런데 수위는 그대로다
    }

    // ---------- ② 해소되면 내려간다 — 게이지의 존재 이유 ----------

    @Test
    void 해소되면_미해소_게이지가_내려간다() {
        seedOneDiscrepancy();
        reconciliationRunner.run(List.of(today));
        double before = metric(UNRESOLVED);

        settlementService.settle(today);                   // 재실행이 메운다(W3D3 §5)
        reconciliationRunner.run(List.of());

        double after = metric(UNRESOLVED);
        System.out.printf("[실측] 해소 → 게이지 %.0f → %.0f (Counter 였다면 내려갈 수 없다)%n",
                before, after);

        assertThat(before).isEqualTo(1.0);
        assertThat(after).isZero();
    }

    // ---------- ③ 소요시간 A·B 와 날짜 수 N ----------

    /**
     * A ÷ N ≈ B 가 닫히는지 본다. N 이 없으면 A 의 변화가 <i>밀려서</i>인지 <i>느려져서</i>인지
     * 가를 수 없다 — 그래서 셋을 같이 낸다.
     */
    @Test
    void 배치가_돌면_소요시간과_날짜수가_찬다() {
        // Timer 는 단조 누적이고 레지스트리는 컨텍스트에 붙어 테스트 간에 살아남는다
        // (W4D1 이 회로를 @BeforeEach 로 reset 한 것과 같은 자리). 절대값이 아니라 델타로 잰다 —
        // Timer 에는 reset 이 없으므로 되돌리는 대신 기준선을 뜬다.
        double countBefore = metric(DATE_DURATION_COUNT);
        double sumBefore = metric(DATE_DURATION_SUM);

        Fixture a = createProduct("셀러A", 500, 10_000L, 5);
        orderService.place(Map.of(a.productId(), 1));
        pushTodaysJournalsTo(today.minusDays(1));           // 어제 + 오늘 → 밀린 날짜 2개
        orderService.place(Map.of(a.productId(), 1));

        List<LocalDate> settled = settlementRunner.runPending();

        double batch = metric(BATCH_DURATION);             // A 는 게이지라 매번 덮어써진다
        double dates = metric(BATCH_DATES);
        double count = metric(DATE_DURATION_COUNT) - countBefore;
        double sum = metric(DATE_DURATION_SUM) - sumBefore;
        System.out.printf("[실측] 배치 → 날짜=%d건 A=%.3fs N=%.0f B_count=%.0f B_sum=%.3fs "
                        + "(A÷N=%.3fs, B평균=%.3fs)%n",
                settled.size(), batch, dates, count, sum, batch / dates, sum / count);

        assertThat(settled).hasSize(2);                    // 어제 + 오늘
        assertThat(dates).isEqualTo(settled.size());
        assertThat(count).isEqualTo(settled.size());       // 날짜마다 한 번씩 쟀다
        assertThat(batch).isPositive();
        assertThat(batch).isGreaterThanOrEqualTo(sum);     // ★ A 는 B 들의 합을 포함한다
    }

    /** 밀린 날짜가 0 건이어도 배치는 "돌았다"를 남긴다 — N=0 과 "안 돌았다"를 가르는 자리. */
    @Test
    void 밀린_날짜가_없어도_배치_지표는_기록된다() {
        List<LocalDate> settled = settlementRunner.runPending();

        double batch = metric(BATCH_DURATION);
        double dates = metric(BATCH_DATES);
        System.out.printf("[실측] 빈 배치 → 정산=%d건 A=%.4fs N=%.0f (NaN 아님 = 돌긴 했다)%n",
                settled.size(), batch, dates);

        assertThat(settled).isEmpty();
        assertThat(dates).isZero();
        assertThat(batch).isNotNaN();                      // ★ "안 돌았다"(NaN)와 갈린다
    }

    // ---------- ④ 마지막 성공 시각 — 배치 중단을 잡는 유일한 지표 ----------

    @Test
    void 대사가_돌기_전엔_마지막_성공_시각이_없고_돌면_채워진다() {
        double before = metric(LAST_SUCCESS);

        reconciliationRunner.run(List.of());
        double after = metric(LAST_SUCCESS);

        long nowEpoch = System.currentTimeMillis() / 1000;
        System.out.printf("[실측] 마지막 성공 시각 → %s → %.0f (now=%d, 차이=%.0fs)%n",
                Double.isNaN(before) ? "NaN(한 번도 안 돎)" : String.valueOf(before),
                after, nowEpoch, nowEpoch - after);

        assertThat(before).isNaN();                        // ★ 0 이 아니다 — 0 은 1970년이라 즉시 오경보
        assertThat(after).isNotNaN();
        assertThat(nowEpoch - after).isLessThan(120);      // 방금 돈 것으로 보인다
    }

    // ---------- helpers ----------

    /** 스크레이프 응답에서 라벨 없는 메트릭 한 줄의 값을 뽑는다. 없으면 실패시킨다. */
    private double metric(String name) {
        String body = scrape();
        return body.lines()
                .filter(l -> l.startsWith(name + " "))
                .map(l -> Double.parseDouble(l.substring(name.length() + 1).trim()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        name + " 가 /actuator/prometheus 응답에 없다 — 등록은 됐는데 노출이 안 됐을 수 있다"));
    }

    private String scrape() {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/actuator/prometheus"))
                .GET()
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            return response.body();
        } catch (Exception e) {
            throw new IllegalStateException("스크레이프 실패", e);
        }
    }

    /**
     * 오늘 날짜에 불일치 1건을 만든다 — {@code DiscrepancyFlagTest} 케이스 ②와 같은 모양.
     * 배치가 본 세계(9,500)와 그 뒤 커밋된 세계(19,000)가 어긋난다.
     */
    private void seedOneDiscrepancy() {
        Fixture a = createProduct("셀러A", 500, 10_000L, 5);
        orderService.place(Map.of(a.productId(), 1));
        settlementService.settle(today);
        orderService.place(Map.of(a.productId(), 1));
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(txManager);
    }

    /** {@code DiscrepancyFlagTest} 와 같은 예외 — 테스트가 장부 시각을 미는 자리. */
    private void pushTodaysJournalsTo(LocalDate target) {
        int days = (int) java.time.temporal.ChronoUnit.DAYS.between(target, today);
        var todayStart = today.atStartOfDay(SettlementService.ZONE).toOffsetDateTime();
        tx().executeWithoutResult(s -> em.createNativeQuery("""
                        UPDATE journal
                           SET created_at = created_at - make_interval(days => :days)
                         WHERE created_at >= :from
                        """)
                .setParameter("days", days)
                .setParameter("from", todayStart)
                .executeUpdate());
    }

    private Fixture createProduct(String sellerName, int feeRateBp, long price, int stock) {
        return tx().execute(s -> {
            Seller seller = new Seller(sellerName, feeRateBp);
            em.persist(seller);
            Product product = new Product(seller, "상품", price, stock);
            em.persist(product);
            em.flush();
            return new Fixture(product.getId(), seller.getId());
        });
    }

    private record Fixture(Long productId, Long sellerId) {}
}
