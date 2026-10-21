package com.settleflow.payment.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.settleflow.payment.service.PaymentNotFoundException;
import com.settleflow.payment.service.PaymentTransitionService;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.OffsetDateTime;

/**
 * PG 웹훅 수신 + 멱등 처리(W1D4 설계 ②).
 *
 * <pre>
 *   서명 검증       ── 실패 ─→ 401  (멱등키를 안 남긴다)
 *        │ 통과
 *   파싱·검증       ── 실패 ─→ 400
 *        │
 *   SETNX(eventId)  ── 실패(이미 있음) ─→ 200 OK  (중복. 아무 일도 안 함)
 *        │ 성공(처음)
 *        ▼
 *   전이 dispatch (PaymentTransitionService, 낙관적 락)
 *        ▼
 *   200 OK
 * </pre>
 *
 * <p><b>SETNX 를 전이보다 먼저</b> — 두 번째 재전송이 PENDING 을 읽어보기도 전에 문전박대해야
 * lost update 가 안 생긴다. SETNX 는 fast-path 일 뿐이고, 그걸 빠져나온 중복은
 * 전이 서비스의 상태 가드+낙관적 락이 막는다("두 겹").
 *
 * <p><b>서명 검증을 SETNX 보다 먼저</b> — 뒤에 두면 위조 요청이 진짜 {@code eventId} 를 담아 먼저 도착해
 * 멱등키를 선점하고, 나중에 온 진짜 통보가 "중복"으로 200 처리되어 <b>버려진다</b>. 위조가 결제 반영을
 * 막는 공격이 된다. 그래서 순서는 서명 → SETNX → 전이.
 *
 * <p><b>바디를 {@code String} 으로 받는다</b> — {@code @RequestBody PgWebhookRequest} 로 받으면 이미
 * 파싱된 객체라 서명 대상인 원본을 잃는다(공백·필드 순서만 달라도 서명이 안 맞는다). 검증을 통과한
 * 뒤에야 파싱한다.
 *
 * <p><b>항상 200 OK</b>(서명을 통과한 요청에 한해) — 중복도, 없는 결제도 정상 응답이다. non-2xx 를 주면 PG 가 몇 시간
 * 재전송한다(W1D1 §4). "이미 처리했음"은 에러가 아니라 성공이다.
 */
@RestController
@RequestMapping("/webhooks/pg")
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    /**
     * 멱등키 TTL. PG 재전송 창(수 시간)보다 넉넉하게 둔다.
     * 성공 후 크래시로 200 을 못 돌려줘도, 재전송이 SETNX 실패 → 200 으로 멈춘다(일은 이미 커밋됨).
     * 반대로 커밋 <b>전</b> 크래시로 키만 남는 경우는 TTL 만료 + 미결제 쓸이 배치가 회수한다.
     */
    private static final Duration DEDUP_TTL = Duration.ofHours(24);

    /**
     * {@code OutboxWriter} 와 같은 이유로 직접 만든다 — Boot 4 는 webmvc 만으론 ObjectMapper 빈을
     * 구성하지 않는다. <b>모르는 필드는 무시한다</b>: 이전엔 {@code @RequestBody} 가 Spring 의 매퍼로
     * 파싱해 PG 가 필드를 추가해도 통과했는데, 직접 만든 매퍼의 기본값(실패)을 그대로 두면 그 순간
     * 모든 웹훅이 400 이 된다.
     */
    private final ObjectMapper objectMapper = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private final StringRedisTemplate redis;
    private final PaymentTransitionService transitionService;
    private final WebhookSignatureVerifier verifier;
    private final Validator validator;

    public WebhookController(StringRedisTemplate redis,
                            PaymentTransitionService transitionService,
                            WebhookSignatureVerifier verifier,
                            Validator validator) {
        this.redis = redis;
        this.transitionService = transitionService;
        this.verifier = verifier;
        this.validator = validator;
    }

    @PostMapping
    public ResponseEntity<Void> receive(
            @RequestHeader(value = "X-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-Signature", required = false) String signature,
            @RequestBody String rawBody) {

        // ① 발신자 검증 — SETNX 보다 먼저. 401 은 멱등키를 남기지 않는다.
        if (!verifier.verify(timestamp, signature, rawBody)) {
            log.warn("웹훅 서명 검증 실패");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        PgWebhookRequest req = parseAndValidate(rawBody);
        if (req == null) {
            return ResponseEntity.badRequest().build();
        }

        String key = "webhook:dedup:" + req.eventId();

        Boolean firstTime = redis.opsForValue().setIfAbsent(key, "1", DEDUP_TTL);
        if (!Boolean.TRUE.equals(firstTime)) {
            return ResponseEntity.ok().build();   // 중복 — 관문에서 막힘
        }

        try {
            dispatch(req);
        } catch (PaymentNotFoundException e) {
            // 위조 웹훅이거나 버그. 재전송해도 소용없으니 키를 남기고 200 OK.
            log.warn("웹훅이 없는 결제를 가리킴: {}", e.getMessage());
        } catch (RuntimeException e) {
            // 일시적 실패(DB 등) 추정 — 키를 지워 재전송이 다시 처리하게 한다.
            // 멱등키가 '끝나지 않은 일'을 영영 막아버리지 않도록.
            redis.delete(key);
            throw e;   // 500 → PG 가 재전송
        }

        return ResponseEntity.ok().build();
    }

    /** {@code @Valid} 가 하던 일을 직접 한다. 파싱 실패·제약 위반이면 {@code null}(→ 400). */
    private PgWebhookRequest parseAndValidate(String rawBody) {
        PgWebhookRequest req;
        try {
            req = objectMapper.readValue(rawBody, PgWebhookRequest.class);
        } catch (JsonProcessingException e) {
            log.warn("웹훅 바디 파싱 실패: {}", e.getOriginalMessage());
            return null;
        }
        if (req == null || !validator.validate(req).isEmpty()) {
            log.warn("웹훅 바디 제약 위반");
            return null;
        }
        return req;
    }

    /**
     * <b>종류를 안 가른다</b>(W5D3). 주문 결제인지 구독 청구인지는 {@code merchantPaymentId} 로
     * 행을 읽어야 알 수 있고, 그 읽기는 전이 서비스가 이미 한다 — 여기서 또 읽으면 조회가
     * 두 번이 되고 도메인 지식이 컨트롤러로 샌다.
     *
     * <p>W5D2 까지 여기가 {@code applyApproved} 를 무조건 불렀고, 구독 청구 웹훅이 오면
     * {@code payment.getOrder()} 가 NULL 이라 NPE → 500 이었다. 그리고 위 {@code catch} 가
     * 멱등키를 지우므로 PG 가 재전송했고, <b>영구 결함이라 매번 다시 500</b> 이었다.
     */
    private void dispatch(PgWebhookRequest req) {
        switch (req.eventType()) {
            case APPROVED -> transitionService.applyApprovedFromWebhook(
                    req.merchantPaymentId(), req.pgTransactionId());
            case DECLINED -> transitionService.applyDeclinedFromWebhook(
                    req.merchantPaymentId(), req.pgTransactionId(), OffsetDateTime.now());
        }
    }
}
