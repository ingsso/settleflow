package com.settleflow.reconciliation;

import com.settleflow.order.service.OrderService;
import com.settleflow.payment.domain.RefundOutcome;
import com.settleflow.payment.repository.PaymentRepository;
import com.settleflow.payment.service.RefundConfirmService;
import com.settleflow.payment.service.RefundService;
import com.settleflow.product.domain.Product;
import com.settleflow.reconciliation.domain.Discrepancy;
import com.settleflow.reconciliation.domain.DiscrepancyType;
import com.settleflow.reconciliation.service.ReconciliationService;
import com.settleflow.seller.domain.Seller;
import com.settleflow.settlement.repository.SettlementRepository;
import com.settleflow.settlement.service.SettlementService;
import com.settleflow.support.IntegrationTestSupport;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * W3D4 실측 — 대사(내부 축: 장부 ↔ 정산 스냅샷).
 *
 * <p>확인하는 것 넷:
 * <ol>
 *   <li><b>맞으면 조용하다</b> — 정상 경로에서 불일치가 0 건인가. 대사가 거짓 경보를 내면
 *       진짜 불일치가 그 속에 묻힌다</li>
 *   <li><b>안분이 껴도 조용하다</b> — {@code allocateRefund} 의 복사가 {@code LedgerService} 와
 *       1 원까지 같은가. 여기서 어긋나면 매일 거짓 경보가 뜬다</li>
 *   <li><b>증발을 잡는가</b> — W3D3 §4 에서 알고 뚫은 구멍(배치 뒤에 커밋된 줄)을 실제로 집어내는가.
 *       그리고 재실행이 그걸 메우는가(W3D3 §5 덮어쓰기가 복구 수단이라는 주장의 연장)</li>
 *   <li><b>세 갈래를 구분하는가</b> — 금액 다름 / 정산에 없음 / 재구성에 없음</li>
 * </ol>
 *
 * <p><b>여기서 검증되지 않는 것</b>: 날짜 경계 로직(대사가 journal 에서 시각을 빌리므로 배치와
 * 공유한다)과 환불 안분 규칙 자체(의도적 복사). 둘 다 {@link ReconciliationService} javadoc 에
 * 이유가 적혀 있다.
 *
 * <h3>단언 순서 규약 — 대사 단언이 먼저다</h3>
 * AssertJ 는 첫 실패에서 멈춘다. 그래서 정산 값 단언({@code netOf})을 대사 단언({@code found})보다
 * 앞에 두면, 정산이 틀린 순간 <b>대사 단언에 도달하지 못하고</b> "대사가 무엇을 봤나"가 기록에서
 * 사라진다. W3D4 음성대조군에서 실제로 그렇게 됐다 — 대사가 2 건을 잡았다는 사실이 단언이 아니라
 * {@code [실측]} printf 에만 남았다. <b>이 클래스가 겨누는 축의 단언을 맨 위에 둔다.</b>
 */
class ReconciliationTest extends IntegrationTestSupport {

    @Autowired OrderService orderService;
    @Autowired PaymentRepository paymentRepository;
    @Autowired RefundService refundService;
    @Autowired RefundConfirmService refundConfirmService;
    @Autowired SettlementService settlementService;
    @Autowired SettlementRepository settlementRepository;
    @Autowired ReconciliationService reconciliationService;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager txManager;

    final LocalDate today = LocalDate.now(SettlementService.ZONE);

    // ---------- 1. 맞으면 조용하다 ----------

    @Test
    void 정상_경로에서는_불일치가_없다() {
        Fixture a = createProduct("셀러A", 500, 12_000L, 5);
        Fixture b = createProduct("셀러B", 500, 8_000L, 5);
        orderService.place(Map.of(a.productId(), 1, b.productId(), 1));

        settlementService.settle(today);
        List<Discrepancy> found = reconciliationService.reconcileSettlement(today);

        System.out.printf("[실측] 정상 경로 → 불일치=%d건 %s%n", found.size(), found);

        // 장부에서 집계한 값과 payment+refund 에서 재구성한 값이 같다.
        // 둘은 완전히 다른 경로로 계산됐다 — 이게 일치해야 대사가 신호로 쓸 수 있다.
        assertThat(found).isEmpty();
    }

    // ---------- 2. 안분이 껴도 조용하다 ----------

    @Test
    void 여러_셀러_부분환불의_안분까지_1원까지_일치한다() {
        Fixture a = createProduct("셀러A", 500, 12_000L, 5);
        Fixture b = createProduct("셀러B", 500, 8_000L, 5);
        Long paymentId = paidOrder(Map.of(a.productId(), 1, b.productId(), 1));   // 20,000

        refundAndConfirm(paymentId, 5_000L);   // 안분: A 3,000 / B 2,000 (비례 + 마지막은 뺄셈)

        settlementService.settle(today);
        List<Discrepancy> found = reconciliationService.reconcileSettlement(today);

        System.out.printf("[실측] 안분 낀 부분환불 → A net=%d B net=%d 불일치=%d건 %s%n",
                netOf(a.sellerId()), netOf(b.sellerId()), found.size(), found);

        // ★ 대사 단언이 먼저다. 이 클래스가 겨누는 게 대사이므로, 무언가 틀렸을 때 여기서 죽어야
        //   "대사가 뭘 봤나"가 실패 메시지에 남는다. 아래 netOf 를 먼저 두면 정산 단언에서 죽어
        //   대사 단언에 도달조차 못 한다(W3D4 음성대조군에서 실제로 그렇게 됐다 — 대사가 2건을
        //   잡았다는 사실이 단언이 아니라 위 printf 에만 남았다).
        //   반올림이 1 원이라도 어긋나면 여기가 깨진다 — 그래서 안분식을 복사했다.
        assertThat(found).isEmpty();
        assertThat(netOf(a.sellerId())).isEqualTo(8_400L);    // 11,400 − 3,000
        assertThat(netOf(b.sellerId())).isEqualTo(5_600L);    //  7,600 − 2,000
    }

    // ---------- 3. 증발 — W3D3 §4 에서 뚫은 구멍을 잡는다 ----------

    @Test
    void 배치_뒤에_들어온_장부_줄을_잡아내고_재실행이_메운다() {
        Fixture a = createProduct("셀러A", 500, 10_000L, 5);
        orderService.place(Map.of(a.productId(), 1));      // payable 9,500

        settlementService.settle(today);                   // 여기까지가 배치가 본 세계

        orderService.place(Map.of(a.productId(), 1));      // 배치 뒤에 커밋된 줄 = 증발분

        List<Discrepancy> found = reconciliationService.reconcileSettlement(today);
        System.out.printf("[실측] 증발분 → 불일치=%d건 %s%n", found.size(), found);

        assertThat(found).hasSize(1);
        Discrepancy d = found.get(0);
        assertThat(d.type()).isEqualTo(DiscrepancyType.AMOUNT_MISMATCH);
        assertThat(d.settled()).isEqualTo(9_500L);          // 배치가 굳힌 값
        assertThat(d.reconstructed()).isEqualTo(19_000L);   // 실제로 있었던 값
        assertThat(d.difference()).isEqualTo(-9_500L);      // 음수 = 정산이 <b>덜</b> 잡았다

        // W3D3 §5: 덮어쓰기를 고른 덕에 재실행이 복구 수단이 된다. 대사가 그걸 확인해준다.
        settlementService.settle(today);
        List<Discrepancy> after = reconciliationService.reconcileSettlement(today);
        System.out.printf("[실측] 재실행 후 → 불일치=%d건%n", after.size());
        assertThat(after).isEmpty();
    }

    // ---------- 4. 세 갈래 구분 ----------

    @Test
    void 배치를_안_돌린_날은_MISSING_IN_SETTLEMENT() {
        Fixture a = createProduct("셀러A", 500, 10_000L, 5);
        orderService.place(Map.of(a.productId(), 1));
        // settle 을 부르지 않는다

        List<Discrepancy> found = reconciliationService.reconcileSettlement(today);
        System.out.printf("[실측] 배치 미실행 → %s%n", found);

        assertThat(found).hasSize(1);
        assertThat(found.get(0).type()).isEqualTo(DiscrepancyType.MISSING_IN_SETTLEMENT);
        assertThat(found.get(0).settled()).isNull();
        assertThat(found.get(0).reconstructed()).isEqualTo(9_500L);
        assertThat(found.get(0).difference()).isEqualTo(-9_500L);
    }

    @Test
    void 정산액이_손으로_고쳐지면_AMOUNT_MISMATCH_와_차액이_나온다() {
        Fixture a = createProduct("셀러A", 500, 10_000L, 5);
        orderService.place(Map.of(a.productId(), 1));
        settlementService.settle(today);

        // 스냅샷만 조작한다 — 장부는 그대로. 정산 테이블이 append-only 가 아니라서 가능한 일이고
        // (W3D3 §5 덮어쓰기의 대가), 그래서 이걸 볼 사람이 필요하다.
        tx().executeWithoutResult(s -> em.createNativeQuery(
                "UPDATE settlement SET net_amount = 99999 WHERE seller_id = " + a.sellerId())
                .executeUpdate());

        List<Discrepancy> found = reconciliationService.reconcileSettlement(today);
        System.out.printf("[실측] 스냅샷 조작 → %s%n", found);

        assertThat(found).hasSize(1);
        assertThat(found.get(0).type()).isEqualTo(DiscrepancyType.AMOUNT_MISMATCH);
        assertThat(found.get(0).difference()).isEqualTo(99_999L - 9_500L);   // 양수 = 과지급 방향
    }

    @Test
    void 거래가_없는_셀러의_정산행은_MISSING_IN_RECONSTRUCTION() {
        Fixture ghost = createProduct("거래없는셀러", 500, 10_000L, 5);   // 주문을 안 만든다

        tx().executeWithoutResult(s -> em.createNativeQuery(
                "INSERT INTO settlement (seller_id, settlement_date, net_amount) VALUES ("
                        + ghost.sellerId() + ", '" + today + "', 7000)")
                .executeUpdate());

        List<Discrepancy> found = reconciliationService.reconcileSettlement(today);
        System.out.printf("[실측] 유령 정산행 → %s%n", found);

        assertThat(found).hasSize(1);
        assertThat(found.get(0).type()).isEqualTo(DiscrepancyType.MISSING_IN_RECONSTRUCTION);
        assertThat(found.get(0).reconstructed()).isNull();
        assertThat(found.get(0).difference()).isEqualTo(7_000L);
    }

    // ---------- helpers ----------

    TransactionTemplate tx() {
        return new TransactionTemplate(txManager);
    }

    long netOf(long sellerId) {
        return settlementRepository.findBySettlementDateAndSellerId(today, sellerId)
                .orElseThrow(() -> new AssertionError("정산 행 없음 sellerId=" + sellerId))
                .getNetAmount();
    }

    RefundOutcome refundAndConfirm(Long paymentId, long amount) {
        RefundOutcome outcome = refundService.requestRefund(paymentId, amount);
        if (outcome instanceof RefundOutcome.Accepted a) {
            refundConfirmService.confirm(a.refund().getId());
        }
        return outcome;
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

    Long paidOrder(Map<Long, Integer> quantities) {
        OrderService.PlacedOrder placed = orderService.place(quantities);
        return paymentRepository.findByMerchantPaymentId(placed.merchantPaymentId())
                .orElseThrow().getId();
    }

    record Fixture(Long productId, Long sellerId) {}
}
