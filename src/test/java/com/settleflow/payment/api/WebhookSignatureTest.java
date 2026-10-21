package com.settleflow.payment.api;

import com.settleflow.order.domain.Order;
import com.settleflow.payment.domain.Payment;
import com.settleflow.payment.domain.PaymentStatus;
import com.settleflow.payment.repository.PaymentRepository;
import com.settleflow.product.domain.Product;
import com.settleflow.seller.domain.Seller;
import com.settleflow.support.IntegrationTestSupport;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 웹훅 발신자 검증 — 서명이 틀리면 401 이고, <b>401 은 아무 흔적도 남기지 않는다</b>.
 *
 * <p>핵심은 {@link #위조요청이_먼저와도_같은_eventId의_진짜통보는_반영된다} 다.
 * 서명 검증이 SETNX 뒤에 있으면 위조가 멱등키를 선점해 진짜 통보가 "중복"으로 버려진다.
 *
 * <p>상태 단언은 결제 행({@code payment.status})과 Redis 멱등키 두 축으로 본다. 401 이 상태를 안
 * 바꿨다는 것만으로는 순서를 증명하지 못한다(뒤에 둬도 상태는 안 바뀐다) — 키가 안 남았다는 게 순서다.
 *
 * <p><b>음성대조군 [실측 2026-10-21]</b> — 검증을 SETNX 뒤로 옮기면 <b>3 red / 4 green</b>.
 * 처음엔 "위조→진짜 하나만 red" 라고 여기 적었는데 틀렸다. 셋 다 같은 원인(401 이 키를 남김)을
 * 다른 자리에서 본 것이다:
 * <ul>
 *   <li>서명 틀림 — 키를 직접 단언해서 잡힌다({@code expected false but was true}). 위 문단이 이미
 *       "키가 순서다"라고 해놓고 아래에선 "다른 케이스는 초록불"이라 써서 스스로 어긋났었다.
 *   <li>헤더 누락 — 첫 요청이 키를 남기자 <b>헤더 없는 두 번째 요청이 200</b>을 받았다(검증까지 안 간다).
 *       위조자가 같은 eventId 로 두 번 쏘면 두 번째부터 "수신 확인"을 받는다는 뜻이다.
 *   <li>위조→진짜 — 진짜 200 인데 PENDING. 진짜 통보가 성공 응답을 받고 버려져 PG 도 재전송하지 않는다.
 * </ul>
 * 초록불 넷(변조·만료·정상·고정값)은 요청을 한 번만 쏘고 키를 안 보므로 순서와 무관하다.
 */
class WebhookSignatureTest extends IntegrationTestSupport {

    private static final long AMOUNT = 10_000L;   // %1000 != 111,222 → Mock PG 정상 경로

    @Autowired PaymentRepository paymentRepository;
    @Autowired StringRedisTemplate redis;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager txManager;
    @Autowired WebhookSignatureVerifier verifier;
    @Autowired MeterRegistry meterRegistry;

    TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        redis.execute((RedisCallback<Object>) c -> {
            c.serverCommands().flushDb();
            return null;
        });
    }

    @Test
    void 정상서명은_200이고_결제가_PAID로_전이된다() {
        UUID mpid = createPendingPayment();

        HttpResponse<String> res = postSignedWebhook(body("evt-ok", mpid, AMOUNT));

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(status(mpid)).isEqualTo(PaymentStatus.PAID);
    }

    @Test
    void 서명이_틀리면_401이고_상태도_멱등키도_그대로다() {
        UUID mpid = createPendingPayment();
        String body = body("evt-badsig", mpid, AMOUNT);
        String ts = now();
        String wrong = new WebhookSignatureVerifier("not-the-secret", 300).sign(ts, body);

        HttpResponse<String> res = postWebhookRaw(body, ts, wrong);

        assertThat(res.statusCode()).isEqualTo(401);
        assertThat(status(mpid)).isEqualTo(PaymentStatus.PENDING);
        assertThat(redis.hasKey("webhook:dedup:evt-badsig")).isFalse();
    }

    @Test
    void 서명헤더가_없으면_401() {
        UUID mpid = createPendingPayment();
        String body = body("evt-noheader", mpid, AMOUNT);
        String ts = now();

        assertThat(postWebhookRaw(body, null, null).statusCode()).isEqualTo(401);
        assertThat(postWebhookRaw(body, ts, null).statusCode()).isEqualTo(401);
        assertThat(postWebhookRaw(body, null, verifier.sign(ts, body)).statusCode()).isEqualTo(401);
        assertThat(status(mpid)).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    void 바디를_변조하면_amount만_바꿔도_401() {
        UUID mpid = createPendingPayment();
        String ts = now();
        String signedFor = verifier.sign(ts, body("evt-tamper", mpid, AMOUNT));

        HttpResponse<String> res = postWebhookRaw(body("evt-tamper", mpid, 1L), ts, signedFor);

        assertThat(res.statusCode()).isEqualTo(401);
        assertThat(status(mpid)).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    void 허용오차_5분을_넘긴_타임스탬프는_서명이_맞아도_401() {
        UUID mpid = createPendingPayment();
        String body = body("evt-stale", mpid, AMOUNT);
        String stale = Long.toString(Instant.now().getEpochSecond() - 301);

        HttpResponse<String> res = postWebhookRaw(body, stale, verifier.sign(stale, body));

        assertThat(res.statusCode()).isEqualTo(401);
        assertThat(status(mpid)).isEqualTo(PaymentStatus.PENDING);
    }

    /**
     * ★ 순서 결정의 근거. 위조가 진짜 {@code eventId} 를 담아 먼저 도착해도 멱등키를 선점하지 못해야
     * 진짜 통보가 반영된다. 음성대조군(검증을 SETNX 뒤로)에서 red — 클래스 javadoc 의 [실측] 참조.
     * 이 케이스만 red 인 건 아니다.
     */
    @Test
    void 위조요청이_먼저와도_같은_eventId의_진짜통보는_반영된다() {
        UUID mpid = createPendingPayment();
        String body = body("evt-race", mpid, AMOUNT);
        String ts = now();
        String forged = new WebhookSignatureVerifier("attacker-guess", 300).sign(ts, body);

        HttpResponse<String> fake = postWebhookRaw(body, ts, forged);
        boolean keyAfterFake = Boolean.TRUE.equals(redis.hasKey("webhook:dedup:evt-race"));
        HttpResponse<String> real = postSignedWebhook(body);

        System.out.printf("[실측] 위조→진짜(같은 eventId): 위조 HTTP %d, 위조 뒤 멱등키 %s, 진짜 HTTP %d, 최종 status=%s%n",
                fake.statusCode(), keyAfterFake ? "있음" : "없음", real.statusCode(), status(mpid));

        // 이 클래스가 겨누는 축(최종 반영)을 먼저 단언한다 — 앞 단언이 실패하면 뒤는 실행조차 안 된다(W3D4).
        assertThat(status(mpid)).isEqualTo(PaymentStatus.PAID);
        assertThat(fake.statusCode()).isEqualTo(401);
        assertThat(keyAfterFake).isFalse();
        assertThat(real.statusCode()).isEqualTo(200);
    }

    /**
     * 알려진 값 대조 — {@code sign()} 과 {@code verify()} 가 같은 클래스라, 둘이 <b>똑같이</b> 틀리면
     * (예: 구분자 {@code "."} 를 빼먹음) 위 케이스들은 전부 초록불이고 실제 PG 와는 안 맞는다.
     * 기대값은 이 코드가 아니라 Python {@code hmac.new(secret, "1700000000." + body, sha256)} 으로 만들었다.
     */
    @Test
    void 서명은_외부에서_계산한_HMAC_SHA256_값과_일치한다() {
        String ts = "1700000000";
        String body = "{\"eventId\":\"evt-1\",\"amount\":10000}";
        Clock fixed = Clock.fixed(Instant.ofEpochSecond(1_700_000_000L), ZoneOffset.UTC);
        WebhookSignatureVerifier v = new WebhookSignatureVerifier(TEST_WEBHOOK_SECRET, null, 300, fixed);

        String expected = "0592d050fe5c5de14b7b8e663ab1a9c46b31b53a2b999c54c0c94728bd89eb41";
        assertThat(v.sign(ts, body)).isEqualTo(expected);
        byte[] raw = body.getBytes(StandardCharsets.UTF_8);
        assertThat(v.verify(ts, expected, raw)).contains(VerifiedKey.CURRENT);
        assertThat(v.verify(ts, expected.toUpperCase(), raw)).contains(VerifiedKey.CURRENT);   // hex 대소문자는 같은 바이트
    }

    /**
     * 서명은 <b>받은 바이트</b>에 대해 검증해야 한다. 바디를 문자열로 받으면 Spring 이 Content-Type 의 문자셋으로
     * 풀고, 서명 계산은 그걸 UTF-8 로 다시 인코딩한다 — 선언 문자셋이 실제 바이트와 다르면 왕복이 원본을 복원하지
     * 못해 <b>정상 서명이 401</b> 이 된다. 재전송해도 같은 바이트라 영구히 401 이다(W6D2 §9).
     *
     * <p>PG 가 한글 필드를 추가하고 헤더에 {@code charset=ISO-8859-1} 을 잘못 적은 경우. 서명은 PG 가 실제로 보낸
     * UTF-8 바이트 기준이다. 모르는 필드({@code storeName})는 매퍼가 무시하므로 서명 검증까지 간다.
     */
    @Test
    void 선언_문자셋이_바이트와_달라도_받은_바이트_그대로_검증한다_ISO_8859_1() {
        UUID mpid = createPendingPayment();
        String body = bodyWithStoreName("evt-latin1", mpid, "한글상점");
        String ts = now();

        HttpResponse<String> res = postWebhookRaw(body.getBytes(StandardCharsets.UTF_8),
                "application/json; charset=ISO-8859-1", ts, verifier.sign(ts, body));

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(status(mpid)).isEqualTo(PaymentStatus.PAID);
    }

    /**
     * 시크릿 교체 카운터의 배선. 키별 판정은 {@code WebhookSecretRotationTest} 가 보고, 여기선 컨트롤러가
     * <b>통과한 키로 한 번 세고 401 은 안 센다</b>는 것만 본다. 테스트 앱엔 구 키가 없어 previous 는 늘 수 없다 —
     * 그래도 시계열은 있어야 한다(0 과 "없음"을 가르려고 미리 등록했다).
     */
    @Test
    void 서명_통과는_키별로_세고_401은_안_센다() {
        UUID mpid = createPendingPayment();
        double current0 = verifiedCount("current");
        double previous0 = verifiedCount("previous");

        String body = body("evt-metric", mpid, AMOUNT);
        String ts = now();
        postWebhookRaw(body, ts, new WebhookSignatureVerifier("not-the-secret", 300).sign(ts, body));
        assertThat(verifiedCount("current")).isEqualTo(current0);

        assertThat(postSignedWebhook(body).statusCode()).isEqualTo(200);
        assertThat(verifiedCount("current")).isEqualTo(current0 + 1);
        assertThat(verifiedCount("previous")).isEqualTo(previous0);
    }

    // ---------- helpers ----------

    private double verifiedCount(String key) {
        return meterRegistry.get("settleflow.webhook.signature.verified").tag("key", key).counter().count();
    }

    private String body(String eventId, UUID mpid, long amount) {
        return String.format(
                "{\"eventId\":\"%s\",\"eventType\":\"APPROVED\",\"merchantPaymentId\":\"%s\","
                        + "\"pgTransactionId\":\"pg-tx-%s\",\"amount\":%d}",
                eventId, mpid, eventId, amount);
    }

    private String bodyWithStoreName(String eventId, UUID mpid, String storeName) {
        return String.format(
                "{\"eventId\":\"%s\",\"eventType\":\"APPROVED\",\"merchantPaymentId\":\"%s\","
                        + "\"pgTransactionId\":\"pg-tx-%s\",\"amount\":%d,\"storeName\":\"%s\"}",
                eventId, mpid, eventId, AMOUNT, storeName);
    }

    private String now() {
        return Long.toString(Instant.now().getEpochSecond());
    }

    private UUID createPendingPayment() {
        return tx.execute(s -> {
            Seller seller = new Seller("셀러");
            em.persist(seller);
            Product product = new Product(seller, "상품", AMOUNT, 100);
            em.persist(product);
            Order order = Order.create();
            order.addItem(product, 1);
            em.persist(order);
            Payment p = new Payment(order, AMOUNT);
            em.persist(p);
            em.flush();
            return p.getMerchantPaymentId();
        });
    }

    private PaymentStatus status(UUID mpid) {
        return tx.execute(s -> paymentRepository.findByMerchantPaymentId(mpid).orElseThrow().getStatus());
    }
}
