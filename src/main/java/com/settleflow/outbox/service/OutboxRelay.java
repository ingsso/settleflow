package com.settleflow.outbox.service;

import com.settleflow.outbox.domain.EventType;
import com.settleflow.outbox.domain.OutboxEvent;
import com.settleflow.outbox.repository.OutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.ExecutionException;

/**
 * 폴링 릴레이(W3D1). 미발행 아웃박스 행을 id 순서로 긁어 Kafka 로 발행하고 {@code published_at} 을
 * 채운다. Quartz 가 주기적으로 부른다({@code OutboxRelayJob}).
 *
 * <h3>at-least-once</h3>
 * 각 행을 <b>Kafka ack 확인 뒤에만</b>({@code send().get()}) published 로 표시한다. 발행 성공 후
 * 커밋 전에 죽으면 다음 폴링에서 재발행된다 — 그래서 <b>소비자 멱등이 필수</b>다({@code event_id}
 * 로 중복 제거, W1D4 SETNX 의 소비자 판). 발행이 실패하면 예외 → tx 롤백 → published_at 이 안 차
 * 다음에 다시 시도된다.
 *
 * <p><b>배치 단위 tx 의 대가</b>: 배치 중간에 한 건이 실패하면 그 tx 가 통째로 롤백돼, 앞서 발행된
 * 건들도 published 표시가 취소되어 다음 폴링에서 재발행된다(중복 폭이 배치 크기까지 커질 수 있다).
 * 단순함을 택했고, 중복은 소비자 멱등이 흡수한다. 폭을 1 로 줄이려면 행별 tx 로 쪼개면 된다(W3 이후).
 */
@Service
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final int BATCH_SIZE = 100;

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    public OutboxRelay(OutboxRepository outboxRepository, KafkaTemplate<String, String> kafkaTemplate) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
    }

    /** 한 배치를 발행한다. 발행한 건수를 돌려준다(0 이면 큐가 비었다). */
    @Transactional
    public int publishBatch() {
        List<OutboxEvent> batch = outboxRepository.findUnpublished(PageRequest.of(0, BATCH_SIZE));

        for (OutboxEvent event : batch) {
            EventType type = EventType.fromType(event.getEventType());
            try {
                // 블록 — ack 를 받은 뒤에만 published 로 표시한다(at-least-once). 키는 aggregateId(파티션 순서).
                kafkaTemplate.send(type.topic(), String.valueOf(event.getAggregateId()), event.getPayload())
                        .get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("아웃박스 릴레이 중단 eventId=" + event.getEventId(), e);
            } catch (ExecutionException e) {
                // 발행 실패 → 예외로 tx 롤백 → published_at 안 참 → 다음 폴링에서 재시도
                throw new IllegalStateException("Kafka 발행 실패 eventId=" + event.getEventId(), e);
            }
            event.markPublished();
        }

        if (!batch.isEmpty()) {
            log.debug("아웃박스 {}건 발행", batch.size());
        }
        return batch.size();
    }
}
