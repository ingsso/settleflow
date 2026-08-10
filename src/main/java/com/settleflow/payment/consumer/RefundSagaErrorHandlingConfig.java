package com.settleflow.payment.consumer;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

import java.time.Duration;

/**
 * Kafka 소비자 재시도 정책(W4D5) — <b>W3D2 이월 "DLT·재시도 정책 없음"을 갚는다.</b>
 *
 * <h3>★ 먼저 발각된 것: 이월 항목의 전제가 틀렸다</h3>
 * {@link RefundRequestedConsumer} javadoc 은 <i>"지금은 장애 시 무한 재시도라, 영구적으로 실패하는
 * 이벤트 하나가 파티션을 막을 수 있다"</i> 고 적어뒀다. <b>둘 다 사실이 아니었다.</b> 에러 핸들러
 * 빈이 하나도 없어 Spring Kafka 기본값이 그대로 돌고 있었고, 그 기본값을 실측하면:
 *
 * <pre>
 * [실측] spring-kafka 4.1.0
 *        SeekUtils.DEFAULT_MAX_FAILURES = 10
 *        SeekUtils.DEFAULT_BACK_OFF     = FixedBackOff(0L, 9L)   ← 간격 0ms
 *        DefaultErrorHandler() = 위 BackOff + recoverer 없음(= 로그 남기고 건너뜀)
 * </pre>
 *
 * 즉 실제 동작은 <b>간격 0ms 로 10번 즉시 재시도한 뒤 오프셋을 밀어버리는 것</b>이었다.
 *
 * <h3>그게 W4D1 의 설계를 정면으로 깨뜨리고 있었다</h3>
 * W4D1 은 회로 개방 시 <b>예외를 내보내 재배달</b>시키기로 했다 —
 * <i>"잡으면 오프셋이 밀려 환불이 조용히 사라진다"</i>. 그런데 회로의
 * {@code wait-duration-in-open-state} 는 <b>30초</b>이고 재시도 10번은 <b>수 밀리초</b>에 끝난다.
 * <b>재시도가 회로가 열려 있는 창을 못 넘긴다</b> → 기본 recoverer 가 오프셋을 밀고,
 * {@code refund} 행은 {@code REQUESTED} + 예약금 그대로 남고, 아무도 다시 안 집는다.
 * <b>W4D1 이 막으려 한 결과를 프레임워크 기본값이 대신 해주고 있었다.</b>
 *
 * <h3>상한을 없앤 이유 — DLT 를 안 만든 이유와 같다</h3>
 * 선택지는 넷이었다(소진 없음 / 소진 후 건너뜀 / 소진 후 DLT / 소진 없음 + 관측).
 * <b>소진 없음 + 관측</b>을 골랐다(본인 판단).
 * <ul>
 *   <li><b>건너뜀은 안 된다</b> — 환불은 <b>이미 진 빚</b>이라 포기하면 돈이 조용히 안 나간다.
 *       승인이라면 성립한다(W4D1: 안 보냈으니 빚이 안 생긴다). 실패의 뜻이 경로마다 다르다는
 *       그 축이 여기서 재시도 정책을 갈랐다</li>
 *   <li><b>DLT 는 필요 없다</b> — DLT 는 정의상 "기다리는 걸 그만두고 옆에 치워둔다"인데,
 *       치워둔 것을 <b>누가 보는가</b>가 새 숙제로 남는다. 안 보면 건너뜀과 같은 결과이고,
 *       그건 W3D5 가 불일치에서 만난 <i>"안 나타남이 깨끗했다/안 봤다 둘을 뜻한다"</i> 와 같은
 *       구조다. 이 소비자는 <b>재시도해도 안 바뀌는 것을 상한으로 자르는 대신 분류해서
 *       빼내는</b> 정책을 이미 쓰고 있다({@code [POISON]}·{@code [ORPHAN]})</li>
 *   <li><b>대가는 관측으로 갚는다</b> — {@link RefundSagaRetryMetrics}</li>
 * </ul>
 *
 * <h3>무제한인 근거는 기본값이다 — 손대지 않는다</h3>
 * <pre>
 * [실측] ExponentialBackOff (spring-core 7.0.8)
 *        DEFAULT_INITIAL_INTERVAL = 2000ms / DEFAULT_MULTIPLIER = 1.5
 *        DEFAULT_MAX_INTERVAL     = 30000ms
 *        DEFAULT_MAX_ELAPSED_TIME = DEFAULT_MAX_ATTEMPTS = Long.MAX_VALUE
 * </pre>
 * {@code maxAttempts}·{@code maxElapsedTime} 를 <b>설정 가능하게 만들지 않았다</b>. 상한 없음이
 * 이 정책의 내용 그 자체라 설정 항목으로 열어두면 조용히 되돌려질 수 있다 — 간격만 조절한다.
 *
 * <h3>개별 대기가 30초를 안 넘는 게 중요하다</h3>
 * {@code DefaultErrorHandler} 의 재시도는 <b>블로킹</b>이라 대기 동안 컨슈머 스레드가 멈춘다.
 * 한 번의 대기가 {@code max.poll.interval.ms}(기본 300초)를 넘으면 컨슈머가 그룹에서 쫓겨나
 * 리밸런스 루프에 빠진다. {@code max-interval} 이 30초라 그 한참 아래이고, 하트비트는 별도
 * 스레드가 보내므로 {@code session.timeout.ms} 와도 무관하다.
 *
 * <h3>⚠️ 알고 남기는 구멍 — 이 정책은 컨테이너 팩토리 전체에 걸린다</h3>
 * {@code CommonErrorHandler}·{@code RecordInterceptor} 는 리스너별이 아니라 <b>팩토리 전역</b>이다.
 * 지금 리스너가 환불 하나뿐이라 성립하지만, <b>승인 쪽 소비자가 생기면 그쪽도 무제한 재시도를
 * 물려받는다</b> — W4D1 이 회로를 {@code pg-approve}/{@code pg-refund} 둘로 나눈 것과 정확히
 * 같은 비대칭이 재시도 정책에는 아직 안 들어가 있다. 리스너가 둘이 되는 날 갈라야 한다.
 */
@Configuration
public class RefundSagaErrorHandlingConfig {

    /**
     * Boot 4.1.0 의 {@code KafkaAnnotationDrivenConfiguration} 이
     * {@code ObjectProvider<CommonErrorHandler>} 로 이 빈을 받아
     * {@code setCommonErrorHandler} 로 컨테이너 팩토리에 꽂는다.
     *
     * <p><b>빈이 떴다는 것만으론 배선을 증명하지 못한다</b> — W4D2 에서 자동설정이 Boot 4 에서
     * 조용히 미끄러진 자리가 정확히 그 모양이었다(에러 0건, 메트릭 하나만 없음). 그래서
     * {@code RefundSagaRetryTest} 는 빈 존재가 아니라 <b>배달 횟수가 옛 상한 10 을 넘는지</b>를
     * 잰다 — 그 하나로 배선과 무제한을 동시에 증명한다.
     */
    @Bean
    DefaultErrorHandler kafkaRefundSagaErrorHandler(
            RefundSagaRetryMetrics metrics,
            @Value("${settleflow.kafka.refund-retry.initial-interval:2s}") Duration initialInterval,
            @Value("${settleflow.kafka.refund-retry.multiplier:1.5}") double multiplier,
            @Value("${settleflow.kafka.refund-retry.max-interval:30s}") Duration maxInterval) {

        ExponentialBackOff backOff = new ExponentialBackOff();
        backOff.setInitialInterval(initialInterval.toMillis());
        backOff.setMultiplier(multiplier);
        backOff.setMaxInterval(maxInterval.toMillis());
        // maxAttempts / maxElapsedTime 는 의도적으로 안 건드린다 — 기본값이 Long.MAX_VALUE 다.

        DefaultErrorHandler handler = new DefaultErrorHandler(backOff);
        handler.setRetryListeners(metrics);
        return handler;
    }
}
