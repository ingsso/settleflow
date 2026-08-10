package com.settleflow.payment.consumer;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.listener.RecordInterceptor;
import org.springframework.kafka.listener.RetryListener;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 환불 Saga 소비자의 재시도를 관측 가능하게 만든다(W4D5).
 *
 * <h3>왜 이게 필요한가 — 무제한 재시도의 대가는 조용한 막힘이다</h3>
 * {@link RefundSagaErrorHandlingConfig} 가 재시도 상한을 없앴다. 근거는 환불이 <b>이미 진 빚</b>이라
 * 포기하면 돈이 조용히 안 나간다는 것이다(W4D1). 그 선택에 딸려오는 대가가 하나 있다 —
 * 기다려도 안 풀리는 예외(제약 위반·W3D2 언더플로 백스톱 같은 것)가 하나 있으면 <b>그 파티션이
 * 영구히 점유되고 뒤의 환불이 안 나간다.</b> 그리고 블로킹 재시도라 로그만 반복될 뿐 <b>아무
 * 지표도 안 움직인다.</b>
 *
 * <p>W4D2 가 세운 축이 여기 그대로 적용된다 — <i>그 알림이 눈을 감는 순간은 언제인가</i>.
 * 무제한 재시도는 "잃지 않음"을 사는 대신 "막힘이 안 보임"을 지불하는데, 그 지불을 여기서 갚는다.
 *
 * <h3>Counter 와 Gauge 를 가르는 기준(W4D2)</h3>
 * <table border="1">
 *   <caption>계기 선택</caption>
 *   <tr><th>지표</th><th>종류</th><th>근거 — 해소되면 내려가야 하는가</th></tr>
 *   <tr><td>재시도 누적 횟수</td><td>Counter</td>
 *       <td><b>아니다.</b> "그동안 재시도가 몇 번 있었다"는 역사적 사실이라 해소돼도 안 내려간다</td></tr>
 *   <tr><td>막혀 있는 시간</td><td>Gauge</td>
 *       <td><b>그렇다.</b> 풀리면 0 이어야 한다 — 이건 수위다</td></tr>
 *   <tr><td>막힌 파티션 수</td><td>Gauge</td>
 *       <td><b>그렇다.</b> 한 건이 막았나 여러 파티션이 막혔나를 가른다</td></tr>
 * </table>
 *
 * <p>여기서 {@code 0} 은 부재가 아니라 <b>실제 값</b>이다("아무것도 막혀 있지 않다"). W4D2 가
 * {@code last_success_timestamp} 에서 0 을 쓰지 않은 건 그게 1970년으로 읽혔기 때문이고,
 * 이 지표엔 그 문제가 없다 — 그래서 {@code NaN} 짝이 필요 없다.
 *
 * <h3>인메모리라 재시작하면 0 이 된다 — 그런데 스스로 회복한다</h3>
 * W4D2 는 <b>재구성</b>을 선호했다(밀어넣은 값은 재시작에 0 이 되는데 대상은 그대로라 거짓 초록이
 * 된다). 여기엔 재구성할 원본이 없다 — 재시도 상태는 컨슈머 스레드 안에만 있다. 다만 이 경우
 * <b>거짓 초록이 오래 안 간다</b>: 오프셋이 안 밀렸으므로 재시작하면 같은 레코드가 다시 배달되고,
 * 다시 실패하면서 게이지가 0 에서 다시 올라간다.
 *
 * <p>⚠️ 대가는 남는다 — <b>재시작을 건너뛴 "총 막힌 시간"은 과소평가된다.</b> 며칠째 막혀 있었는지를
 * 알아야 하면(W3D5 가 불일치에서 만난 그 질문) 이 게이지로는 부족하고 기록이 필요하다.
 *
 * <h3>두 훅을 쓰는 이유</h3>
 * {@link RetryListener} 에는 <b>성공 통보가 없다</b>({@code failedDelivery}/{@code recovered} 뿐이고
 * {@code recovered} 는 상한이 소진될 때만 불리므로 무제한 설정에선 <b>영원히 안 불린다</b>).
 * 그래서 게이지를 내리는 쪽은 {@link RecordInterceptor#success} 로 받는다. 둘 다 컨테이너 수준
 * 훅이라 <b>리스너 코드를 안 건드린다</b> — W4D1 이 회로를 데코레이터로 뺀 것과 같은 이유.
 */
@Component
public class RefundSagaRetryMetrics implements RetryListener, RecordInterceptor<Object, Object> {

    /** 지금 재시도에 막혀 있는 레코드. 블로킹 재시도라 파티션당 최대 1건이다. */
    private final Map<TopicPartition, Blocked> blocked = new ConcurrentHashMap<>();

    private final Counter retries;

    private record Blocked(long offset, long firstFailureNanos) {
    }

    public RefundSagaRetryMetrics(MeterRegistry registry) {
        this.retries = Counter.builder("settleflow.refund.saga.retry")
                .description("환불 Saga 소비자의 재시도 누적 횟수. 상한이 없으므로 이 값이 계속 오르는 것 "
                        + "자체는 정상이고, 판단은 아래 blocked 게이지로 한다")
                .register(registry);

        Gauge.builder("settleflow.refund.saga.retry.blocked.seconds", this,
                        RefundSagaRetryMetrics::oldestBlockedSeconds)
                .description("가장 오래 재시도에 막혀 있는 레코드의 경과 시간. 무제한 재시도가 "
                        + "'영구 점유'로 바뀌는 순간을 잡는 유일한 지표 — 알림을 걸 자리는 여기다")
                .baseUnit("seconds")
                .register(registry);

        Gauge.builder("settleflow.refund.saga.retry.blocked.partitions", blocked, Map::size)
                .description("재시도에 막혀 있는 파티션 수. 한 건이 막았는지 여러 파티션이 막혔는지를 가른다")
                .register(registry);
    }

    // ---------- RetryListener — 막힘을 올린다 ----------

    /**
     * 배달이 실패했다. <b>같은 오프셋의 재실패는 시작 시각을 갱신하지 않는다</b> — 갱신하면
     * "얼마나 오래 막혀 있나"가 항상 0 에 가깝게 나와 지표가 뜻을 잃는다.
     */
    @Override
    public void failedDelivery(ConsumerRecord<?, ?> record, Exception ex, int deliveryAttempt) {
        retries.increment();
        blocked.compute(key(record), (tp, current) ->
                current != null && current.offset() == record.offset()
                        ? current
                        : new Blocked(record.offset(), System.nanoTime()));
    }

    // ---------- RecordInterceptor — 막힘을 내린다 ----------

    @Override
    public ConsumerRecord<Object, Object> intercept(ConsumerRecord<Object, Object> record,
                                                    Consumer<Object, Object> consumer) {
        return record;                       // 가로채지 않는다. success() 를 받기 위해 구현할 뿐이다.
    }

    /** 그 레코드가 결국 처리됐다. 막고 있던 게 이 오프셋이었다면 내려준다. */
    @Override
    public void success(ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
        blocked.computeIfPresent(key(record),
                (tp, current) -> current.offset() == record.offset() ? null : current);
    }

    // ---------- 조회 ----------

    /** 막힌 게 없으면 0. 여기서 0 은 부재가 아니라 "안 막혀 있다"는 실제 값이다(위 javadoc). */
    public double oldestBlockedSeconds() {
        long now = System.nanoTime();
        return blocked.values().stream()
                .mapToDouble(b -> (now - b.firstFailureNanos()) / 1_000_000_000.0)
                .max()
                .orElse(0.0);
    }

    public int blockedPartitions() {
        return blocked.size();
    }

    private static TopicPartition key(ConsumerRecord<?, ?> record) {
        return new TopicPartition(record.topic(), record.partition());
    }
}
