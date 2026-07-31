package com.settleflow.settlement;

import com.settleflow.order.service.OrderService;
import com.settleflow.product.domain.Product;
import com.settleflow.seller.domain.Seller;
import com.settleflow.settlement.repository.SettlementPendingRepository;
import com.settleflow.settlement.repository.SettlementRepository;
import com.settleflow.settlement.service.SettlementRunner;
import com.settleflow.settlement.service.SettlementService;
import com.settleflow.support.IntegrationTestSupport;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * W3D5 실측 — <b>배치 중 유입되는 장부 줄이 증발하지 않는가</b>(계획서의 "동시성 테스트").
 *
 * <h3>겨누는 것</h3>
 * W3D3 §4 가 뚫고 갚기로 한 구멍. {@code journal.created_at} 은 persist 때 찍히고 가시성은 커밋
 * 때 생기므로, 경계 직전에 찍히고 배치 뒤에 커밋된 줄은 <b>어느 정산에도 안 들어간다</b>.
 * V10 이 진행 축({@code settlement_pending})을 분리해 그걸 닫았고, 이 클래스가 실측한다.
 *
 * <h3>두 가지를 따로 증명한다 — 서로 다른 걸 잡는다</h3>
 * <table border="1">
 *   <tr><th></th><th>재현하는 것</th><th>증명하는 것</th></tr>
 *   <tr><td><b>①② 상태</b></td>
 *       <td>배치가 지나간 <b>뒤에</b> 그 날짜의 장부가 커밋된 상태</td>
 *       <td>늦게 온 줄이 <b>제 날짜로</b> 주워진다 = W3D5 의 목표 그 자체</td></tr>
 *   <tr><td><b>③ 타이밍</b></td>
 *       <td>배치의 <b>집계와 표식 삭제 사이</b>에 다른 커넥션이 커밋</td>
 *       <td>둘이 <b>같은 스냅샷</b>을 본다 = {@code REPEATABLE READ} 선택의 검증</td></tr>
 * </table>
 *
 * <p>①②만 있으면 격리 수준을 {@code READ COMMITTED} 로 되돌려도 <b>테스트가 안 깨진다</b>
 * (그 구멍은 밀리초짜리라 순차 실행에선 안 밟힌다). ③만 있으면 오늘의 목표 자체를 직접
 * 안 겨눈다. 그래서 둘 다 있다.
 *
 * <h3>단언 순서 — W3D4 §4-5 의 규약</h3>
 * AssertJ 는 첫 실패에서 멈추므로 <b>클래스가 겨누는 축의 단언을 맨 위에 둔다</b>. 이 클래스의
 * 축은 <i>표식</i>이므로 pending 단언이 금액 단언보다 먼저다. W3D4 에서 정산 단언이 먼저 터져
 * 대사 단언이 실행조차 안 된 적이 있다.
 */
class SettlementConcurrencyTest extends IntegrationTestSupport {

    @Autowired OrderService orderService;
    @Autowired SettlementService settlementService;
    @Autowired SettlementRunner settlementRunner;
    @Autowired SettlementRepository settlementRepository;
    @Autowired SettlementPendingRepository pendingRepository;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager txManager;

    final LocalDate today = LocalDate.now(SettlementService.ZONE);

    // ---------- 1. 진입점 — 날짜를 안 알려줬는데 밀린 날짜를 찾아낸다 ----------

    @Test
    void 배치는_날짜를_계산하지_않고_안_센_날짜를_물어본다() {
        Fixture a = createProduct("셀러A", 500, 10_000L, 5);
        orderService.place(Map.of(a.productId(), 1));      // payable 9,500
        pushTodaysJournalsTo(today.minusDays(3));          // 사흘 전 일로 만든다

        List<LocalDate> pending = settlementService.pendingDates();
        List<LocalDate> dates = settlementRunner.runPending();

        System.out.printf("[실측] 밀린 날짜=%s 처리=%s → 정산행=%s%n", pending, dates,
                settlementRepository.findAll().stream()
                        .map(r -> r.getSettlementDate() + ":" + r.getNetAmount()).toList());

        assertThat(pending).containsExactly(today.minusDays(3));
        assertThat(pendingRepository.count()).isZero();          // 처리했으니 표식이 사라진다
        assertThat(dates).containsExactly(today.minusDays(3));
        assertThat(netOf(today.minusDays(3), a.sellerId())).isEqualTo(9_500L);
    }

    // ---------- 2. ★ 상태 — 배치가 지나간 뒤 커밋된 줄이 제 날짜로 주워진다 ----------

    /**
     * W3D3 의 {@code settleYesterday()} 였으면 <b>이 줄은 영영 증발한다</b>. 그 진입점은 매번
     * "어제"만 돌므로, 사흘 전 날짜의 장부가 지금 커밋되면 그 날짜를 다시 부르는 사람이 없다.
     *
     * <p>여기서 확인하는 건 두 가지다 — (1) 그 날짜가 <b>다시 돌고</b>, (2) 늦게 온 줄이
     * 주워진 날 정산이 아니라 <b>제 날짜 정산</b>에 들어간다("26일자 매출"의 뜻이 안 흐려진다).
     */
    @Test
    void 배치_이후_커밋된_사흘전_장부가_제_날짜로_주워진다() {
        Fixture a = createProduct("셀러A", 500, 10_000L, 5);
        LocalDate d = today.minusDays(3);

        orderService.place(Map.of(a.productId(), 1));      // A
        pushTodaysJournalsTo(d);
        settlementRunner.runPending();
        long first = netOf(d, a.sellerId());

        // 배치가 지나간 뒤에 그 날짜의 장부가 하나 더 커밋된다.
        // (실제로는 경계 직전에 시작돼 배치 뒤에 커밋된 트랜잭션이 이 모양이다)
        orderService.place(Map.of(a.productId(), 1));      // B — 지금 커밋, 귀속은 사흘 전
        pushTodaysJournalsTo(d);

        List<LocalDate> pending = settlementService.pendingDates();
        settlementRunner.runPending();
        long second = netOf(d, a.sellerId());

        System.out.printf("[실측] 늦게 온 줄 → 밀린 날짜=%s 1차=%d 2차=%d 정산행=%d%n",
                pending, first, second, settlementRepository.findAll().size());

        assertThat(pending).containsExactly(d);              // ★ 사흘 전이 다시 목록에 뜬다
        assertThat(pendingRepository.count()).isZero();
        assertThat(first).isEqualTo(9_500L);
        assertThat(second).isEqualTo(19_000L);               // 증발하지 않았다
        assertThat(settlementRepository.findAll()).hasSize(1);   // 오늘자 행이 생기지 않았다
    }

    // ---------- 3. ★ 타이밍 — 집계와 표식 삭제가 같은 스냅샷을 본다 ----------

    /**
     * 배치 트랜잭션 <b>안</b>에서 다른 커넥션이 커밋하는 상황을 실제로 만든다.
     *
     * <p>프로덕션 코드에 테스트용 훅을 넣지 않으려고, {@code settle()} 이 하는 두 문장을 테스트가
     * 직접 같은 격리 수준의 트랜잭션에서 부른다. <b>SQL 은 전부 프로덕션 것</b>이고 순서도 같다 —
     * 다른 건 그 사이에 커밋을 끼워 넣을 수 있다는 것뿐이다.
     *
     * <p>기대: 새로 커밋된 줄은 <b>집계에도 안 들어가고 표식도 안 지워진다</b>. 둘이 짝이 맞아야
     * 한다 — 한쪽만 일어나면 그게 증발이다.
     */
    @Test
    void 집계와_표식삭제_사이에_커밋된_줄은_표식이_살아남는다() {
        Fixture a = createProduct("셀러A", 500, 10_000L, 5);
        orderService.place(Map.of(a.productId(), 1));      // A — 배치가 보게 될 줄

        OffsetDateTime from = today.atStartOfDay(SettlementService.ZONE).toOffsetDateTime();
        OffsetDateTime to = today.plusDays(1).atStartOfDay(SettlementService.ZONE).toOffsetDateTime();

        TransactionTemplate rr = new TransactionTemplate(txManager);
        rr.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);

        rr.executeWithoutResult(s -> {
            // ① 집계 — REPEATABLE READ 의 스냅샷이 이 첫 문장에서 뜬다
            settlementRepository.upsertDailyNetBySeller(today, from, to);

            // ② 그 사이에 다른 커넥션이 같은 날짜의 장부를 커밋한다
            commitInOtherConnection(() -> orderService.place(Map.of(a.productId(), 1)));   // B

            // ③ 표식 삭제 — ①과 같은 스냅샷을 봐야 한다. READ COMMITTED 였으면 여기서 B 의
            //    표식까지 지워지고, B 는 집계엔 안 들어갔으므로 그대로 증발한다.
            pendingRepository.clearSettled(from, to);
        });

        long duringBatch = netOf(today, a.sellerId());
        long survivors = pendingRepository.count();

        settlementRunner.runPending();                      // 다음 배치가 줍는다
        long afterRerun = netOf(today, a.sellerId());

        System.out.printf("[실측] 스냅샷 → 살아남은 표식=%d 배치직후=%d 재실행후=%d%n",
                survivors, duringBatch, afterRerun);

        // 이 클래스의 축은 표식이다 — 금액보다 먼저 단언한다(W3D4 §4-5)
        assertThat(survivors).isEqualTo(1);                 // ★ B 의 표식이 살아남았다
        assertThat(duringBatch).isEqualTo(9_500L);          // B 는 집계에 안 들어갔다 (짝이 맞는다)
        assertThat(afterRerun).isEqualTo(19_000L);          // 다음 배치가 메웠다
        assertThat(pendingRepository.count()).isZero();
        assertThat(settlementRepository.findAll()).hasSize(1);
    }

    // ---------- helpers ----------

    TransactionTemplate tx() {
        return new TransactionTemplate(txManager);
    }

    long netOf(LocalDate date, long sellerId) {
        return settlementRepository.findBySettlementDateAndSellerId(date, sellerId)
                .orElseThrow(() -> new AssertionError("정산 행 없음 date=" + date + " sellerId=" + sellerId))
                .getNetAmount();
    }

    /**
     * <b>오늘 찍힌</b> journal 만 목표 날짜로 민다.
     *
     * <p>{@code SettlementBatchTest.pushPaymentJournalsToYesterday()} 와 같은 예외 —
     * 테스트가 장부를 UPDATE 하는 자리이고 append-only 규율의 예외다(프로덕션 경로엔 없다).
     * 다른 점은 <b>이미 민 줄을 또 밀지 않도록</b> 범위를 오늘로 한정한다는 것. 그래야 "1차 배치
     * 뒤에 도착한 줄"만 골라서 옮길 수 있다.
     *
     * <p><b>정직하게</b>: 이건 커밋 가시성을 진짜로 재현하는 게 아니라 {@code created_at} 을 손으로
     * 과거로 찍는 <b>시뮬레이션</b>이다. 진짜 가시성은 케이스 ③이 본다.
     */
    void pushTodaysJournalsTo(LocalDate target) {
        int days = (int) ChronoUnit.DAYS.between(target, today);
        OffsetDateTime todayStart = today.atStartOfDay(SettlementService.ZONE).toOffsetDateTime();
        tx().executeWithoutResult(s -> em.createNativeQuery("""
                        UPDATE journal
                           SET created_at = created_at - make_interval(days => :days)
                         WHERE created_at >= :from
                        """)
                .setParameter("days", days)
                .setParameter("from", todayStart)
                .executeUpdate());
    }

    /**
     * 다른 스레드 = <b>다른 커넥션 = 다른 트랜잭션</b>에서 실행하고 커밋될 때까지 기다린다.
     *
     * <p>스프링 트랜잭션은 스레드에 묶이므로(ThreadLocal), 여기서 도는 {@code @Transactional}
     * 서비스는 바깥의 RR 트랜잭션과 무관한 자기 트랜잭션을 열고 <b>독립적으로 커밋</b>한다.
     * 그게 이 케이스가 필요로 하는 전부다.
     */
    void commitInOtherConnection(Runnable action) {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            pool.submit(action).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("다른 커넥션의 커밋 실패", e);
        } finally {
            pool.shutdownNow();
        }
    }

    Fixture createProduct(String sellerName, int feeRateBp, long price, int stock) {
        return tx().execute(s -> {
            Seller seller = new Seller(sellerName, feeRateBp);
            em.persist(seller);
            Product product = new Product(seller, "상품", price, stock);
            em.persist(product);
            em.flush();
            return new Fixture(product.getId(), seller.getId());
        });
    }

    record Fixture(Long productId, Long sellerId) {}
}
