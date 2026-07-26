package com.settleflow.payment;

import com.settleflow.order.service.OrderService;
import com.settleflow.outbox.service.OutboxRelay;
import com.settleflow.payment.domain.RefundOutcome;
import com.settleflow.payment.domain.RefundPgStatus;
import com.settleflow.payment.repository.PaymentRepository;
import com.settleflow.payment.service.RefundConfirmService;
import com.settleflow.payment.service.RefundService;
import com.settleflow.product.domain.Product;
import com.settleflow.seller.domain.Seller;
import com.settleflow.support.IntegrationTestSupport;
import jakarta.persistence.EntityManager;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * W3D2 실측 — 환불 Saga(consumer + 보상).
 *
 * <p>확인하는 것 다섯 축:
 * <ol>
 *   <li><b>미룸</b> — 요청 시점엔 예약만 잡히고 장부는 비어 있는가</li>
 *   <li><b>승인</b> — PG 확인 뒤에야 역분개가 생기고 {@code refund.completed}(fat)가 나가는가</li>
 *   <li><b>거절(보상)</b> — 예약이 풀리고 장부엔 <b>끝내 아무것도 안 적히는가</b>.
 *       보상 <i>분개</i>가 없는 게 정답이다 — 안 적었으니 지울 것도 없다</li>
 *   <li><b>무응답</b> — 예약도 장부도 <b>안 건드리는가</b>. 여기서 풀면 이중환불이 열린다</li>
 *   <li><b>멱등</b> — 같은 이벤트를 두 번 처리해도 PG 를 두 번 부르지 않고 장부도 하나인가</li>
 * </ol>
 *
 * <p>스텁 PG 는 <b>환불 금액</b>의 {@code % 1000} 으로 결과를 고른다: 111 거절, 222 타임아웃,
 * 그 외 승인({@code StubPgClient}).
 */
class RefundSagaTest extends IntegrationTestSupport {

    @Autowired OrderService orderService;
    @Autowired PaymentRepository paymentRepository;
    @Autowired RefundService refundService;
    @Autowired RefundConfirmService refundConfirmService;
    @Autowired OutboxRelay outboxRelay;
    @Autowired KafkaListenerEndpointRegistry registry;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager txManager;

    // ---------- 1. 미룸 — 요청 시점엔 예약만 ----------

    @Test
    void 환불_요청만으로는_장부에_아무것도_안_적힌다() {
        Long paymentId = paidOrder();                       // 20,000

        refundService.requestRefund(paymentId, 5_000L);

        long refundJournals = count("SELECT count(*) FROM journal WHERE type = 'REFUND'");
        long reserved = refundedAmount(paymentId);
        String pgStatus = pgStatusOf(paymentId);
        long linkedJournal = count("SELECT count(*) FROM refund WHERE journal_id IS NOT NULL");

        System.out.printf("[실측] 요청만 → REFUND journal=%d 예약(refunded_amount)=%,d pg_status=%s journal연결=%d%n",
                refundJournals, reserved, pgStatus, linkedJournal);

        assertThat(refundJournals).isZero();        // 아직 아무 일도 확정되지 않았다
        assertThat(linkedJournal).isZero();
        assertThat(reserved).isEqualTo(5_000L);     // 예약은 잡혔다 — 이게 과다환불 가드의 근거
        assertThat(pgStatus).isEqualTo("REQUESTED");
    }

    // ---------- 2. 승인 — 확인 뒤에야 장부 ----------

    @Test
    void PG_승인시_역분개가_생기고_fat한_refund_completed가_발행된다() {
        Long paymentId = paidOrder();
        outboxRelay.publishBatch();                          // payment.completed 를 비운다

        RefundOutcome outcome = refundService.requestRefund(paymentId, 5_000L);
        long refundId = ((RefundOutcome.Accepted) outcome).refund().getId();

        RefundPgStatus status = refundConfirmService.confirm(refundId);
        outboxRelay.publishBatch();

        long refundJournals = count("SELECT count(*) FROM journal WHERE type = 'REFUND'");
        long reserved = refundedAmount(paymentId);
        String pgRefundId = (String) em.createNativeQuery(
                "SELECT pg_refund_id FROM refund WHERE id = " + refundId).getSingleResult();
        ConsumerRecord<String, String> rec =
                consume("settlement.refund.completed", String.valueOf(paymentId));

        System.out.printf("[실측] PG 승인 → status=%s REFUND journal=%d 확정액=%,d pg_refund_id=%s%n",
                status, refundJournals, reserved, pgRefundId);
        System.out.printf("[실측] refund.completed value=%s%n", rec.value());

        assertThat(status).isEqualTo(RefundPgStatus.COMPLETED);
        assertThat(refundJournals).isEqualTo(1);             // 이제서야 장부에 적혔다
        assertThat(reserved).isEqualTo(5_000L);              // 예약이 그대로 실제 환불액이 됐다
        assertThat(pgRefundId).isEqualTo("pgr-" + refundId);
        // fat 스냅샷 — 방금 만든 journal 을 거울처럼 읽는다(W3D1 §4 규칙을 지킬 수 있는 유일한 지점)
        assertThat(compact(rec.value())).contains("\"amount\":5000");
        assertThat(compact(rec.value())).contains("\"sellerId\":");
        assertThat(compact(rec.value())).contains("\"refundStatus\":\"PARTIAL_REFUNDED\"");
        assertThat(compact(rec.value())).contains("\"pgRefundId\":\"pgr-" + refundId + "\"");
    }

    // ---------- 3. 거절 — 보상은 예약 해제뿐, 장부는 끝내 비어 있다 ----------

    @Test
    void PG_거절시_예약이_풀리고_장부엔_끝내_아무것도_안_적힌다() {
        Long paymentId = paidOrder();

        RefundOutcome outcome = refundService.requestRefund(paymentId, 5_111L);   // 111 → 거절
        long refundId = ((RefundOutcome.Accepted) outcome).refund().getId();
        long reservedBefore = refundedAmount(paymentId);

        RefundPgStatus status = refundConfirmService.confirm(refundId);

        long reservedAfter = refundedAmount(paymentId);
        long refundJournals = count("SELECT count(*) FROM journal WHERE type = 'REFUND'");
        long ledgerLines = count("SELECT count(*) FROM ledger_entry e JOIN journal j ON j.id = e.journal_id"
                + " WHERE j.type = 'REFUND'");
        long completedEvents = count("SELECT count(*) FROM outbox WHERE event_type = 'refund.completed'");
        long refundRows = count("SELECT count(*) FROM refund");

        System.out.printf("[실측] PG 거절 → status=%s 예약 %,d→%,d | REFUND journal=%d 줄=%d "
                        + "refund.completed=%d refund행=%d%n",
                status, reservedBefore, reservedAfter, refundJournals, ledgerLines,
                completedEvents, refundRows);

        assertThat(status).isEqualTo(RefundPgStatus.FAILED);
        assertThat(reservedBefore).isEqualTo(5_111L);
        assertThat(reservedAfter).isZero();          // 예약 해제 — 잔액이 온전히 돌아왔다
        assertThat(refundJournals).isZero();         // 핵심: 보상 분개조차 없다. 안 적었으니 지울 것도 없다
        assertThat(ledgerLines).isZero();
        assertThat(completedEvents).isZero();        // 완료 안 했으니 완료 이벤트도 없다
        assertThat(refundRows).isEqualTo(1);         // 시도한 이력은 남는다(append-only)
    }

    /**
     * 예약 해제가 <b>실제로 잔액을 되돌려주는가</b>. 상태만 FAILED 로 바꾸고 금액을 안 풀면
     * 여기서 걸린다 — 거절된 환불이 잔액을 영원히 묶어 고객이 재환불을 못 하게 된다.
     */
    @Test
    void 거절로_해제된_잔액은_다시_환불할_수_있다() {
        Long paymentId = paidOrder();                        // 20,000

        RefundOutcome first = refundService.requestRefund(paymentId, 19_111L);   // 111 → 거절
        refundConfirmService.confirm(((RefundOutcome.Accepted) first).refund().getId());

        // 19,111 이 안 풀렸다면 잔액은 889 뿐이라 이 요청이 EXCEEDS_REMAINING 으로 거부된다.
        RefundOutcome retry = refundService.requestRefund(paymentId, 20_000L);

        System.out.printf("[실측] 19,111 거절 해제 후 20,000 재환불 → %s | 예약=%,d%n",
                retry.getClass().getSimpleName(), refundedAmount(paymentId));

        assertThat(retry).isInstanceOf(RefundOutcome.Accepted.class);
        assertThat(refundedAmount(paymentId)).isEqualTo(20_000L);
    }

    // ---------- 4. 무응답 — 아무것도 되돌리지 않는다 ----------

    @Test
    void PG_무응답시_예약도_장부도_건드리지_않는다() {
        Long paymentId = paidOrder();

        RefundOutcome outcome = refundService.requestRefund(paymentId, 5_222L);   // 222 → 타임아웃
        long refundId = ((RefundOutcome.Accepted) outcome).refund().getId();

        RefundPgStatus status = refundConfirmService.confirm(refundId);

        long reserved = refundedAmount(paymentId);
        long refundJournals = count("SELECT count(*) FROM journal WHERE type = 'REFUND'");

        System.out.printf("[실측] PG 무응답 → status=%s 예약=%,d(유지) REFUND journal=%d%n",
                status, reserved, refundJournals);

        assertThat(status).isEqualTo(RefundPgStatus.UNKNOWN);
        // 여기서 예약을 풀면 PG 는 실제로 환불했는데 잔액만 되살아나 이중환불이 열린다.
        // W1D5 의 성급한 재고 복원 = 이중판매와 같은 구멍이라 일부러 잡아둔다.
        assertThat(reserved).isEqualTo(5_222L);
        assertThat(refundJournals).isZero();     // 사실이 확인되지 않았으니 장부도 비어 있다
    }

    // ---------- 5. 멱등 — 중복 배달을 흡수한다 ----------

    @Test
    void 같은_이벤트를_두번_처리해도_역분개는_하나다() {
        Long paymentId = paidOrder();

        RefundOutcome outcome = refundService.requestRefund(paymentId, 5_000L);
        long refundId = ((RefundOutcome.Accepted) outcome).refund().getId();

        RefundPgStatus first = refundConfirmService.confirm(refundId);
        RefundPgStatus second = refundConfirmService.confirm(refundId);   // at-least-once 재배달
        RefundPgStatus third = refundConfirmService.confirm(refundId);

        long refundJournals = count("SELECT count(*) FROM journal WHERE type = 'REFUND'");
        long ledgerLines = count("SELECT count(*) FROM ledger_entry e JOIN journal j ON j.id = e.journal_id"
                + " WHERE j.type = 'REFUND'");
        long reserved = refundedAmount(paymentId);
        long completedEvents = count("SELECT count(*) FROM outbox WHERE event_type = 'refund.completed'");

        System.out.printf("[실측] 3회 처리 → %s/%s/%s | REFUND journal=%d 줄=%d 환불액=%,d refund.completed=%d%n",
                first, second, third, refundJournals, ledgerLines, reserved, completedEvents);

        assertThat(first).isEqualTo(RefundPgStatus.COMPLETED);
        assertThat(second).isEqualTo(RefundPgStatus.COMPLETED);   // 처리됨이 아니라 "이미 처리됨"
        assertThat(third).isEqualTo(RefundPgStatus.COMPLETED);
        assertThat(refundJournals).isEqualTo(1);      // 조건부 UPDATE 가 2·3 회차를 0행으로 끊었다
        assertThat(ledgerLines).isEqualTo(2);
        assertThat(reserved).isEqualTo(5_000L);       // 돈이 세 번 나가지 않았다
        assertThat(completedEvents).isEqualTo(1);     // 완료 이벤트도 하나
    }

    @Test
    void 거절도_두번_처리하면_예약이_두번_풀리지_않는다() {
        Long paymentId = paidOrder();

        RefundOutcome outcome = refundService.requestRefund(paymentId, 5_111L);
        long refundId = ((RefundOutcome.Accepted) outcome).refund().getId();
        refundService.requestRefund(paymentId, 3_000L);     // 다른 환불의 예약 — 같이 풀리면 안 된다

        refundConfirmService.confirm(refundId);
        RefundPgStatus second = refundConfirmService.confirm(refundId);

        long reserved = refundedAmount(paymentId);

        System.out.printf("[실측] 거절 2회 처리 → %s | 남은 예약=%,d (3,000 이어야 함)%n", second, reserved);

        assertThat(second).isEqualTo(RefundPgStatus.FAILED);
        // 두 번 풀렸다면 3,000 - 5,111 이 되어 언더플로 가드에 걸리거나 음수가 됐을 것이다.
        assertThat(reserved).isEqualTo(3_000L);
    }

    // ---------- 6. 배선 — 리스너가 실제로 이벤트를 받아 Saga 를 돈다 ----------

    /**
     * 위 케이스들은 전부 {@code confirm()} 을 직접 부른다(결정적). 그래서 <b>리스너가 실제로
     * 배선됐는지는 증명하지 못한다</b> — W3D1 에서 Quartz 를 끄고 {@code publishBatch()} 를 직접
     * 불렀던 것과 같은 공백이다. 여기서만 리스너 컨테이너를 손으로 켜서, 아무것도 부르지 않고
     * 기다렸을 때 Saga 가 저절로 도는지 본다.
     */
    @Test
    void 리스너가_refund_requested를_받아_스스로_환불을_확정한다() throws Exception {
        Long paymentId = paidOrder();

        RefundOutcome outcome = refundService.requestRefund(paymentId, 5_000L);
        long refundId = ((RefundOutcome.Accepted) outcome).refund().getId();

        outboxRelay.publishBatch();          // payment.completed + refund.requested 를 Kafka 로

        registry.getListenerContainers().forEach(c -> {
            if (!c.isRunning()) c.start();
        });
        try {
            String status = awaitPgStatus(refundId, "COMPLETED", Duration.ofSeconds(20));
            long refundJournals = count("SELECT count(*) FROM journal WHERE type = 'REFUND'");

            System.out.printf("[실측] 리스너 자동 소비 → refundId=%d pg_status=%s REFUND journal=%d%n",
                    refundId, status, refundJournals);

            assertThat(status).isEqualTo("COMPLETED");   // 아무도 confirm() 을 부르지 않았다
            assertThat(refundJournals).isEqualTo(1);
        } finally {
            // 다음 테스트의 cleanDatabase 와 겹치지 않게 반드시 다시 끈다.
            registry.getListenerContainers().forEach(c -> {
                if (c.isRunning()) c.stop();
            });
        }
    }

    /** pg_status 가 기대값이 될 때까지 폴링한다. 소비는 비동기라 시각이 아니라 상태를 기다린다. */
    private String awaitPgStatus(long refundId, String expected, Duration timeout) throws Exception {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        String last = null;
        while (System.currentTimeMillis() < deadline) {
            last = tx().execute(s -> (String) em.createNativeQuery(
                    "SELECT pg_status FROM refund WHERE id = " + refundId).getSingleResult());
            if (expected.equals(last)) return last;
            Thread.sleep(200);
        }
        return last;
    }

    // ---------- helpers ----------

    private ConsumerRecord<String, String> consume(String topic, String key) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

        try (KafkaConsumer<String, String> consumer =
                     new KafkaConsumer<>(props, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(topic));
            long deadline = System.currentTimeMillis() + 10_000;
            while (System.currentTimeMillis() < deadline) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> r : records) {
                    if (key.equals(r.key())) return r;
                }
            }
        }
        throw new AssertionError("Kafka 토픽 " + topic + " 에서 key=" + key + " 레코드를 못 찾음");
    }

    private String pgStatusOf(Long paymentId) {
        return (String) em.createNativeQuery(
                "SELECT pg_status FROM refund WHERE payment_id = " + paymentId).getSingleResult();
    }

    private long refundedAmount(Long paymentId) {
        return ((Number) em.createNativeQuery(
                "SELECT refunded_amount FROM payment WHERE id = " + paymentId)
                .getSingleResult()).longValue();
    }

    private long count(String sql) {
        return ((Number) em.createNativeQuery(sql).getSingleResult()).longValue();
    }

    private static String compact(String json) {
        return json.replaceAll("\\s", "");
    }

    TransactionTemplate tx() {
        return new TransactionTemplate(txManager);
    }

    /** 상품 1개(10,000)×2 → 20,000 PAID 결제. 요율 5%. */
    Long paidOrder() {
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
}
