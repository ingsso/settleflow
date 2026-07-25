package com.settleflow.outbox.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.settleflow.outbox.domain.EventType;
import com.settleflow.outbox.domain.OutboxEvent;
import com.settleflow.outbox.repository.OutboxRepository;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 이벤트를 아웃박스에 append 한다(W3D1). <b>{@code @Transactional} 이 없다</b> — 호출자(결제 전이·
 * 환불)의 트랜잭션에 얹혀야 하기 때문이다. 그래야 event INSERT 가 journal INSERT 와 같은 tx 에서
 * 커밋돼 둘이 갈라지지 않는다. 트랜잭션 밖에서 부르면 그건 프로그래밍 오류다.
 *
 * <p>ObjectMapper 는 주입받지 않고 직접 만든다 — Boot 4 는 webmvc 만으론 ObjectMapper 빈을 자동
 * 구성하지 않는다. jsr310 을 달아 {@code OffsetDateTime} 을 ISO 문자열로 쓰고, 타임스탬프 숫자화는 끈다.
 */
@Component
public class OutboxWriter {

    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    public OutboxWriter(OutboxRepository outboxRepository) {
        this.outboxRepository = outboxRepository;
    }

    /**
     * @param aggregateId 애그리거트 id(= paymentId). Kafka 메시지 키가 된다.
     * @param type        이벤트 종류
     * @param payload     fat 스냅샷. 직렬화 실패는 <b>우리가 만든 페이로드</b>의 문제이므로 예외(프로그래밍 오류).
     */
    public void append(long aggregateId, EventType type, Object payload) {
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("이벤트 직렬화 실패: " + type.type(), e);
        }
        outboxRepository.save(new OutboxEvent(aggregateId, type.type(), UUID.randomUUID(), json));
    }
}
