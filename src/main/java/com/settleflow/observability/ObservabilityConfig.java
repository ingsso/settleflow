package com.settleflow.observability;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 관측 배선(W4D2).
 *
 * <h3>★ 왜 이 파일이 존재하는가 — 자동설정이 Boot 4 에서 조용히 미끄러진다</h3>
 * {@code resilience4j-micrometer:2.2.0} 은 클래스패스에 <b>있고</b>, 회로도 정상 동작하고,
 * {@code /actuator/prometheus} 도 200 을 준다. 그런데 {@code resilience4j_circuitbreaker_state} 만
 * <b>안 나온다.</b> 에러는 없다.
 *
 * <p>원인은 {@code CircuitBreakerMetricsAutoConfiguration} 의 애노테이션에 있다:
 * <pre>
 * &#64;AutoConfigureAfter(name = {
 *     "org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration", ... })
 * ...
 * &#64;Bean &#64;ConditionalOnBean(MeterRegistry.class)
 * TaggedCircuitBreakerMetricsPublisher taggedCircuitBreakerMetricsPublisher(MeterRegistry r)
 * </pre>
 * <b>Boot 4.1.0 에서 저 클래스는 그 패키지에 없다</b> —
 * {@code org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration} 으로
 * 옮겨갔다. {@code @AutoConfigureAfter} 는 클래스를 <b>이름(문자열)</b>으로 참조하므로 없는 이름은
 * 에러가 아니라 <b>그냥 무시</b>되고, 순서 제약이 통째로 증발한다. 그러면 이 자동설정이
 * {@code MeterRegistry} 빈이 등록되기 <b>전에</b> 평가될 수 있고, {@code @ConditionalOnBean} 이
 * false 로 떨어져 퍼블리셔가 안 만들어진다.
 *
 * <p><b>이게 W4D1 §2 가 예고한 실패 모양이다.</b> 거기선 "자동설정이 미끄러지면 에러가 아니라
 * 무음"이라 스모크를 박았는데, 그때 미끄러진 건 없었고 <b>오늘 메트릭 층에서 실제로 미끄러졌다.</b>
 * 설정 파일에 {@code management.*} 를 적은 것만으로는 절대 안 걸린다 — 엔드포인트는 200 이고
 * 다른 메트릭 수십 개가 멀쩡히 나오기 때문이다.
 *
 * <h3>왜 퍼블리셔가 아니라 바인더인가</h3>
 * {@link TaggedCircuitBreakerMetrics} 는 {@code MeterBinder} 이고, Boot 는 <b>모든
 * {@code MeterBinder} 빈을 레지스트리가 만들어진 뒤에</b> 붙인다. 즉 <b>평가 순서에 기대는
 * 부분이 없다</b> — 위에서 깨진 게 바로 그 순서라, 순서를 다시 전제하지 않는 배선을 고른 것이다.
 * 레지스트리 이벤트도 구독하므로 나중에 생기는 회로도 따라 잡힌다.
 *
 * <p><b>대안(퍼블리셔 직접 등록)이 실패한다는 뜻은 아니다 — 안 돌려봤다.</b> 사용자
 * {@code @Configuration} 은 자동설정보다 먼저 처리되므로 그 경로도 성립할 수 있다.
 * 여기서 주장하는 건 <i>"이 방식이 순서를 전제하지 않는다"</i> 까지이고,
 * <i>"저 방식은 안 된다"</i> 는 <b>검증하지 않았다</b>.
 *
 * <p><b>이 빈은 회로가 늘면 자동으로 따라간다</b> — 이름을 여기 하드코딩하지 않는다.
 * W4D1 이 회로를 둘로 나눴고 W5 에서 정기결제가 하나 더 붙을 수 있는데, 그때 이 파일을
 * 고쳐야 한다면 관측이 조용히 뒤처지는 자리가 또 생긴다.
 */
@Configuration
public class ObservabilityConfig {

    /**
     * 회로 상태·호출수·실패율을 {@code resilience4j_circuitbreaker_*} 로 노출한다.
     *
     * <p><b>회로 상태는 DB 에 흔적조차 없다</b> — W4D2 의 축(무엇을 어디에 남기나)에 가장 깨끗하게
     * 맞는 지표다. 불일치 건수는 {@code reconciliation_run} 에 있고 소요시간은 아무 데도 없지만,
     * 회로는 <b>순수 인메모리</b>라 이 경로가 아니면 운영 중에 볼 방법이 아예 없다.
     */
    @Bean
    TaggedCircuitBreakerMetrics circuitBreakerMetrics(CircuitBreakerRegistry circuitBreakerRegistry) {
        return TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(circuitBreakerRegistry);
    }
}
