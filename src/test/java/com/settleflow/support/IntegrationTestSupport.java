package com.settleflow.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 통합 테스트 공통 인프라 — 실제 Postgres + Redis 컨테이너.
 *
 * <p>웹훅 동시 폭주를 진짜로 재현하려면 스레드들이 실제 HTTP 엔드포인트를 두드려야 한다.
 * 그래서 {@code RANDOM_PORT} 로 내장 서버를 띄운다 — 테스트에서 {@code @LocalServerPort} 로 포트를 받아
 * {@code TestRestTemplate}(또는 스레드별 HttpClient)로 {@code POST /webhooks/pg} 를 동시에 쏘면 된다.
 *
 * <p>Postgres 는 {@code @ServiceConnection} 이 datasource 를 자동 배선하고,
 * Redis 는 스타터에 자동 연결 지원이 없어 {@code @DynamicPropertySource} 로 host/port 를 직접 꽂는다.
 *
 * <p>사용: {@code class XxxTest extends IntegrationTestSupport { ... }}.
 * 필요한 빈은 {@code @Autowired} 로 꺼내 쓴다 —
 * {@code PaymentService}, {@code PaymentRepository}, {@code OrderRepository},
 * {@code ProductRepository}, {@code StringRedisTemplate}, {@code TransactionTemplate} 등.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
public abstract class IntegrationTestSupport {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(TestImages.POSTGRES);

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(TestImages.REDIS).withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }
}
