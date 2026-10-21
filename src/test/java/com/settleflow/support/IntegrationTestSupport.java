package com.settleflow.support;

import com.settleflow.payment.api.WebhookSignatureVerifier;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
        // 같은 이유로 Dunning 폴러도 끈다(W5D2). 켜두면 P1/P2 가 테스트와 <b>동시에</b> PG 를 부르고
        // 주기·장부를 써서, 단언이 자기가 만든 상태가 아니라 폴러가 남긴 상태를 재게 된다.
        // 테스트는 DunningScheduler.runBillingDue(today) / runRetryDue(now) 를 직접 부른다 —
        // 시각을 인자로 받으므로 "1일 뒤"를 기다리지 않고 결정적으로 검증할 수 있다.
        registry.add("settleflow.dunning.scheduler.enabled", () -> "false");
        // 웹훅 서명 시크릿. 메인 yaml 은 ${WEBHOOK_SECRET} 이라 환경변수 없이는 컨텍스트가 안 뜬다.
        registry.add("settleflow.webhook.secret", () -> TEST_WEBHOOK_SECRET);
    }

    protected static final String TEST_WEBHOOK_SECRET = "test-webhook-secret";

    @LocalServerPort private int webhookPort;
    @Autowired private WebhookSignatureVerifier webhookSigner;
    private final HttpClient webhookHttp = HttpClient.newHttpClient();

    /**
     * 서명을 붙여 {@code POST /webhooks/pg} 를 쏜다. 서명 검증이 SETNX 앞에 생긴 뒤로 서명 없는 웹훅은
     * 전부 401 이라, 웹훅을 직접 쏘는 테스트는 이걸 거친다. 서명은 앱이 쓰는 같은 검증기의
     * {@code sign()} 으로 만든다 — 시각은 지금.
     */
    protected HttpResponse<String> postSignedWebhook(String body) {
        String ts = Long.toString(java.time.Instant.now().getEpochSecond());
        return postWebhookRaw(body, ts, webhookSigner.sign(ts, body));
    }

    /** 헤더를 직접 고르는 버전. {@code null} 인 헤더는 안 붙인다(누락 케이스용). */
    protected HttpResponse<String> postWebhookRaw(String body, String timestamp, String signature) {
        return postWebhookRaw(body.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "application/json", timestamp, signature);
    }

    /**
     * 바이트와 Content-Type 까지 고르는 버전. 서버가 받은 바이트를 문자열로 풀었다 다시 인코딩하는지
     * (= 원본 바이트로 검증하지 않는지)를 재려면 바디와 선언 문자셋을 어긋나게 보낼 수 있어야 한다.
     */
    protected HttpResponse<String> postWebhookRaw(byte[] body, String contentType,
                                                  String timestamp, String signature) {
        HttpRequest.Builder req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + webhookPort + "/webhooks/pg"))
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (timestamp != null) req.header("X-Timestamp", timestamp);
        if (signature != null) req.header("X-Signature", signature);
        try {
            return webhookHttp.send(req.build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    protected static String kafkaBootstrapServers() {
        return KAFKA.getBootstrapServers();
    }

    @Autowired private EntityManager em;
    @Autowired private PlatformTransactionManager cleanupTxManager;
    @Autowired private CircuitBreakerRegistry cleanupCircuitBreakerRegistry;

    /**
     * 회로 상태를 매 테스트 전에 되돌린다(W5D2 에서 추가).
     *
     * <p><b>{@link #cleanDatabase} 만으로는 격리가 안 됐다.</b> 회로는 DB 가 아니라 스프링
     * 컨텍스트에 사는데 컨텍스트는 클래스를 넘어 캐시되므로, 앞 테스트가 낸 타임아웃이
     * {@code pg-approve} 를 열어둔 채 다음 테스트로 넘어간다. 그러면 승인이 PG 에 닿지도 않고
     * {@code CallNotPermittedException} 으로 되돌아와 <b>정상 경로가 실패로 확정된다</b>.
     *
     * <p>W5D2 통합 테스트가 타임아웃을 15건 넘게 내면서 실제로 드러났다 —
     * 성공 경로 단언이 {@code expected "PAID" but was "FAILED"} 로 깨졌고, 원인은 그 테스트가
     * 아니라 <b>앞선 테스트들</b>이었다. W4 회고 §4-2(<i>"재는 자와 재이는 것"</i>)와 같은 축이고,
     * 이번에 스위트가 물려준 건 데이터가 아니라 <b>회로</b>다.
     *
     * <p>{@code PgCircuitBreakerTest} 가 자기 클래스에서 하던 초기화를 여기로 올렸다 —
     * 회로를 흔드는 테스트가 그 클래스 하나가 아니게 됐기 때문이다.
     */
    @BeforeEach
    void resetCircuitBreakers() {
        cleanupCircuitBreakerRegistry.circuitBreaker("pg-approve").reset();
        cleanupCircuitBreakerRegistry.circuitBreaker("pg-refund").reset();
    }

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
            // settlement_pending 은 journal 을 FK 로 참조하므로 journal 보다 먼저 지운다.
            // 이 테이블은 앱이 아니라 트리거가 채운다(V10) — 테스트가 journal 을 만들면 자동으로
            // 행이 생기므로, 여기서 안 비우면 다음 테스트의 pendingDates() 에 남의 날짜가 섞인다.
            List<String> tablesChildFirst = List.of(
                    "discrepancy_observation", "reconciliation_run",
                    "settlement", "settlement_pending", "outbox", "ledger_entry", "refund",
                    // payment 는 billing_cycle 을 참조하고(V12 배타적 아크) billing_cycle 은
                    // subscription 을, subscription 은 seller 를 참조한다 — 이 순서를 지킨다.
                    "payment", "billing_cycle", "subscription",
                    "order_item", "orders", "journal", "product", "seller");
            tablesChildFirst.forEach(t ->
                    em.createNativeQuery("DELETE FROM " + t).executeUpdate());
        });
    }
}
