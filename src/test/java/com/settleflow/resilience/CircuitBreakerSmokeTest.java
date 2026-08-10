package com.settleflow.resilience;

import com.settleflow.support.IntegrationTestSupport;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.springboot3.circuitbreaker.autoconfigure.CircuitBreakerProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * W4D1 선결 — <b>resilience4j 가 Spring Boot 4.1.0 에서 실제로 동작하는가</b>.
 *
 * <p>이 프로젝트는 {@code resilience4j-spring-boot3:2.2.0} 을 쓴다. 이름이 말하듯 <b>Boot 3 용
 * 스타터</b>이고 Boot 4 대응 스타터는 아직 없다 — W3 회고 §5-3 이 "호환성 미확인"으로 적어둔 자리다.
 * 의존성은 W1 부터 클래스패스에 있었지만 코드가 한 줄도 없어 <b>지금까지 아무도 안 밟았다.</b>
 *
 * <h3>왜 "빈이 뜨는지"만 보면 안 되는가</h3>
 * 자동설정이 {@code @ConditionalOnClass} 에서 미끄러지면 <b>에러가 아니라 무음</b>이다. 그 상태로
 * {@code @CircuitBreaker} 를 붙이면 애노테이션은 그냥 무시되고 <b>테스트는 초록으로 통과한다</b> —
 * 차단기가 없는데 있다고 믿게 된다. 오늘 아침 {@code :test UP-TO-DATE} 와 같은 모양이다.
 * 그래서 셋을 한 번에 겨눈다:
 * <ol>
 *   <li><b>자동설정</b> — {@link CircuitBreakerRegistry} 빈이 컨텍스트에 뜨는가
 *       (안 뜨면 {@code @Autowired} 가 <b>컨텍스트 로딩부터</b> 실패시킨다)</li>
 *   <li><b>AOP</b> — {@code @CircuitBreaker} 가 실제로 호출을 가로채 상태를 바꾸는가.
 *       무음이면 아무리 실패시켜도 상태가 {@code CLOSED} 에 머물러 빨간불이 난다</li>
 *   <li><b>설정 바인딩</b> — {@code resilience4j.circuitbreaker.*} 프로퍼티가 먹는가.
 *       기본값(호출 100건)이 아니라 아래에서 준 5건에 열려야 한다</li>
 * </ol>
 *
 * <p><b>프로덕션 코드는 건드리지 않는다.</b> {@code PgClient} 에 어떻게 걸지는 W4D1 설계에서
 * 정한다(회로 단위·OPEN 일 때의 결제 상태·장애 주입 방식). 이 클래스는 그 설계의 <b>전제</b>가
 * 성립하는지만 확인한다.
 */
@Import(CircuitBreakerSmokeTest.Flaky.class)
class CircuitBreakerSmokeTest extends IntegrationTestSupport {

    /** 임계치. 기본값 100 이 아니라 이 값에 열려야 설정이 먹은 것이다. */
    private static final int THRESHOLD = 5;

    /**
     * 부모의 {@code @DynamicPropertySource} 와 <b>합쳐진다</b>(덮어쓰기가 아니다).
     * 테스트 전용 설정을 {@code src/test/resources/application.yaml} 에 두면 메인 yaml 을 통째로
     * 가린다는 걸 W3D2 에서 겪었으므로, 프로퍼티는 여기서 준다.
     */
    @DynamicPropertySource
    static void circuitBreakerProperties(DynamicPropertyRegistry registry) {
        String base = "resilience4j.circuitbreaker.instances.smoke.";
        registry.add(base + "sliding-window-type", () -> "COUNT_BASED");
        registry.add(base + "sliding-window-size", () -> THRESHOLD);
        registry.add(base + "minimum-number-of-calls", () -> THRESHOLD);
        registry.add(base + "failure-rate-threshold", () -> 100);
        // OPEN 인 채로 단언할 수 있도록 충분히 길게 — 자동 HALF_OPEN 전이도 기본값(off)에 맡긴다.
        registry.add(base + "wait-duration-in-open-state", () -> "60s");
    }

    @Autowired CircuitBreakerRegistry circuitBreakerRegistry;   // ① 자동설정
    @Autowired CircuitBreakerProperties properties;             // ③ 설정 바인딩
    @Autowired Flaky flaky;                                     // ② AOP

    @Test
    void resilience4j_가_Boot4_에서_실제로_회로를_연다() {
        // ③ 준 값이 바인딩됐는가 — 여기가 어긋나면 아래 임계치 단언의 의미가 없다
        assertThat(properties.getInstances().get("smoke").getMinimumNumberOfCalls())
                .isEqualTo(THRESHOLD);

        CircuitBreaker breaker = circuitBreakerRegistry.circuitBreaker("smoke");
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);

        // ② 임계치까지 실패를 밀어넣는다. 애스펙트가 안 걸려 있으면 예외는 그대로 나오지만
        //    차단기는 이 호출들을 못 보므로 상태가 CLOSED 에 머문다 → 아래에서 빨간불.
        for (int i = 0; i < THRESHOLD; i++) {
            assertThatThrownBy(flaky::boom).isInstanceOf(IllegalStateException.class);
        }

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // OPEN 이면 호출이 <b>대상에 닿기도 전에</b> 거절된다 — 예외 타입이 바뀌는 게 그 증거다.
        // (IllegalStateException 이면 여전히 boom() 이 실행됐다는 뜻이라 차단이 아니다)
        assertThatThrownBy(flaky::boom).isInstanceOf(CallNotPermittedException.class);

        System.out.printf("[실측] resilience4j %s — %d회 실패 후 %s, 이후 호출은 CallNotPermittedException%n",
                CircuitBreaker.class.getPackage().getImplementationVersion(),
                THRESHOLD, breaker.getState());
    }

    /** 스모크 전용 대상. 프로덕션 빈이 아니라 {@code @Import} 로만 들어온다. */
    public static class Flaky {

        @io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker(name = "smoke")
        public String boom() {
            throw new IllegalStateException("boom");
        }
    }
}
