package com.settleflow.payment.api;

import com.settleflow.payment.service.PaymentNotFoundException;
import com.settleflow.payment.service.PaymentTransitionService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.OffsetDateTime;

/**
 * PG 웹훅 수신 + 멱등 처리(W1D4 설계 ②).
 *
 * <pre>
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
 * <p><b>항상 200 OK</b> — 중복도, 없는 결제도 정상 응답이다. non-2xx 를 주면 PG 가 몇 시간
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

    private final StringRedisTemplate redis;
    private final PaymentTransitionService transitionService;

    public WebhookController(StringRedisTemplate redis,
                            PaymentTransitionService transitionService) {
        this.redis = redis;
        this.transitionService = transitionService;
    }

    @PostMapping
    public ResponseEntity<Void> receive(@Valid @RequestBody PgWebhookRequest req) {
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
