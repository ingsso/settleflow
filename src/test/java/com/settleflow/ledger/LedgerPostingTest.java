package com.settleflow.ledger;

import com.settleflow.ledger.repository.JournalRepository;
import com.settleflow.order.service.OrderService;
import com.settleflow.payment.service.PaymentTransitionService;
import com.settleflow.product.domain.Product;
import com.settleflow.seller.domain.Seller;
import com.settleflow.support.IntegrationTestSupport;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * W2D2 실측 — 결제 확정 시 셀러별 이중장부 분개가 자동으로, 그리고 <b>한 번만</b> 생기는가.
 *
 * <p>배선은 {@code PaymentTransitionService} 의 TRANSITIONED 부수효과다. 여기선 그 결과
 * (journal·ledger_entry 행)를 실제 DB 에서 읽어 확인한다 — "컴파일된다"가 아니라 "돈이 맞게 적혔다".
 */
class LedgerPostingTest extends IntegrationTestSupport {

    @Autowired OrderService orderService;
    @Autowired PaymentTransitionService transitions;
    @Autowired JournalRepository journalRepository;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager txManager;

    TransactionTemplate tx() {
        return new TransactionTemplate(txManager);
    }

    // ---------- 1. 단일 셀러 — 3줄 분개, 균형, 금액 ----------

    @Test
    void 결제_확정시_셀러_한명이면_3줄_분개가_균형있게_생긴다() {
        long productId = createProduct("셀러A", 500, 10_000L, 5);   // 10,000 × 2 = 20,000

        orderService.place(Map.of(productId, 2));

        long journals = journalRepository.count();
        long entries = count("SELECT count(*) FROM ledger_entry");
        long debit = sum("SELECT coalesce(sum(amount),0) FROM ledger_entry WHERE dr_cr = 'DEBIT'");
        long credit = sum("SELECT coalesce(sum(amount),0) FROM ledger_entry WHERE dr_cr = 'CREDIT'");
        long cash = sum("SELECT amount FROM ledger_entry WHERE account = 'CASH'");
        long payable = sum("SELECT amount FROM ledger_entry WHERE account = 'SELLER_PAYABLE'");
        long fee = sum("SELECT amount FROM ledger_entry WHERE account = 'FEE_REVENUE'");
        long cashSellerNull = count("SELECT count(*) FROM ledger_entry WHERE account = 'CASH' AND seller_id IS NULL");

        System.out.printf("[실측] 단일셀러 → journal=%d entries=%d 차변=%d 대변=%d | 현금=%d 미지급금=%d 수수료=%d%n",
                journals, entries, debit, credit, cash, payable, fee);

        assertThat(journals).isEqualTo(1);
        assertThat(entries).isEqualTo(3);
        assertThat(debit).isEqualTo(credit);          // 균형
        assertThat(cash).isEqualTo(20_000L);
        assertThat(payable).isEqualTo(19_000L);        // 20,000 - 5%
        assertThat(fee).isEqualTo(1_000L);
        assertThat(cashSellerNull).isEqualTo(1);       // 현금 줄엔 셀러 없음
    }

    // ---------- 2. 다중 셀러 — 2N+1 줄 ----------

    @Test
    void 셀러가_둘이면_5줄_분개가_생기고_균형이_유지된다() {
        long p1 = createProduct("셀러A", 500, 12_000L, 5);
        long p2 = createProduct("셀러B", 500, 8_000L, 5);

        orderService.place(Map.of(p1, 1, p2, 1));   // 12,000 + 8,000 = 20,000

        long entries = count("SELECT count(*) FROM ledger_entry");
        long payableRows = count("SELECT count(*) FROM ledger_entry WHERE account = 'SELLER_PAYABLE'");
        long feeRows = count("SELECT count(*) FROM ledger_entry WHERE account = 'FEE_REVENUE'");
        long debit = sum("SELECT coalesce(sum(amount),0) FROM ledger_entry WHERE dr_cr = 'DEBIT'");
        long credit = sum("SELECT coalesce(sum(amount),0) FROM ledger_entry WHERE dr_cr = 'CREDIT'");

        System.out.printf("[실측] 다중셀러 → entries=%d 미지급금줄=%d 수수료줄=%d 차변=%d 대변=%d%n",
                entries, payableRows, feeRows, debit, credit);

        assertThat(entries).isEqualTo(5);              // 2N+1, N=2
        assertThat(payableRows).isEqualTo(2);
        assertThat(feeRows).isEqualTo(2);
        assertThat(debit).isEqualTo(credit).isEqualTo(20_000L);
    }

    // ---------- 3. 이중계상 방어 — 중복 승인 20회에도 journal 1개 ----------

    @Test
    void 같은_결제에_승인이_20번_확정돼도_journal은_하나만_생긴다() {
        long productId = createProduct("셀러A", 500, 10_000L, 5);
        OrderService.PlacedOrder placed = orderService.place(Map.of(productId, 2));   // 이미 PAID + journal 1
        UUID mpid = placed.merchantPaymentId();

        for (int i = 0; i < 20; i++) {
            transitions.applyApproved(mpid, "pg-tx-1");   // 이미 PAID → IGNORED, 부수효과 없음
        }

        long journals = journalRepository.count();
        long entries = count("SELECT count(*) FROM ledger_entry");

        System.out.printf("[실측] 중복승인20 → journal=%d entries=%d (기대 1 / 3)%n", journals, entries);

        assertThat(journals).isEqualTo(1);   // W2D1 §4 — TRANSITIONED 1회에만 분개
        assertThat(entries).isEqualTo(3);
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

    long count(String sql) {
        return ((Number) em.createNativeQuery(sql).getSingleResult()).longValue();
    }

    long sum(String sql) {
        return ((Number) em.createNativeQuery(sql).getSingleResult()).longValue();
    }
}
