package com.settleflow.settlement;

import com.settleflow.order.service.OrderService;
import com.settleflow.payment.domain.RefundOutcome;
import com.settleflow.payment.repository.PaymentRepository;
import com.settleflow.payment.service.RefundConfirmService;
import com.settleflow.payment.service.RefundService;
import com.settleflow.product.domain.Product;
import com.settleflow.seller.domain.Seller;
import com.settleflow.settlement.domain.Settlement;
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
 * W3D3 실측 — 야간 정산 배치.
 *
 * <p>확인하는 것 다섯 축:
 * <ol>
 *   <li><b>집계</b> — 장부의 {@code SELLER_PAYABLE} 이 셀러별로 하루치 net 이 되는가</li>
 *   <li><b>환불 차감</b> — 역분개(DEBIT)가 별도 로직 없이 자동으로 빠지는가.
 *       장부를 원천으로 고른 것의 실질적 이득이 여기서 확인된다</li>
 *   <li><b>경계</b> — {@code >= from AND < to} 반열림이 실제로 다른 날을 배제하는가</li>
 *   <li><b>멱등·복구</b> — 두 번 돌려도 행이 하나인가, 그리고 <b>재실행이 늦게 도착한 줄을
 *       메우는가</b>(덮어쓰기를 고른 이유)</li>
 *   <li><b>음수</b> — 판 것 없이 환불만 있는 날이 음수로 저장되는가</li>
 * </ol>
 */
class SettlementBatchTest extends IntegrationTestSupport {

    @Autowired OrderService orderService;
    @Autowired PaymentRepository paymentRepository;
    @Autowired RefundService refundService;
    @Autowired RefundConfirmService refundConfirmService;
    @Autowired SettlementService settlementService;
    @Autowired SettlementRepository settlementRepository;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager txManager;

    /** 장부는 지금 시각으로 찍히므로 "오늘"은 정산 존 기준이어야 한다. */
    final LocalDate today = LocalDate.now(SettlementService.ZONE);

    // ---------- 1. 집계 — 셀러별 net ----------

    @Test
    void 하루치_셀러별_미지급금이_net으로_집계된다() {
        Fixture a = createProduct("셀러A", 500, 12_000L, 5);
        Fixture b = createProduct("셀러B", 500, 8_000L, 5);

        orderService.place(Map.of(a.productId(), 1, b.productId(), 1));   // 12,000 + 8,000

        int sellers = settlementService.settle(today);
        List<Settlement> rows = settlementRepository.findBySettlementDateOrderBySellerId(today);

        System.out.printf("[실측] 집계 → 셀러=%d건 %s%n", sellers,
                rows.stream().map(r -> r.getSellerId() + ":" + r.getNetAmount()).toList());

        assertThat(sellers).isEqualTo(2);
        assertThat(rows).hasSize(2);
        assertThat(netOf(a.sellerId())).isEqualTo(11_400L);   // 12,000 − 5%
        assertThat(netOf(b.sellerId())).isEqualTo(7_600L);    //  8,000 − 5%
    }

    // ---------- 2. 환불 차감 — 별도 로직 없이 ----------

    @Test
    void 같은_날_환불은_역분개로_자동_차감된다() {
        Fixture a = createProduct("셀러A", 500, 10_000L, 5);
        Long paymentId = paidOrder(Map.of(a.productId(), 2));   // 20,000 → payable 19,000

        refundAndConfirm(paymentId, 5_000L);                    // 역분개 SELLER_PAYABLE DEBIT 5,000

        settlementService.settle(today);

        long net = netOf(a.sellerId());
        System.out.printf("[실측] 환불 차감 → net=%d (기대 19,000 − 5,000 = 14,000)%n", net);

        // 정산 코드에 "환불"이라는 단어가 한 번도 안 나온다 — 장부를 원천으로 골랐기 때문이다.
        assertThat(net).isEqualTo(14_000L);
    }

    // ---------- 3. 경계 — 반열림이 이웃 날을 배제한다 ----------

    @Test
    void 다른_날짜로_돌리면_오늘_장부는_잡히지_않는다() {
        Fixture a = createProduct("셀러A", 500, 10_000L, 5);
        orderService.place(Map.of(a.productId(), 1));   // created_at = 지금

        int yesterday = settlementService.settle(today.minusDays(1));
        int tomorrow = settlementService.settle(today.plusDays(1));
        int onDay = settlementService.settle(today);

        System.out.printf("[실측] 경계 → 어제=%d 내일=%d 당일=%d%n", yesterday, tomorrow, onDay);

        assertThat(yesterday).isZero();
        assertThat(tomorrow).isZero();
        assertThat(onDay).isEqualTo(1);
        assertThat(settlementRepository.findAll()).hasSize(1);   // 빈 날은 행을 만들지 않는다
    }

    // ---------- 4. 멱등 — 두 번 돌려도 행 하나, 값 그대로 ----------

    @Test
    void 같은_날짜로_두_번_돌려도_행은_하나고_값이_같다() {
        Fixture a = createProduct("셀러A", 500, 10_000L, 5);
        orderService.place(Map.of(a.productId(), 1));   // payable 9,500

        settlementService.settle(today);
        settlementService.settle(today);
        settlementService.settle(today);

        List<Settlement> rows = settlementRepository.findAll();
        System.out.printf("[실측] 3회 실행 → 행=%d net=%d%n", rows.size(), rows.getFirst().getNetAmount());

        assertThat(rows).hasSize(1);            // 누적이었으면 28,500 짜리 3행
        assertThat(rows.getFirst().getNetAmount()).isEqualTo(9_500L);
    }

    // ---------- 5. 복구 — 재실행이 늦게 도착한 줄을 메운다 ----------

    @Test
    void 배치_이후_도착한_장부는_재실행으로_메워진다() {
        Fixture a = createProduct("셀러A", 500, 10_000L, 5);
        orderService.place(Map.of(a.productId(), 1));

        settlementService.settle(today);
        long first = netOf(a.sellerId());

        // 배치가 지나간 뒤에 같은 날짜의 장부가 하나 더 생긴 상황
        // (실제로는 경계 직전에 시작돼 배치 뒤에 커밋된 트랜잭션이 이 모양이다)
        orderService.place(Map.of(a.productId(), 1));

        settlementService.settle(today);
        long second = netOf(a.sellerId());

        System.out.printf("[실측] 복구 → 1차=%d 2차=%d 행=%d%n",
                first, second, settlementRepository.findAll().size());

        assertThat(first).isEqualTo(9_500L);
        assertThat(second).isEqualTo(19_000L);                  // 덮어쓰기라 재실행이 복구가 된다
        assertThat(settlementRepository.findAll()).hasSize(1);   // 누적이 아니다
    }

    // ---------- 6. 음수 — 판 것 없이 환불만 있는 날 ----------

    @Test
    void 판_것_없이_환불만_있는_날은_정산액이_음수다() {
        Fixture a = createProduct("셀러A", 500, 10_000L, 5);
        Long paymentId = paidOrder(Map.of(a.productId(), 2));   // 20,000 → payable 19,000

        pushPaymentJournalsToYesterday();                       // 결제는 어제 일로 만든다
        refundAndConfirm(paymentId, 5_000L);                    // 환불만 오늘

        settlementService.settle(today);

        long net = netOf(a.sellerId());
        System.out.printf("[실측] 환불만 있는 날 → net=%d%n", net);

        // V8 에 CHECK (net_amount > 0) 을 걸었으면 여기서 DataIntegrityViolationException 이 났다.
        // 안 건 게 판단이라는 근거가 이 케이스다.
        assertThat(net).isEqualTo(-5_000L);
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

    /**
     * 결제 journal 만 하루 전으로 민다.
     *
     * <p><b>테스트가 장부를 UPDATE 하는 유일한 곳이고, append-only 규율의 예외다.</b> 어제 팔고
     * 오늘 환불하는 상황은 시계를 건드리지 않고는 한 테스트 안에서 만들 수 없다. 프로덕션 경로엔
     * 이런 UPDATE 가 없다(W2D1 §5).
     */
    void pushPaymentJournalsToYesterday() {
        tx().executeWithoutResult(s -> em.createNativeQuery(
                        "UPDATE journal SET created_at = created_at - INTERVAL '1 day' WHERE type = 'PAYMENT'")
                .executeUpdate());
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
