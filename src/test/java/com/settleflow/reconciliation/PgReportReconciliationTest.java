package com.settleflow.reconciliation;

import com.settleflow.order.service.OrderService;
import com.settleflow.payment.domain.RefundOutcome;
import com.settleflow.payment.repository.PaymentRepository;
import com.settleflow.payment.service.RefundConfirmService;
import com.settleflow.payment.service.RefundService;
import com.settleflow.product.domain.Product;
import com.settleflow.reconciliation.domain.PgDiscrepancy;
import com.settleflow.reconciliation.domain.PgDiscrepancyType;
import com.settleflow.reconciliation.pg.PgReportCsv;
import com.settleflow.reconciliation.pg.PgReportGenerator;
import com.settleflow.reconciliation.pg.PgReportRow;
import com.settleflow.reconciliation.pg.PgRowType;
import com.settleflow.reconciliation.service.ReconciliationService;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * W3D4 실측 — 대사(외부 축: 우리 기록 ↔ PG 정산 리포트).
 *
 * <h3>이 테스트가 증명하는 것과 증명하지 않는 것</h3>
 * <b>"정상 경로 0 건"은 아무것도 증명하지 않는다.</b> Mock 리포트가 우리 DB 에서 나오므로 같을 수밖에
 * 없다({@link PgReportGenerator} javadoc). 진짜 PG 가 없는 이상 이 동어반복은 피할 수 없다.
 *
 * <p>그래서 <b>값은 심은 불일치를 잡느냐에 있다</b> — 계획서 주말 체크포인트(<i>"의도적으로 불일치
 * 3건 심어두고 정확히 3건을 잡아내는지"</i>)가 이 클래스의 3번 케이스다.
 *
 * <p>단언 순서 규약은 {@link ReconciliationTest} 와 같다 — 이 클래스가 겨누는 축의 단언이 맨 위.
 */
class PgReportReconciliationTest extends IntegrationTestSupport {

    @Autowired OrderService orderService;
    @Autowired PaymentRepository paymentRepository;
    @Autowired RefundService refundService;
    @Autowired RefundConfirmService refundConfirmService;
    @Autowired PgReportGenerator generator;
    @Autowired ReconciliationService reconciliationService;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager txManager;

    final LocalDate today = LocalDate.now(SettlementService.ZONE);

    // ---------- 1. 기준선 ----------

    @Test
    void 리포트가_우리_기록과_같으면_불일치가_없다() {
        세팅();

        List<PgReportRow> report = generator.generate(today);
        List<PgDiscrepancy> found = reconciliationService.reconcileWithPgReport(today, report);

        System.out.printf("[실측] 기준선 → 리포트=%d행 불일치=%d건 %s%n", report.size(), found.size(), found);

        // ★ 이건 대사가 옳다는 증명이 아니다 — 리포트가 우리 DB 에서 나왔으니 같은 게 당연하다.
        //   기준선이 0 이어야 심은 불일치의 개수를 셀 수 있다는, 그 용도뿐이다.
        assertThat(found).isEmpty();
        assertThat(report).hasSize(3);   // 결제 2 + 환불 1
    }

    // ---------- 2. CSV 를 실제로 거친다 ----------

    @Test
    void CSV로_쓰고_읽어도_같은_리포트다() {
        세팅();

        String csv = generator.generateCsv(today);
        List<PgReportRow> parsed = PgReportCsv.read(csv);

        System.out.printf("[실측] CSV 왕복%n%s", csv);

        assertThat(parsed).isEqualTo(generator.generate(today));
        assertThat(csv.lines().findFirst()).hasValue(PgReportCsv.HEADER);
        // 동기 승인 경로라 PG 거래번호가 비어 있다 — 그래서 조인 키가 merchant_ref 다.
        assertThat(parsed.stream().filter(r -> r.type() == PgRowType.PAYMENT))
                .allSatisfy(r -> assertThat(r.transactionId()).isNull());
    }

    @Test
    void 헤더가_다른_CSV는_조용히_넘어가지_않고_거부된다() {
        assertThatThrownBy(() -> PgReportCsv.read("type,amount\nPAYMENT,1000\n"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("헤더");

        System.out.println("[실측] 헤더 불일치 CSV → IllegalArgumentException (0건은 침묵하는 실패다)");
    }

    // ---------- 3. 주말 체크포인트 — 3건 심고 3건 잡기 ----------

    @Test
    void 불일치_3건을_심으면_정확히_3건을_잡고_종류도_맞춘다() {
        Setup s = 세팅();

        List<PgReportRow> report = new ArrayList<>(generator.generate(today));

        // ① 웹훅 유실 — PG 는 처리했는데 우리에겐 기록이 없다. 내부 축이 절대 못 잡는 것.
        String 유령 = UUID.randomUUID().toString();
        report.add(new PgReportRow(PgRowType.PAYMENT, "pgtx-ghost", 유령,
                OffsetDateTime.now(), 33_000L, 990L, 32_010L));

        // ② 리포트에서 환불 행을 지운다 — 우리는 환불했다는데 PG 리포트에 없다.
        report.removeIf(r -> r.type() == PgRowType.REFUND);

        // ③ 결제 한 건의 금액을 바꾼다.
        report.replaceAll(r -> r.merchantRef().equals(s.merchantRef2())
                ? new PgReportRow(r.type(), r.transactionId(), r.merchantRef(), r.occurredAt(),
                        9_000L, r.pgFee(), r.netAmount())
                : r);

        // CSV 를 한 번 통과시켜 파일로 받은 상황까지 재현한다.
        List<PgDiscrepancy> found =
                reconciliationService.reconcileWithPgReport(today, PgReportCsv.read(PgReportCsv.write(report)));

        System.out.printf("[실측] 3건 심음 → 잡은 것=%d건%n", found.size());
        found.forEach(d -> System.out.printf("        %s%n", d));

        assertThat(found).hasSize(3);
        assertThat(found).extracting(PgDiscrepancy::kind)
                .containsExactlyInAnyOrder(
                        PgDiscrepancyType.ONLY_IN_REPORT,       // ① 웹훅 유실
                        PgDiscrepancyType.ONLY_IN_INTERNAL,     // ② 환불이 리포트에 없다
                        PgDiscrepancyType.AMOUNT_MISMATCH);     // ③ 금액 다름

        PgDiscrepancy 유실 = kindOf(found, PgDiscrepancyType.ONLY_IN_REPORT);
        assertThat(유실.merchantRef()).isEqualTo(유령);
        assertThat(유실.internal()).isNull();
        assertThat(유실.report()).isEqualTo(33_000L);

        PgDiscrepancy 금액 = kindOf(found, PgDiscrepancyType.AMOUNT_MISMATCH);
        assertThat(금액.difference()).isEqualTo(8_000L - 9_000L);   // 내부 − 리포트

        PgDiscrepancy 환불 = kindOf(found, PgDiscrepancyType.ONLY_IN_INTERNAL);
        assertThat(환불.type()).isEqualTo(PgRowType.REFUND);
        assertThat(환불.report()).isNull();
    }

    // ---------- 4. PG 수수료는 읽지만 안 쓴다 ----------

    @Test
    void PG수수료를_바꿔도_대사는_흔들리지_않는다() {
        세팅();

        // 리포트의 수수료·입금액을 통째로 엉터리로 만든다. gross 만 그대로 두면 대사는 조용해야 한다 —
        // 우리 장부에 PG 수수료 계정이 없어서 비교 대상이 아니기 때문이다(④-나).
        List<PgReportRow> tampered = generator.generate(today).stream()
                .map(r -> new PgReportRow(r.type(), r.transactionId(), r.merchantRef(), r.occurredAt(),
                        r.grossAmount(), 99_999L, -99_999L))
                .toList();

        List<PgDiscrepancy> found = reconciliationService.reconcileWithPgReport(today, tampered);

        System.out.printf("[실측] 수수료·입금액 조작 → 불일치=%d건 (gross 만 본다)%n", found.size());
        assertThat(found).isEmpty();
    }

    // ---------- helpers ----------

    /** 결제 2건(20,000 / 8,000) + 확정 환불 1건(5,000). */
    Setup 세팅() {
        Fixture a = createProduct("셀러A", 500, 10_000L, 5);
        Fixture b = createProduct("셀러B", 500, 8_000L, 5);

        Long p1 = paidOrder(Map.of(a.productId(), 2));   // 20,000
        Long p2 = paidOrder(Map.of(b.productId(), 1));   //  8,000
        refundAndConfirm(p1, 5_000L);

        return new Setup(merchantRefOf(p1), merchantRefOf(p2));
    }

    String merchantRefOf(Long paymentId) {
        return tx().execute(s -> paymentRepository.findById(paymentId).orElseThrow()
                .getMerchantPaymentId().toString());
    }

    static PgDiscrepancy kindOf(List<PgDiscrepancy> found, PgDiscrepancyType kind) {
        return found.stream().filter(d -> d.kind() == kind).findFirst()
                .orElseThrow(() -> new AssertionError("없음: " + kind));
    }

    TransactionTemplate tx() {
        return new TransactionTemplate(txManager);
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

    record Setup(String merchantRef1, String merchantRef2) {}
}
