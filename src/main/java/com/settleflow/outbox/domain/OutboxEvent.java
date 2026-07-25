package com.settleflow.outbox.domain;

import jakarta.persistence.*;
import lombok.Getter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 아웃박스 행(W3D1). 이벤트가 장부와 <b>같은 트랜잭션</b>에서 INSERT 되어 dual-write 를 없앤다.
 *
 * <p><b>거의 append-only 다 — 예외는 {@code published_at} 하나.</b> 릴레이가 발행 후 그 한 칸만
 * UPDATE 한다({@link #markPublished()}). 이게 장부의 append-only 규율과 부딪히지 않는 이유는
 * 아웃박스가 <b>재무 진실이 아니라 전송 큐</b>이기 때문 — 진실은 {@code journal}/{@code ledger_entry}
 * 에 있고, 여긴 그걸 Kafka 로 나르는 파이프다(V6 주석).
 *
 * <p>{@code payload} 는 fat 스냅샷이다. 발행 지점에서 <b>방금 만든 journal 의 줄을 거울처럼 읽어</b>
 * 만들어지므로(재계산이 아니라) 이벤트가 장부와 갈라질 수 없다.
 */
@Getter
@Entity
@Table(name = "outbox")
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "aggregate_id", nullable = false, updatable = false)
    private Long aggregateId;

    @Column(name = "event_type", nullable = false, updatable = false, length = 64)
    private String eventType;

    @Column(name = "event_id", nullable = false, unique = true, updatable = false)
    private UUID eventId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, updatable = false)
    private String payload;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private OffsetDateTime occurredAt;

    @Column(name = "published_at")
    private OffsetDateTime publishedAt;

    protected OutboxEvent() {   // JPA 전용
    }

    public OutboxEvent(Long aggregateId, String eventType, UUID eventId, String payload) {
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.eventId = eventId;
        this.payload = payload;
        this.occurredAt = OffsetDateTime.now();
    }

    /** 릴레이가 Kafka 발행을 확인한 뒤 호출한다. 유일하게 허용된 변경. */
    public void markPublished() {
        this.publishedAt = OffsetDateTime.now();
    }
}
