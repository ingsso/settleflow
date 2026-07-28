package com.settleflow.payment;

import com.settleflow.order.service.OrderService;
import com.settleflow.payment.domain.RefundOutcome;
import com.settleflow.payment.domain.RefundResult;
import com.settleflow.payment.domain.RefundStatus;
import com.settleflow.payment.repository.PaymentRepository;
import com.settleflow.payment.service.RefundConfirmService;
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
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * W2D4 실측 — <b>프로덕션 경로</b>로 동시 환불을 걸어 과다환불이 막히는가.
 *
 * <p><b>W1D2 {@code RefundRaceTest} 와 뭐가 다른가.</b> 그건 테스트가 손으로 만든 임시
 * {@code payment} 테이블에 JDBC 로 직접 UPDATE 를 쐈다 — "이 모양의 SQL 이면 된다"를 증명했지
 * "우리 코드가 그렇게 돼 있다"를 증명하진 않았다. 여기선 {@link RefundService#requestRefund}
 * 를 그대로 부른다. 즉 <b>Spring 트랜잭션 경계·JPA 영속성 컨텍스트·실제 스키마</b>가 다 낀 상태에서
 * 같은 보증이 살아있는지 본다.
 *
 * <p><b>설계 판단 (나)의 실측 승격.</b> "잔액 조회 → 앱에서 판단 → UPDATE"(가) 대신
 * "조건부 UPDATE 의 영향 행 수가 곧 판정"(나)을 골랐다. (가)였다면 10 스레드가 모두 같은 잔액
 * 20,000 을 읽고 전부 통과해 50,000 이 환불됐을 것이다. 그 차이가 아래 숫자로 나와야 한다.
 *
 * <p><b>W3D2 에서 이 테스트의 의미가 커졌다.</b> 역분개를 PG 확인 뒤로 미루면서
 * {@code reserved_refund_amount} 누적은 요청 시점에 <b>예약</b>으로 남겼는데, 여기서 검증하는 게 정확히
 * 그 예약이 여전히 동시성을 막는가다. 이 누적까지 미뤘다면 10 스레드가 전부 통과했을 것이다.
 * 확정 단계까지 태워 <b>예약 4건 = 역분개 4개</b> 로 수렴하는 것도 함께 본다.
 */
class RefundConcurrencyTest extends IntegrationTestSupport {

    @Autowired OrderService orderService;
    @Autowired PaymentRepository paymentRepository;
    @Autowired RefundService refundService;
    @Autowired RefundConfirmService refundConfirmService;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager txManager;

    @Test
    void 동시_환불_10건이_한도를_넘으면_정확히_4건만_통과한다() throws Exception {
        Long paymentId = paidPaymentId();      // 10,000 × 2 = 20,000
        long refundAmt = 5_000L;
        int threads = 10;                      // 10 × 5,000 = 50,000 요청 vs 용량 20,000 → 4건

        CyclicBarrier gate = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        Callable<RefundOutcome> attempt = () -> {
            gate.await();                      // 전원 모이면 동시 발사
            return refundService.requestRefund(paymentId, refundAmt);
        };

        List<Future<RefundOutcome>> futures =
                pool.invokeAll(java.util.Collections.nCopies(threads, attempt));

        RefundOutcome.Rejected exceedsRemaining = new RefundOutcome.Rejected(RefundResult.EXCEEDS_REMAINING);
        int accepted = 0, exceeded = 0;
        for (Future<RefundOutcome> f : futures) {
            RefundOutcome outcome = f.get();
            if (outcome instanceof RefundOutcome.Accepted a) {
                accepted++;
                // Saga 두 번째 다리(W3D2). 소비자가 할 일을 직접 부른다 — Kafka 를 끼우면 타이밍이
                // 비결정적이라 단언이 흔들린다(W3D1 에서 publishBatch() 를 직접 부른 것과 같은 이유).
                refundConfirmService.confirm(a.refund().getId());
            } else if (outcome.equals(exceedsRemaining)) exceeded++;
        }
        pool.shutdown();

        long reserved = reservedRefundAmount(paymentId);
        long completed = completedRefundAmount(paymentId);
        long rows = refundRows();
        long refundJournals = refundJournalCount();
        RefundStatus label = refundStatusOf(paymentId);

        System.out.printf("[실측] 동시환불 %d스레드×%,d원 (한도 20,000) → ACCEPTED=%d EXCEEDS=%d "
                        + "| 예약=%,d 확정=%,d refund행=%d REFUND journal=%d 라벨=%s%n",
                threads, refundAmt, accepted, exceeded, reserved, completed, rows, refundJournals, label);

        assertThat(accepted).isEqualTo(4);              // 20,000 / 5,000
        assertThat(exceeded).isEqualTo(6);
        assertThat(reserved).isEqualTo(20_000L);        // 25,000 이면 lost update — 실패
        // 4건 다 confirm 을 태웠으므로 예약이 전부 확정으로 굳는다. 둘이 갈라지면 그 차가 곧
        // "PG 확인 대기중 + UNKNOWN 보류중"이다(W3D4 대사의 축).
        assertThat(completed).isEqualTo(reserved);
        assertThat(rows).isEqualTo(4);                  // 통과한 만큼만 이력이 남는다
        // W2D5 §7 이월 닫음: 환불엔 멱등이 없어 ACCEPTED 4건이면 역분개도 4개가 정답이다.
        // refund행 4개는 봤지만 journal 이 4개인지는 안 봤었다 — 하나라도 새면 장부와 이력이 드리프트.
        assertThat(refundJournals).isEqualTo(accepted);
        assertThat(label).isEqualTo(RefundStatus.REFUNDED);
    }

    // ---------- helpers ----------

    TransactionTemplate tx() {
        return new TransactionTemplate(txManager);
    }

    Long paidPaymentId() {
        long productId = tx().execute(s -> {
            Seller seller = new Seller("셀러A", 500);
            em.persist(seller);
            Product product = new Product(seller, "상품", 10_000L, 5);
            em.persist(product);
            em.flush();
            return product.getId();
        });
        OrderService.PlacedOrder placed = orderService.place(Map.of(productId, 2));
        return paymentRepository.findByMerchantPaymentId(placed.merchantPaymentId())
                .orElseThrow().getId();
    }

    /** 라벨은 <b>확정</b> 환불액에서 파생한다(W3D4) — 예약이 아니라. */
    RefundStatus refundStatusOf(Long paymentId) {
        return tx().execute(s -> em.find(com.settleflow.payment.domain.Payment.class, paymentId)
                .refundStatus(completedRefundAmount(paymentId)));
    }

    /** 가드가 보는 값(예약 = 확정 + 진행중 + 보류중). W3D4 이전 이름은 {@code refunded_amount}. */
    long reservedRefundAmount(Long paymentId) {
        return ((Number) em.createNativeQuery(
                        "SELECT reserved_refund_amount FROM payment WHERE id = " + paymentId)
                .getSingleResult()).longValue();
    }

    /** 라벨이 보는 값. 이 테스트는 4건 모두 confirm 까지 태우므로 예약과 같아진다. */
    long completedRefundAmount(Long paymentId) {
        return ((Number) em.createNativeQuery(
                        "SELECT COALESCE(SUM(amount), 0) FROM refund"
                                + " WHERE payment_id = " + paymentId + " AND pg_status = 'COMPLETED'")
                .getSingleResult()).longValue();
    }

    long refundRows() {
        return ((Number) em.createNativeQuery("SELECT count(*) FROM refund")
                .getSingleResult()).longValue();
    }

    long refundJournalCount() {
        return ((Number) em.createNativeQuery("SELECT count(*) FROM journal WHERE type = 'REFUND'")
                .getSingleResult()).longValue();
    }
}
