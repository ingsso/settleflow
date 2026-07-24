package com.settleflow.ledger;

import com.settleflow.order.service.OrderService;
import com.settleflow.payment.domain.RefundOutcome;
import com.settleflow.payment.domain.RefundResult;
import com.settleflow.payment.repository.PaymentRepository;
import com.settleflow.payment.service.RefundService;
import com.settleflow.product.domain.Product;
import com.settleflow.seller.domain.Seller;
import com.settleflow.support.IntegrationTestSupport;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * W2D5 실측 — 환불 역분개(reversing entry).
 *
 * <p>확인하는 것 네 축:
 * <ol>
 *   <li><b>모양</b> — {@code SELLER_PAYABLE} 차변 N 줄 + {@code CASH} 대변 1 줄이고
 *       {@code FEE_REVENUE} 는 안 건드리는가(W2D1 정책: 환불분 수수료는 셀러 부담)</li>
 *   <li><b>안분</b> — 셀러가 여럿일 때 결제 subtotal 비례로 갈리는가</li>
 *   <li><b>반올림</b> — 나눠떨어지지 않는 금액에서도 균형이 성립하는가.
 *       W2D2 §5 → W2D3 → W2D4 로 <b>세 번 이월된 {@code .5} 경로</b>가 여기서 걸린다</li>
 *   <li><b>주말 체크포인트</b> — 부분환불 3 번 반복해도 장부 불변식이 안 깨지는가</li>
 * </ol>
 */
class RefundLedgerTest extends IntegrationTestSupport {

    @Autowired OrderService orderService;
    @Autowired PaymentRepository paymentRepository;
    @Autowired RefundService refundService;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager txManager;

    TransactionTemplate tx() {
        return new TransactionTemplate(txManager);
    }

    // ---------- 1. 모양 — 2줄, 수수료 미접촉 ----------

    @Test
    void 부분환불은_미지급금_차변과_현금_대변_두줄짜리_역분개를_만든다() {
        long productId = createProduct("셀러A", 500, 10_000L, 5);
        Long paymentId = paidOrder(Map.of(productId, 2));            // 20,000

        RefundOutcome result = refundService.requestRefund(paymentId, 5_000L);

        long refundJournals = count("SELECT count(*) FROM journal WHERE type = 'REFUND'");
        long lines = count(refundEntries("count(*)"));
        long cash = sum(refundEntries("coalesce(sum(e.amount),0)") + " AND e.account = 'CASH'");
        long payable = sum(refundEntries("coalesce(sum(e.amount),0)") + " AND e.account = 'SELLER_PAYABLE'");
        long feeRows = count(refundEntries("count(*)") + " AND e.account = 'FEE_REVENUE'");
        long cashCredit = count(refundEntries("count(*)") + " AND e.account = 'CASH' AND e.dr_cr = 'CREDIT'");
        long payableDebit = count(refundEntries("count(*)") + " AND e.account = 'SELLER_PAYABLE' AND e.dr_cr = 'DEBIT'");

        System.out.printf("[실측] 5,000 부분환불 → REFUND journal=%d 줄=%d | 현금대변=%d 미지급금차변=%d 수수료줄=%d%n",
                refundJournals, lines, cash, payable, feeRows);

        assertThat(result).isInstanceOf(RefundOutcome.Accepted.class);
        assertThat(refundJournals).isEqualTo(1);
        assertThat(lines).isEqualTo(2);
        assertThat(cash).isEqualTo(5_000L);
        assertThat(payable).isEqualTo(5_000L);
        assertThat(feeRows).isZero();          // 수수료는 환급하지 않는다(W2D1)
        assertThat(cashCredit).isEqualTo(1);
        assertThat(payableDebit).isEqualTo(1);
        assertThat(allJournalsBalanced()).isTrue();
    }

    @Test
    void refund행은_자기_역분개를_가리킨다() {
        long productId = createProduct("셀러A", 500, 10_000L, 5);
        Long paymentId = paidOrder(Map.of(productId, 2));

        refundService.requestRefund(paymentId, 5_000L);

        long linked = count("SELECT count(*) FROM refund r JOIN journal j ON j.id = r.journal_id"
                + " WHERE j.type = 'REFUND'");
        long orphan = count("SELECT count(*) FROM refund WHERE journal_id IS NULL");

        System.out.printf("[실측] refund→journal 연결=%d / journal 없는 refund=%d%n", linked, orphan);

        assertThat(linked).isEqualTo(1);
        assertThat(orphan).isZero();   // W2D4 까지는 전부 null 이었다
    }

    // ---------- 2. 안분 — 셀러가 둘이면 결제 비율대로 갈린다 ----------

    @Test
    void 셀러가_둘이면_환불액이_결제_비율대로_안분된다() {
        long p1 = createProduct("셀러A", 500, 12_000L, 5);
        long p2 = createProduct("셀러B", 500, 8_000L, 5);
        Long paymentId = paidOrder(Map.of(p1, 1, p2, 1));            // 12,000 + 8,000 = 20,000

        refundService.requestRefund(paymentId, 5_000L);              // 6:4 → 3,000 / 2,000

        List<Long> portions = refundPortionsBySellerId();
        long cash = sum(refundEntries("coalesce(sum(e.amount),0)") + " AND e.account = 'CASH'");

        System.out.printf("[실측] 12,000/8,000 에 5,000 환불 → 안분 %s | 합=%d 현금대변=%d%n",
                portions, portions.stream().mapToLong(Long::longValue).sum(), cash);

        assertThat(portions).containsExactly(3_000L, 2_000L);        // sellerId 오름차순
        assertThat(portions.stream().mapToLong(Long::longValue).sum()).isEqualTo(cash);
        assertThat(allJournalsBalanced()).isTrue();
    }

    // ---------- 3. 반올림 — 세 번 이월된 .5 경로 ----------

    @Test
    void 나눠떨어지지_않는_환불도_균형이_깨지지_않는다() {
        long p1 = createProduct("셀러A", 500, 10_000L, 5);
        long p2 = createProduct("셀러B", 500, 10_000L, 5);
        Long paymentId = paidOrder(Map.of(p1, 1, p2, 1));            // 10,000 + 10,000 = 20,000

        // 5,001 을 5:5 로 → 각 2,500.5. 둘 다 반올림하면 5,002(초과), 둘 다 버리면 5,000(부족).
        // 마지막 셀러를 뺄셈으로 구하므로 합은 정의상 5,001 이다.
        refundService.requestRefund(paymentId, 5_001L);

        List<Long> portions = refundPortionsBySellerId();
        long cash = sum(refundEntries("coalesce(sum(e.amount),0)") + " AND e.account = 'CASH'");
        long payable = sum(refundEntries("coalesce(sum(e.amount),0)") + " AND e.account = 'SELLER_PAYABLE'");

        System.out.printf("[실측] .5 경로 — 10,000/10,000 에 5,001 환불 → 안분 %s | 차변합=%d 현금대변=%d%n",
                portions, payable, cash);

        assertThat(portions).containsExactly(2_501L, 2_500L);   // 앞은 반올림, 마지막은 뺄셈
        assertThat(payable).isEqualTo(5_001L).isEqualTo(cash);  // 균형 — 반올림 방식과 무관
        assertThat(allJournalsBalanced()).isTrue();
    }

    @Test
    void 홀수_비율_환불에서도_안분합이_환불액과_정확히_같다() {
        long p1 = createProduct("셀러A", 500, 7_000L, 5);
        long p2 = createProduct("셀러B", 500, 6_000L, 5);
        long p3 = createProduct("셀러C", 500, 4_000L, 5);
        Long paymentId = paidOrder(Map.of(p1, 1, p2, 1, p3, 1));     // 17,000

        refundService.requestRefund(paymentId, 3_333L);              // 어느 셀러도 안 떨어진다

        List<Long> portions = refundPortionsBySellerId();
        long payable = sum(refundEntries("coalesce(sum(e.amount),0)") + " AND e.account = 'SELLER_PAYABLE'");

        System.out.printf("[실측] 7,000/6,000/4,000 에 3,333 환불 → 안분 %s 합=%d%n", portions, payable);

        assertThat(portions).hasSize(3);
        assertThat(payable).isEqualTo(3_333L);
        assertThat(allJournalsBalanced()).isTrue();
    }

    // ---------- 4. 주말 체크포인트 — 부분환불 3번 ----------

    @Test
    void 부분환불_3번을_반복해도_장부_불변식이_깨지지_않는다() {
        long productId = createProduct("셀러A", 500, 10_000L, 5);
        Long paymentId = paidOrder(Map.of(productId, 2));            // 20,000

        refundService.requestRefund(paymentId, 8_000L);
        refundService.requestRefund(paymentId, 7_000L);
        refundService.requestRefund(paymentId, 5_000L);              // 누적 20,000 = 전액

        long journals = count("SELECT count(*) FROM journal");
        long refundJournals = count("SELECT count(*) FROM journal WHERE type = 'REFUND'");
        long debit = sum("SELECT coalesce(sum(amount),0) FROM ledger_entry WHERE dr_cr = 'DEBIT'");
        long credit = sum("SELECT coalesce(sum(amount),0) FROM ledger_entry WHERE dr_cr = 'CREDIT'");
        long netPayable = sum("SELECT coalesce(sum(CASE WHEN dr_cr = 'CREDIT' THEN amount ELSE -amount END),0)"
                + " FROM ledger_entry WHERE account = 'SELLER_PAYABLE'");
        long netFee = sum("SELECT coalesce(sum(CASE WHEN dr_cr = 'CREDIT' THEN amount ELSE -amount END),0)"
                + " FROM ledger_entry WHERE account = 'FEE_REVENUE'");

        System.out.printf("[실측] 부분환불 3회 → journal=%d(REFUND %d) 차변=%d 대변=%d | 미지급금순액=%d 수수료순액=%d%n",
                journals, refundJournals, debit, credit, netPayable, netFee);

        assertThat(journals).isEqualTo(4);            // PAYMENT 1 + REFUND 3
        assertThat(refundJournals).isEqualTo(3);      // 환불엔 멱등이 없다 — 3건이면 3개(W2D4 §1)
        assertThat(debit).isEqualTo(credit);          // 전체 불변식
        assertThat(allJournalsBalanced()).isTrue();   // 사건 단위 불변식

        // 전액환불했는데 수수료는 환급하지 않았다 → 미지급금이 음수(셀러가 우리에게 1,000 빚짐).
        // 버그가 아니라 정책의 귀결이다. W3 정산에서 다음 회차와 상계된다.
        assertThat(netPayable).isEqualTo(-1_000L);
        assertThat(netFee).isEqualTo(1_000L);         // 이미 번 수수료는 그대로 남는다
    }

    @Test
    void 거부된_환불은_역분개도_만들지_않는다() {
        long productId = createProduct("셀러A", 500, 10_000L, 5);
        Long paymentId = paidOrder(Map.of(productId, 2));            // 20,000

        RefundOutcome over = refundService.requestRefund(paymentId, 20_001L);

        long refundJournals = count("SELECT count(*) FROM journal WHERE type = 'REFUND'");
        long refunds = count("SELECT count(*) FROM refund");

        System.out.printf("[실측] 잔액초과 환불 → %s | REFUND journal=%d refund행=%d%n",
                over, refundJournals, refunds);

        assertThat(over).isEqualTo(new RefundOutcome.Rejected(RefundResult.EXCEEDS_REMAINING));
        assertThat(refundJournals).isZero();   // 판정이 UPDATE 한 문장이라 그 앞에서 끊긴다
        assertThat(refunds).isZero();
    }

    // ---------- helpers ----------

    long createProduct(String sellerName, int feeRateBp, long price, int stock) {
        return tx().execute(s -> {
            Seller seller = new Seller(sellerName, feeRateBp);
            em.persist(seller);
            Product product = new Product(seller, "상품", price, stock);
            em.persist(product);
            em.flush();
            return product.getId();
        });
    }

    Long paidOrder(Map<Long, Integer> quantities) {
        OrderService.PlacedOrder placed = orderService.place(quantities);
        return paymentRepository.findByMerchantPaymentId(placed.merchantPaymentId())
                .orElseThrow().getId();
    }

    /** REFUND journal 의 줄만 고르는 공통 FROM/WHERE. */
    String refundEntries(String select) {
        return "SELECT " + select + " FROM ledger_entry e JOIN journal j ON j.id = e.journal_id"
                + " WHERE j.type = 'REFUND'";
    }

    /** 역분개의 셀러별 미지급금 차변 — sellerId 오름차순(= 안분 순서). */
    @SuppressWarnings("unchecked")
    List<Long> refundPortionsBySellerId() {
        List<Number> rows = em.createNativeQuery(
                        refundEntries("e.amount") + " AND e.account = 'SELLER_PAYABLE' ORDER BY e.seller_id")
                .getResultList();
        return rows.stream().map(Number::longValue).toList();
    }

    /** 사건 단위 불변식 — 모든 journal 에서 Σ차변 == Σ대변 인가. */
    boolean allJournalsBalanced() {
        return count("SELECT count(*) FROM ("
                + "  SELECT j.id FROM journal j JOIN ledger_entry e ON e.journal_id = j.id"
                + "  GROUP BY j.id"
                + "  HAVING sum(CASE WHEN e.dr_cr = 'DEBIT' THEN e.amount ELSE -e.amount END) <> 0"
                + ") x") == 0;
    }

    long count(String sql) {
        return ((Number) em.createNativeQuery(sql).getSingleResult()).longValue();
    }

    long sum(String sql) {
        return ((Number) em.createNativeQuery(sql).getSingleResult()).longValue();
    }
}
