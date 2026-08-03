package com.settleflow.reconciliation;

import com.settleflow.order.service.OrderService;
import com.settleflow.product.domain.Product;
import com.settleflow.reconciliation.domain.DiscrepancyObservation;
import com.settleflow.reconciliation.domain.DiscrepancyType;
import com.settleflow.reconciliation.domain.ReconciliationRun;
import com.settleflow.reconciliation.repository.ReconciliationRunRepository;
import com.settleflow.reconciliation.service.ReconciliationRunner;
import com.settleflow.seller.domain.Seller;
import com.settleflow.settlement.service.SettlementService;
import com.settleflow.support.IntegrationTestSupport;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * W3D5 ① 실측 — <b>불일치 자동 플래그</b>(계획서의 "불일치 건 자동 플래그").
 *
 * <h3>겨누는 것</h3>
 * 저장하는 값어치가 <i>"지금 어긋나 있다"</i> 가 아니라 <b>"며칠째 안 메워진다"</b> 에 있다는
 * 설계. W3D5 ② 이후 {@code MISSING_IN_SETTLEMENT} 는 대체로 일시적이라(다음 배치가 줍는다)
 * 한 번 떴다는 사실은 신호가 약하다.
 *
 * <h3>상태 대신 관측을 쌓는다 — 그래서 실행 기록이 필요하다</h3>
 * 해소를 {@code resolved_at} UPDATE 로 표현하지 않는다(W2D1 §5 append-only). 해소는
 * <b>다음 관측에 안 나타남</b>인데, 그것만으로는 <i>깨끗했다</i> 와 <i>안 봤다</i> 를 못 가른다.
 * {@code reconciliation_run} 이 부재에 뜻을 준다 — 케이스 ①이 그걸 직접 겨눈다.
 *
 * <h3>단언 순서</h3>
 * 이 클래스의 축은 <b>기록</b>이다. run·관측 단언이 금액 단언보다 먼저다(W3D4 §4-5).
 */
class DiscrepancyFlagTest extends IntegrationTestSupport {

    @Autowired OrderService orderService;
    @Autowired SettlementService settlementService;
    @Autowired ReconciliationRunner reconciliationRunner;
    @Autowired ReconciliationRunRepository runRepository;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager txManager;

    final LocalDate today = LocalDate.now(SettlementService.ZONE);

    // ---------- 1. 깨끗해도 run 은 남는다 — 부재에 뜻을 주는 게 이 기록의 존재 이유 ----------

    @Test
    void 불일치가_0건이어도_실행_기록은_남는다() {
        Fixture a = createProduct("셀러A", 500, 10_000L, 5);
        orderService.place(Map.of(a.productId(), 1));
        settlementService.settle(today);                       // 정상 — 어긋날 게 없다

        reconciliationRunner.run(List.of(today));

        List<ReconciliationRun> runs = runRepository.findBySettlementDateOrderByIdDesc(today);
        System.out.printf("[실측] 깨끗한 대사 → run=%d건 관측=%d건 미해소날짜=%s%n",
                runs.size(), observationsOf(today), runRepository.findUnresolvedDates());

        assertThat(runs).hasSize(1);                           // ★ 깨끗해도 행이 남는다
        assertThat(runs.getFirst().isClean()).isTrue();
        assertThat(observationsOf(today)).isZero();
        assertThat(runRepository.findUnresolvedDates()).isEmpty();
    }

    // ---------- 2. 불일치가 나면 관측이 쌓이고 미해소 날짜로 올라온다 ----------

    @Test
    void 불일치가_나면_관측이_쌓이고_미해소_날짜가_된다() {
        Fixture a = createProduct("셀러A", 500, 10_000L, 5);
        orderService.place(Map.of(a.productId(), 1));
        settlementService.settle(today);                       // 배치가 본 세계 = 9,500
        orderService.place(Map.of(a.productId(), 1));          // 배치 뒤에 커밋 = 재구성은 19,000

        reconciliationRunner.run(List.of(today));

        List<DiscrepancyObservation> obs = observations(today);
        System.out.printf("[실측] 불일치 → 관측=%d건 type=%s 차액=%d 미해소날짜=%s%n",
                obs.size(), obs.getFirst().getType(), obs.getFirst().getDifference(),
                runRepository.findUnresolvedDates());

        assertThat(obs).hasSize(1);
        assertThat(runRepository.findUnresolvedDates()).containsExactly(today);   // ★ 스스로 살아남는다
        assertThat(obs.getFirst().getType()).isEqualTo(DiscrepancyType.AMOUNT_MISMATCH);
        assertThat(obs.getFirst().getSettled()).isEqualTo(9_500L);
        assertThat(obs.getFirst().getReconstructed()).isEqualTo(19_000L);
        assertThat(obs.getFirst().getDifference()).isEqualTo(-9_500L);            // 음수 = 덜 잡았다
    }

    // ---------- 3. 해소 — 다음 관측에 안 나타나고, 옛 관측은 안 지워진다 ----------

    /**
     * 해소된 뒤 <b>정산 날짜를 안 넘겨도</b> 대사가 그 날짜를 다시 본다는 게 핵심이다 —
     * 날짜 출처 ②(미해소 목록)가 실제로 일을 한다는 뜻이다. 이게 없으면 "며칠째"가 1 에서
     * 멈추고 해소도 영영 안 찍힌다.
     */
    @Test
    void 해소되면_다음_run이_깨끗해지고_옛_관측은_남는다() {
        Fixture a = createProduct("셀러A", 500, 10_000L, 5);
        orderService.place(Map.of(a.productId(), 1));
        settlementService.settle(today);
        orderService.place(Map.of(a.productId(), 1));
        reconciliationRunner.run(List.of(today));              // 1차 — 불일치 1건

        settlementService.settle(today);                       // 재실행이 메운다(W3D3 §5)
        List<LocalDate> targets = reconciliationRunner.run(List.of());   // ★ 정산 날짜를 안 넘긴다

        List<ReconciliationRun> runs = runRepository.findBySettlementDateOrderByIdDesc(today);
        System.out.printf("[실측] 해소 → 대사대상=%s run=%d건 최신깨끗=%s 누적관측=%d건 미해소=%s%n",
                targets, runs.size(), runs.getFirst().isClean(), observationsOf(today),
                runRepository.findUnresolvedDates());

        assertThat(targets).contains(today);                   // ★ 출처 ② 가 today 를 다시 올렸다
        assertThat(runs).hasSize(2);
        assertThat(runs.getFirst().isClean()).isTrue();        // 최신 run 은 깨끗
        assertThat(observationsOf(today)).isEqualTo(1);        // ★ 옛 관측은 안 지워진다(append-only)
        assertThat(runRepository.findUnresolvedDates()).isEmpty();
    }

    // ---------- 4. ★ 최근 창 — 아무도 안 돈 날을 잡는다(상관 실패 차단) ----------

    /**
     * 정산이 <b>한 번도 안 돈</b> 날짜를 대사가 스스로 찾아낸다.
     *
     * <p>날짜 출처를 ①(정산이 돈 날짜)만 두면 대사가 <b>감시 대상에게서 볼 목록을 물려받는</b>
     * 꼴이 된다. {@code trg_journal_settlement_pending} 이 죽으면 정산 대상이 0 건이 되고,
     * 대사도 볼 게 없어 <b>불일치 0 건 = 초록불</b>을 낸다 — 모든 장부가 조용히 증발하는 동안.
     * 여기서 정산을 한 번도 안 부르는 게 그 상황의 재현이고, 출처 ③ 이 그걸 끊는다.
     */
    @Test
    void 정산이_한_번도_안_돈_날짜를_최근_창이_잡는다() {
        Fixture a = createProduct("셀러A", 500, 10_000L, 5);
        orderService.place(Map.of(a.productId(), 1));
        pushTodaysJournalsTo(today.minusDays(2));              // 이틀 전 일 — 최근 7일 안

        // 정산을 한 번도 안 부른다. 넘기는 날짜도 없다.
        List<LocalDate> targets = reconciliationRunner.run(List.of());

        List<DiscrepancyObservation> obs = observations(today.minusDays(2));
        System.out.printf("[실측] 최근 창 → 대사대상=%d건 관측=%d건 type=%s 재구성=%s%n",
                targets.size(), obs.size(),
                obs.isEmpty() ? "-" : obs.getFirst().getType(),
                obs.isEmpty() ? "-" : obs.getFirst().getReconstructed());

        assertThat(targets).contains(today.minusDays(2));      // ★ 아무도 안 알려줬는데 봤다
        assertThat(obs).hasSize(1);
        assertThat(obs.getFirst().getType()).isEqualTo(DiscrepancyType.MISSING_IN_SETTLEMENT);
        assertThat(obs.getFirst().getSettled()).isNull();
        assertThat(obs.getFirst().getReconstructed()).isEqualTo(9_500L);
        assertThat(runRepository.findUnresolvedDates()).containsExactly(today.minusDays(2));
    }

    // ---------- helpers ----------

    TransactionTemplate tx() {
        return new TransactionTemplate(txManager);
    }

    List<DiscrepancyObservation> observations(LocalDate date) {
        return tx().execute(s -> em.createQuery("""
                        SELECT o FROM DiscrepancyObservation o
                         WHERE o.run.settlementDate = :date
                         ORDER BY o.id
                        """, DiscrepancyObservation.class)
                .setParameter("date", date)
                .getResultList());
    }

    long observationsOf(LocalDate date) {
        return observations(date).size();
    }

    /** {@code SettlementConcurrencyTest} 와 같은 예외 — 테스트가 장부 시각을 미는 유일한 자리. */
    void pushTodaysJournalsTo(LocalDate target) {
        int days = (int) java.time.temporal.ChronoUnit.DAYS.between(target, today);
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
