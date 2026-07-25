package com.settleflow.outbox;

import com.settleflow.order.service.OrderService;
import com.settleflow.outbox.service.OutboxRelay;
import com.settleflow.payment.repository.PaymentRepository;
import com.settleflow.payment.service.RefundService;
import com.settleflow.product.domain.Product;
import com.settleflow.seller.domain.Seller;
import com.settleflow.support.IntegrationTestSupport;
import jakarta.persistence.EntityManager;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * W3D1 실측 — 트랜잭셔널 아웃박스 + 폴링 릴레이.
 *
 * <p>확인하는 것 세 축:
 * <ol>
 *   <li><b>원자성</b> — 결제/환불이 반영되면 이벤트가 <b>같은 tx</b>로 아웃박스에 쌓인다(journal 과 짝).
 *   <li><b>릴레이</b> — 미발행 행을 Kafka 로 실어내고 published_at 을 찍는다. 발행된 건 재발행 안 한다.
 *   <li><b>fat 스냅샷</b> — payload 가 셀러별 내역(장부를 거울처럼)을 담는다.
 * </ol>
 *
 * <p>Quartz 자동시작은 테스트에서 꺼져 있다(IntegrationTestSupport) — {@link OutboxRelay#publishBatch()}
 * 를 직접 불러 결정적으로 본다.
 */
class OutboxRelayTest extends IntegrationTestSupport {

    @Autowired OrderService orderService;
    @Autowired RefundService refundService;
    @Autowired PaymentRepository paymentRepository;
    @Autowired OutboxRelay outboxRelay;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager txManager;

    @Test
    void 결제_확정시_payment_completed가_같은_tx로_아웃박스에_쌓인다() {
        Long paymentId = paidOrder();   // 셀러 1명, 20,000

        long outboxRows = count("SELECT count(*) FROM outbox WHERE event_type = 'payment.completed'");
        long unpublished = count("SELECT count(*) FROM outbox WHERE published_at IS NULL");
        long journals = count("SELECT count(*) FROM journal WHERE type = 'PAYMENT'");
        String payload = payloadOf(paymentId, "payment.completed");

        System.out.printf("[실측] 결제확정 → outbox(payment.completed)=%d 미발행=%d journal(PAYMENT)=%d%n",
                outboxRows, unpublished, journals);
        System.out.printf("[실측] payload=%s%n", payload);

        assertThat(outboxRows).isEqualTo(1);
        assertThat(unpublished).isEqualTo(1);          // 아직 릴레이 안 돌았다
        assertThat(journals).isEqualTo(outboxRows);    // journal 과 이벤트가 짝 — 같은 tx
        assertThat(compact(payload)).contains("\"amount\":20000");
        assertThat(compact(payload)).contains("\"payable\":19000").contains("\"fee\":1000");   // fat 스냅샷
    }

    @Test
    void 릴레이가_Kafka로_발행하고_published를_찍고_재발행하지_않는다() {
        Long paymentId = paidOrder();

        int published = outboxRelay.publishBatch();
        long stillUnpublished = count("SELECT count(*) FROM outbox WHERE published_at IS NULL");
        int secondRun = outboxRelay.publishBatch();     // 이미 발행됨 → 0

        ConsumerRecord<String, String> rec =
                consume("settlement.payment.completed", String.valueOf(paymentId));

        System.out.printf("[실측] publishBatch=%d 미발행잔량=%d 재실행=%d | Kafka key=%s%n",
                published, stillUnpublished, secondRun, rec.key());
        System.out.printf("[실측] Kafka value=%s%n", rec.value());

        assertThat(published).isEqualTo(1);
        assertThat(stillUnpublished).isZero();          // published_at 찍힘
        assertThat(secondRun).isZero();                 // 미발행만 긁는다 — 재발행 없음
        assertThat(rec.key()).isEqualTo(String.valueOf(paymentId));   // 키 = aggregateId(파티션 순서)
        assertThat(compact(rec.value())).contains("\"amount\":20000");
    }

    @Test
    void 환불시_refund_requested가_발행된다() {
        Long paymentId = paidOrder();
        outboxRelay.publishBatch();                     // payment.completed 를 먼저 비운다

        refundService.requestRefund(paymentId, 5_000L);
        int published = outboxRelay.publishBatch();

        ConsumerRecord<String, String> rec =
                consume("settlement.refund.requested", String.valueOf(paymentId));

        System.out.printf("[실측] 환불 → publishBatch=%d | Kafka value=%s%n", published, rec.value());

        assertThat(published).isEqualTo(1);
        assertThat(compact(rec.value())).contains("\"amount\":5000");
        assertThat(compact(rec.value())).contains("\"refundStatus\":\"PARTIAL_REFUNDED\"");
        assertThat(compact(rec.value())).contains("\"sellerId\":");   // fat: 셀러별 안분
    }

    // ---------- helpers ----------

    /** 토픽에서 주어진 key(paymentId)의 레코드를 찾아 돌려준다. paymentId 는 스위트 내내 유일(IDENTITY). */
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

    private String payloadOf(Long paymentId, String eventType) {
        return (String) em.createNativeQuery(
                        "SELECT payload::text FROM outbox WHERE aggregate_id = " + paymentId
                                + " AND event_type = '" + eventType + "'")
                .getSingleResult();
    }

    private long count(String sql) {
        return ((Number) em.createNativeQuery(sql).getSingleResult()).longValue();
    }

    /** JSONB 는 콜론 뒤에 공백을 넣어 저장한다("amount": 20000). 포맷에 안 흔들리게 공백을 지운다. */
    private static String compact(String json) {
        return json.replaceAll("\\s", "");
    }

    TransactionTemplate tx() {
        return new TransactionTemplate(txManager);
    }

    /** 상품 1개(10,000)×2 → 20,000 PAID 결제. 요율 5% → 미지급금 19,000 / 수수료 1,000. */
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
