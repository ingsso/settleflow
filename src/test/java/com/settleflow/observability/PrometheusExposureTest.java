package com.settleflow.observability;

import com.settleflow.support.IntegrationTestSupport;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetricsPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * W4D2 선결 — <b>메트릭이 실제로 밖으로 나오는가</b>. W4D1 스모크가 "증명 안 한 것:
 * 메트릭 노출(W4D2 소관)"으로 남긴 자리를 닫는다.
 *
 * <h3>왜 "설정을 넣었다"로 안 끝나는가</h3>
 * W4D1 §2 와 같은 계열의 함정이 여기도 있다. {@code management.endpoints.web.exposure.include} 에
 * {@code prometheus} 를 안 적으면 <b>에러가 아니라 404</b> 고, 레지스트리가 안 붙으면 엔드포인트는
 * 200 을 주면서 <b>그 메트릭만 조용히 빠진다</b>. 둘 다 "설정 파일에 썼다"로는 안 걸린다.
 *
 * <h3>이름이 있는 것과 살아 있는 것은 다르다</h3>
 * 응답 본문에서 문자열만 찾으면 <b>이름이 존재한다</b>까지만 증명된다. 그래서 회로를 실제로
 * 열고 <b>같은 메트릭의 값이 따라 움직이는지</b>를 본다 — 그게 아니면 노출된 숫자가 실제
 * 레지스트리에 연결돼 있다는 근거가 없다. W4D1 이 {@code verify(never())} 로 "안 보냈다"의
 * 직접 증거를 요구한 것과 같은 자리다.
 *
 * <p><b>회로 상태는 DB 에 흔적조차 없다</b> — W4D2 의 축(어디에 남기나)에 가장 깨끗하게 맞는
 * 지표다. 오늘 이전까지 회로가 열렸는지는 로그와 테스트로만 알 수 있었다.
 */
class PrometheusExposureTest extends IntegrationTestSupport {

    private static final String STATE_METRIC = "resilience4j_circuitbreaker_state";

    @LocalServerPort int port;
    @Autowired CircuitBreakerRegistry circuitBreakerRegistry;
    @Autowired ApplicationContext ctx;

    /** 프로젝트 관례 — 웹훅 테스트와 같은 JDK HttpClient({@code PaymentFlowTest}). */
    final HttpClient http = HttpClient.newHttpClient();

    @Test
    void 차단기_메트릭이_prometheus_엔드포인트로_나오고_회로를_열면_값이_따라_움직인다() {
        // ⓪ resilience4j 의 자동설정이 Boot 4 에서 안 붙는다는 직접 증거.
        //    ObservabilityConfig 가 손으로 바인더를 다는 근거가 이 한 줄이다 — 이 단언이
        //    깨지면(= 자동설정이 붙기 시작하면) 우리 빈은 중복이므로 걷어내야 한다.
        //    "왜 이 코드가 있는가"를 주석이 아니라 실행되는 것으로 남긴다.
        assertThat(ctx.getBeanNamesForType(TaggedCircuitBreakerMetricsPublisher.class))
                .as("resilience4j 자동설정이 붙었다면 이 워크어라운드를 제거할 것")
                .isEmpty();

        CircuitBreaker approve = circuitBreakerRegistry.circuitBreaker("pg-approve");
        approve.reset();

        // ① 엔드포인트가 열려 있는가 — include 에 prometheus 가 없으면 여기서 404 다
        String closed = scrape();

        // ② 회로 둘이 이름표를 달고 나오는가. W4D1 이 회로를 둘로 나눈 판단이
        //    관측에서도 갈려 보여야 한다 — 하나로 합쳐 보이면 격리를 볼 수가 없다
        assertThat(closed).contains(STATE_METRIC);
        assertThat(closed).contains("name=\"pg-approve\"");
        assertThat(closed).contains("name=\"pg-refund\"");

        // ③ 지금은 닫혀 있다
        assertThat(closed).contains(line("pg-approve", "closed", "1.0"));
        assertThat(closed).contains(line("pg-approve", "open", "0.0"));

        // ★ ④ 값이 실제로 움직이는가 — 이름만 있는 것과 살아 있는 것을 가르는 단언
        approve.transitionToOpenState();
        String opened = scrape();

        assertThat(opened).contains(line("pg-approve", "open", "1.0"));
        assertThat(opened).contains(line("pg-approve", "closed", "0.0"));

        // ⑤ 격리가 관측에서도 보인다 — 승인이 열려도 환불은 닫힌 채다(W4D1 §7)
        assertThat(opened).contains(line("pg-refund", "closed", "1.0"));

        System.out.printf("[실측] /actuator/prometheus — %s 노출 확인. "
                        + "pg-approve open 0.0 → 1.0, 같은 시점 pg-refund closed=1.0%n",
                STATE_METRIC);

        approve.reset();
    }

    private String scrape() {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/actuator/prometheus"))
                .GET()
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException("스크레이프 실패", e);
        }
        // include 에 prometheus 가 없으면 여기서 404 다 — 에러가 아니라 조용한 부재라서 단언한다.
        assertThat(response.statusCode()).isEqualTo(200);
        return response.body();
    }

    /** 노출 형식 한 줄. 태그 순서는 micrometer 가 이름순으로 정렬한다. */
    private static String line(String circuit, String state, String value) {
        return STATE_METRIC + "{name=\"" + circuit + "\",state=\"" + state + "\"} " + value;
    }
}
