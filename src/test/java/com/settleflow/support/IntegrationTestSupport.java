package com.settleflow.support;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

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
 * <p><b>싱글턴 컨테이너 패턴(W2D2).</b> {@code @Container}/{@code @Testcontainers} 를 쓰지 않는다 —
 * 그 조합은 이 추상 부모의 static 컨테이너를 <b>클래스마다 stop</b> 시킨다. 그런데 Spring 컨텍스트는
 * 캐시되어 클래스를 넘어 살아남으므로, 뒤에 오는 공유 클래스가 <b>이미 죽은 컨테이너를 가리키는
 * 캐시 컨텍스트</b>를 물어 커넥션을 0개 얻는다({@code total=0} 타임아웃). 공유 클래스가 3개로 늘자
 * 실제로 터졌다. 대신 static 블록에서 한 번만 {@code start()} 하고 <b>끄지 않는다</b> —
 * JVM 종료 시 Testcontainers 의 Ryuk 이 정리한다. 컨테이너가 스위트 전체에서 살아 있어 캐시 컨텍스트가
 * 항상 유효하다.
 *
 * <p>사용: {@code class XxxTest extends IntegrationTestSupport { ... }}.
 * 필요한 빈은 {@code @Autowired} 로 꺼내 쓴다 —
 * {@code PaymentService}, {@code PaymentRepository}, {@code OrderRepository},
 * {@code ProductRepository}, {@code StringRedisTemplate}, {@code TransactionTemplate} 등.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class IntegrationTestSupport {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(TestImages.POSTGRES);

    static final GenericContainer<?> REDIS =
            new GenericContainer<>(TestImages.REDIS).withExposedPorts(6379);

    /**
     * Kafka(W3D1). {@code @ServiceConnection} 이 bootstrap-servers 를 컨테이너 포트로 자동 배선한다 —
     * KafkaContainer 가 advertised.listeners 를 매핑 포트로 맞춰주므로 compose 의 9092 고정 트릭이
     * 테스트에선 불필요하다. 싱글턴이라 스위트당 한 번만 뜬다.
     */
    @ServiceConnection
    static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse(TestImages.KAFKA));

    static {
        // 한 번만 켜고 스위트 내내 유지 — @Container 의 클래스별 stop 을 대체한다.
        POSTGRES.start();
        REDIS.start();
        KAFKA.start();
    }

    /**
     * <b>테스트 전용 설정은 전부 여기 둔다.</b> 예전엔 {@code src/test/resources/application.yaml} 에
     * {@code ddl-auto: validate} 하나가 있었는데, 같은 파일명이라 <b>메인 yaml 을 병합이 아니라 통째로
     * 가렸다</b> — 테스트에선 {@code spring.kafka.*} 가 아예 안 읽혔다(W3D2 에서 발각). W3D1 이 안 터진 건
     * 그 값들이 우연히 Boot 기본값과 같아서였고, {@code auto-offset-reset: earliest} 처럼 기본값과 다른
     * 설정이 생기자 드러났다. 파일을 지우고 이리로 옮겨 가림막을 없앴다.
     */
    @DynamicPropertySource
    static void testProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        // 엔티티 매핑이 Flyway 스키마와 어긋나면 컨텍스트 로딩부터 실패시킨다(SchemaMappingTest 와 같은 보증).
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        // 아웃박스 릴레이 Quartz job 이 테스트마다 제멋대로 폴링하면 published_at 단언이 흔들린다.
        // 자동시작을 끄고 테스트가 relay.publishBatch() 를 직접 불러 결정적으로 검증한다.
        registry.add("spring.quartz.auto-startup", () -> "false");
        // Kafka 리스너도 같은 이유로 끈다(W3D2). 켜두면 refund.requested 소비가 테스트와 <b>동시에</b>
        // PG 를 부르고 장부를 써서 단언이 흔들리고, 더 나쁘게는 테스트가 끝난 뒤 소비가 도착해
        // 다음 테스트의 cleanDatabase 와 겹친다. 판단·트랜잭션은 전부 서비스에 있으므로 테스트는
        // 서비스를 직접 부르고, "리스너가 실제로 배선됐는가"만 별도 케이스에서 컨테이너를 손으로
        // 켜서 확인한다(RefundSagaTest).
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
    }

    protected static String kafkaBootstrapServers() {
        return KAFKA.getBootstrapServers();
    }

    @Autowired private EntityManager em;
    @Autowired private PlatformTransactionManager cleanupTxManager;

    /**
     * 컨테이너가 스위트 내내 살아 있으므로(싱글턴) 데이터가 클래스·테스트 간에 남는다.
     * 매 테스트 전에 전 테이블을 비워 격리한다 — 자식의 {@code @BeforeEach}(seeding)보다
     * 먼저 실행된다(JUnit: 부모 @BeforeEach 우선).
     *
     * <p>순서는 FK 를 거스르지 않는다: 자식 행부터 지운다. {@code ledger_entry}(→journal·seller)·
     * {@code refund}/{@code payment}(→journal) 를 먼저, 그 다음 {@code journal},
     * {@code product} 뒤 {@code seller}. W2 에서 ledger 테스트가 늘어도 여기 한 곳만 본다.
     */
    @BeforeEach
    void cleanDatabase() {
        new TransactionTemplate(cleanupTxManager).executeWithoutResult(s -> {
            List<String> tablesChildFirst = List.of(
                    "settlement", "outbox", "ledger_entry", "refund", "payment", "order_item",
                    "orders", "journal", "product", "seller");
            tablesChildFirst.forEach(t ->
                    em.createNativeQuery("DELETE FROM " + t).executeUpdate());
        });
    }
}
