package com.settleflow.payment.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.settleflow.payment.domain.RefundPgStatus;
import com.settleflow.payment.event.RefundRequestedEvent;
import com.settleflow.payment.service.RefundConfirmService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * {@code settlement.refund.requested} 소비자(W3D2). 환불 Saga 의 두 번째 다리를 트리거한다.
 *
 * <h3>이 클래스가 하는 일은 배달뿐이다</h3>
 * 역직렬화하고 {@link RefundConfirmService#confirm} 을 부르는 게 전부다. 판단·트랜잭션·멱등은
 * 전부 서비스에 있다. 그래야 테스트가 Kafka 를 거치지 않고 서비스를 직접 불러 결정적으로 검증할
 * 수 있다 — W3D1 에서 {@code OutboxRelay.publishBatch()} 를 직접 부른 것과 같은 구조.
 *
 * <h3>@Transactional 이 없는 이유</h3>
 * 여기서 tx 를 열면 PG 호출이 그 안에 들어간다. {@link RefundConfirmService} 가 PG 호출을 tx 밖에
 * 두려고 tx0/tx1 로 쪼개놨는데(W1D1 §2 함정1), 리스너가 바깥에서 tx 를 열면 그 설계가 무의미해진다.
 *
 * <h3>오프셋 커밋 = at-least-once</h3>
 * 리스너가 정상 반환하면 오프셋이 커밋된다. 예외가 나가면 커밋되지 않아 <b>같은 이벤트가 다시
 * 배달된다</b> — 그래서 {@code confirm()} 은 PG 거절 같은 정상적 사업 결과에 예외를 던지지 않는다
 * (던지면 무한 재배달이 된다). 재배달되는 건 진짜 장애(DB 다운 등)뿐이고, 그때 중복 처리는
 * {@code confirm()} 의 두 겹 멱등이 흡수한다.
 *
 * <p><b>재시도 정책은 {@link RefundSagaErrorHandlingConfig} 에 있다</b>(W4D5, W3D2 이월 청산).
 * 상한 없는 지수 백오프이고, DLT 는 만들지 않았다 — 근거는 그 javadoc.
 *
 * <p>⚠️ <b>이 자리에 W4D5 까지 틀린 설명이 적혀 있었다</b>: <i>"지금은 장애 시 무한 재시도라,
 * 영구적으로 실패하는 이벤트 하나가 파티션을 막을 수 있다"</i>. 에러 핸들러 빈이 없어 Spring Kafka
 * 기본값({@code FixedBackOff(0ms, 9)} + 건너뛰는 recoverer)이 돌고 있었으므로 실제로는
 * <b>무한도 아니고 막히지도 않았다 — 10번 즉시 재시도 후 오프셋을 밀어 환불을 버렸다.</b>
 * 검증 없이 적은 단정이 두 세션 동안 남아 있었고, 그 위에 W4D1 이 재배달 설계를 얹었다.
 *
 * <h3>PG 차단기 개방 = 의도된 재배달(W4D1)</h3>
 * {@link RefundConfirmService#confirm} 이 {@code CallNotPermittedException} 을 내보내면 여기서
 * <b>잡지 않는다</b> — 잡으면 오프셋이 밀려 환불이 조용히 사라진다. 예외가 나가야 오프셋이
 * 안 밀리고 같은 이벤트가 다시 온다. 위 [POISON]·[ORPHAN] 과 갈리는 지점은 <b>재시도해서
 * 달라지는가</b> 다: 저 둘은 몇 번을 다시 해도 같은 결과라 넘기지만, 차단기 개방은
 * <b>시한부</b>라 기다리면 풀린다.
 *
 * <p><b>W4D5 에 갚았다.</b> 그리고 갚으면서 알게 된 건 구멍이 "뜨겁게 돈다"가 아니었다는 것이다 —
 * 기본 재시도가 간격 0ms 로 10번이라 <b>회로의 30초 창을 못 넘고 그대로 유실</b>됐다. 뜨거운 게
 * 문제가 아니라 <b>식기 전에 포기하는 게</b> 문제였다. 지금은 상한 없는 지수 백오프라 회로가
 * 풀릴 때까지 기다린다({@link RefundSagaErrorHandlingConfig}).
 */
@Component
public class RefundRequestedConsumer {

    private static final Logger log = LoggerFactory.getLogger(RefundRequestedConsumer.class);

    /**
     * {@code OutboxWriter} 와 같은 이유로 직접 만든다 — Boot 4 는 webmvc 만으론 ObjectMapper 빈을
     * 자동 구성하지 않는다. jsr310 으로 ISO 문자열 {@code OffsetDateTime} 을 읽는다.
     */
    private final ObjectMapper objectMapper = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .build();

    private final RefundConfirmService refundConfirmService;

    public RefundRequestedConsumer(RefundConfirmService refundConfirmService) {
        this.refundConfirmService = refundConfirmService;
    }

    @KafkaListener(topics = "settlement.refund.requested", groupId = "settlement-refund-saga")
    public void onRefundRequested(String payload) {
        RefundRequestedEvent event;
        try {
            event = objectMapper.readValue(payload, RefundRequestedEvent.class);
        } catch (Exception e) {
            // 우리가 만든 페이로드를 우리가 못 읽는다 — 재시도해도 안 바뀐다(프로그래밍/스키마 오류).
            // 예외를 던지면 무한 재배달로 파티션이 막히므로, 기록하고 넘긴다.
            log.error("[POISON] refund.requested 역직렬화 실패 — 건너뜀. payload={}", payload, e);
            return;
        }

        RefundPgStatus status;
        try {
            status = refundConfirmService.confirm(event.refundId());
        } catch (IllegalArgumentException e) {
            // 이벤트가 가리키는 refund 행이 없다. 아웃박스는 refund 행과 같은 tx 로 쓰이므로
            // (RefundService) 정상 경로에선 불가능하다 — 누가 행을 지웠거나 DB 가 유실됐다는 뜻이다.
            // 재시도해도 안 바뀌는데 예외를 내보내면 오프셋이 안 밀려 파티션 전체가 이 한 건에 막힌다.
            // 기록하고 넘긴다 — 위 [POISON] 과 같은 판단.
            log.error("[ORPHAN] refund.requested 가 없는 refundId={} 를 가리킨다 — 건너뜀. 사람이 확인해야 함",
                    event.refundId(), e);
            return;
        }
        log.info("환불 확정 처리 refundId={} paymentId={} → {}",
                event.refundId(), event.paymentId(), status);
    }
}
