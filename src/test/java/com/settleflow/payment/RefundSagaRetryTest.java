package com.settleflow.payment;

import com.settleflow.payment.consumer.RefundSagaRetryMetrics;
import com.settleflow.payment.domain.RefundPgStatus;
import com.settleflow.payment.event.RefundRequestedEvent;
import com.settleflow.payment.service.RefundConfirmService;
import com.settleflow.support.IntegrationTestSupport;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;

/**
 * 환불 Saga 재시도 정책의 회귀 테스트(W4D5) — <b>W3D2 이월 "DLT·재시도 정책 없음" 청산분.</b>
 *
 * <h3>이 클래스가 겨누는 명제</h3>
 * <b>기다리면 풀리는 장애에서 환불 이벤트가 버려지지 않는다.</b> 환불은 이미 진 빚이라
 * 포기하면 돈이 조용히 안 나간다(W4D1) — 그래서 재시도에 상한을 두지 않았고, 그 선택이 실제로
 * 배선됐는지를 여기서 잰다.
 *
 * <h3>왜 "빈이 떴는가"를 안 보는가</h3>
 * {@code DefaultErrorHandler} 빈이 존재하는 것과 그게 컨테이너 팩토리에 <b>꽂힌</b> 것은 다르다.
 * W4D2 에서 Boot 4 자동설정이 조용히 미끄러진 자리가 정확히 그 모양이었다 — 에러 0건,
 * {@code /actuator/prometheus} 는 200, 메트릭 하나만 없음. 그래서 여기서는 <b>행동</b>을 잰다:
 *
 * <p><b>배달 횟수가 {@code SeekUtils.DEFAULT_MAX_FAILURES}(=10)를 넘는가.</b> 우리 핸들러가 안
 * 꽂히면 기본값이 돌아 <b>정확히 10회</b>에서 멈추고 오프셋이 밀린다. 넘으면 우리 것이 꽂혔고
 * 상한이 없다는 뜻이다 — 단언 하나가 배선과 무제한을 <b>동시에</b> 증명한다.
 *
 * <h3>줄인 것과 안 줄인 것</h3>
 * 프로덕션 간격(2s 시작)으로 11회째를 보려면 2분이 넘는다. 그래서 <b>간격만</b> 50ms 로 줄인다.
 * <b>상한은 손대지 않는다</b> — 애초에 설정 항목으로 열려 있지도 않다
 * ({@code RefundSagaErrorHandlingConfig} 참조). W4D1 이 세운 기준을 그대로 적용한 것이다:
 * 재는 대상(포기하지 않는다는 정책)은 그대로 두고 <b>도구의 눈금만</b> 바꾼다. 회로를 끄고 잰
 * 수치는 우리 수치가 아니라던 그 자리.
 *
 * <h3>{@link RefundConfirmService} 를 대역으로 쓰는 이유</h3>
 * "기다리면 풀리는 장애"를 진짜로 만들려면 DB 를 내리거나 회로를 열어야 하는데, 둘 다 이 테스트가
 * 겨누는 축(재시도가 멈추지 않는가)과 무관한 노이즈를 끌고 온다. 실패의 <b>출처</b>가 아니라
 * 실패에 <b>대응하는 방식</b>이 대상이므로, 예외를 N회 던지고 그 뒤 성공하는 대역이면 충분하다.
 * 리스너부터 Kafka·에러 핸들러·백오프·관측까지는 전부 실물이다.
 *
 * <p>끝단(실제 PG·장부까지)은 {@code RefundSagaTest} 가 이미 덮는다.
 */
@TestPropertySource(properties = {
        // 간격만 줄인다. 상한은 프로덕션과 같다(= 없다).
        "settleflow.kafka.refund-retry.initial-interval=50ms",
        "settleflow.kafka.refund-retry.multiplier=1.0",
        "settleflow.kafka.refund-retry.max-interval=50ms"
})
class RefundSagaRetryTest extends IntegrationTestSupport {

    /** 옛 기본 상한. 이걸 넘겨야 우리 핸들러가 꽂혔다는 뜻이다. */
    private static final int OLD_DEFAULT_CAP = 10;

    /** 상한(10)보다 확실히 많이 실패시킨다. */
    private static final int FAIL_TIMES = 14;

    private static final String TOPIC = "settlement.refund.requested";

    @Autowired KafkaTemplate<String, String> kafkaTemplate;
    @Autowired KafkaListenerEndpointRegistry registry;
    @Autowired MeterRegistry meterRegistry;
    @Autowired RefundSagaRetryMetrics retryMetrics;

    @MockitoBean RefundConfirmService refundConfirmService;

    @Test
    void 기다리면_풀리는_장애에서_환불_이벤트를_버리지_않는다() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        AtomicLong blockedSecondsWhileStuck = new AtomicLong();

        given(refundConfirmService.confirm(anyLong())).willAnswer(invocation -> {
            int n = attempts.incrementAndGet();
            if (n < FAIL_TIMES) {
                throw new IllegalStateException("기다리면 풀리는 장애 모사 #" + n);
            }
            // 마지막(성공할) 호출 시점에 게이지를 읽는다 — 이때는 확실히 막혀 있는 상태다.
            // 밖에서 폴링하면 창이 좁아 놓칠 수 있고, 그건 깜빡이는 단언이 된다(오늘 §인덱스 교훈).
            blockedSecondsWhileStuck.set(
                    (long) (retryMetrics.oldestBlockedSeconds() * 1000));
            return RefundPgStatus.COMPLETED;
        });

        kafkaTemplate.send(TOPIC, payload()).get();

        // 부모가 spring.kafka.listener.auto-startup=false 를 @DynamicPropertySource 로 넣는데,
        // 그쪽이 @TestPropertySource 보다 우선순위가 높아 프로퍼티로는 못 되돌린다.
        // RefundSagaTest 와 같은 방식으로 손으로 켠다.
        registry.getListenerContainers().forEach(c -> {
            if (!c.isRunning()) c.start();
        });

        double retryTotal;
        try {
            // 대역이 성공을 반환할 때까지 = 재시도가 FAIL_TIMES 번을 넘어설 때까지
            awaitAttempts(FAIL_TIMES, Duration.ofSeconds(30));
            retryTotal = meterRegistry.get("settleflow.refund.saga.retry").counter().count();
        } finally {
            // 다음 테스트의 cleanDatabase 와 겹치지 않게 반드시 끈다.
            registry.getListenerContainers().forEach(c -> {
                if (c.isRunning()) c.stop();
            });
        }

        System.out.printf("""
                [실측] 배달 시도 %d회 (옛 기본 상한 %d)
                [실측] retry counter = %.0f
                [실측] 막혀 있던 동안의 blocked.seconds = %.3fs
                [실측] 해소 후 blocked.seconds = %.3f / blocked.partitions = %d
                """,
                attempts.get(), OLD_DEFAULT_CAP, retryTotal,
                blockedSecondsWhileStuck.get() / 1000.0,
                retryMetrics.oldestBlockedSeconds(), retryMetrics.blockedPartitions());

        // ★ 이 단언 하나가 배선과 무제한을 동시에 증명한다 — 기본값이면 정확히 10에서 멈춘다.
        assertThat(attempts.get())
                .as("우리 DefaultErrorHandler 가 안 꽂혔으면 기본 상한 10 에서 멈추고 오프셋이 밀린다")
                .isGreaterThan(OLD_DEFAULT_CAP);

        // 재시도가 실제로 있었다는 것 — 실패 횟수와 카운터가 맞물린다.
        assertThat(retryTotal).isGreaterThanOrEqualTo(OLD_DEFAULT_CAP);

        // ★ (라) 관측이 값을 하는지 — 막혀 있는 동안 게이지가 올라가 있었다.
        assertThat(blockedSecondsWhileStuck.get())
                .as("막혀 있는 동안 blocked.seconds 가 0 이면 영구 점유를 잡을 지표가 없다는 뜻")
                .isPositive();

        // 그리고 해소되면 내려간다 — Gauge 로 고른 근거가 이것이다(W4D2 기준).
        awaitBlockedCleared(Duration.ofSeconds(10));
        assertThat(retryMetrics.blockedPartitions()).isZero();
        assertThat(retryMetrics.oldestBlockedSeconds()).isZero();
    }

    // ---------- helpers ----------

    /**
     * JSON 을 손으로 쓰지 않고 <b>실제 이벤트 레코드를 직렬화</b>한다 — 스키마가 바뀌면 여기서
     * 컴파일이 깨진다. 손으로 쓴 리터럴은 스키마와 어긋나도 조용히 {@code [POISON]} 으로 걸러져
     * "재시도가 안 됐다"로 보인다(1차 실행에서 실제로 그랬다).
     */
    private String payload() throws Exception {
        return com.fasterxml.jackson.databind.json.JsonMapper.builder()
                .addModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                .build()
                .writeValueAsString(new RefundRequestedEvent(1L, 1L, 5_000L, OffsetDateTime.now()));
    }

    private void awaitAttempts(int target, Duration timeout) throws Exception {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (attemptsReached(target)) return;
            Thread.sleep(50);
        }
        throw new AssertionError("시도 횟수가 " + target + " 에 도달하지 않았다");
    }

    private boolean attemptsReached(int target) {
        return org.mockito.Mockito.mockingDetails(refundConfirmService)
                .getInvocations().size() >= target;
    }

    /** 성공 통보는 RecordInterceptor 를 거쳐 비동기로 온다 — 상태를 기다린다. */
    private void awaitBlockedCleared(Duration timeout) throws Exception {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (retryMetrics.blockedPartitions() == 0) return;
            Thread.sleep(50);
        }
    }
}
