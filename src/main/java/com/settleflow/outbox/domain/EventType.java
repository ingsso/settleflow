package com.settleflow.outbox.domain;

/**
 * 이벤트 종류 ↔ Kafka 토픽 매핑(W3D1). 아웃박스엔 {@link #type} 문자열을 저장하고, 릴레이가
 * 이걸로 {@link #topic} 을 찾아 발행한다.
 *
 * <p><b>토픽은 타입별로 나눈다.</b> 같은 결제의 payment→refund <b>교차 순서 보장은 필요 없다</b> —
 * 각 소비자가 DB 상태를 재조회하지 이벤트 순서에 의존하지 않는다(대사는 배치 조회, Saga 는 payment 가
 * 이미 DB 에 있음). 같은 타입 내 per-payment 순서(부분환불 3건)는 {@code aggregate_id} 키가 같은
 * 파티션으로 보내 보장한다.
 */
public enum EventType {

    PAYMENT_COMPLETED("payment.completed", "settlement.payment.completed"),
    REFUND_REQUESTED("refund.requested", "settlement.refund.requested"),

    /**
     * refund.completed — PG 가 환불을 확정한 순간 발행된다(W3D2 부터).
     * {@code RefundConfirmService} 가 역분개 journal 을 쓰는 것과 같은 tx 에서 append 한다.
     */
    REFUND_COMPLETED("refund.completed", "settlement.refund.completed");

    private final String type;
    private final String topic;

    EventType(String type, String topic) {
        this.type = type;
        this.topic = topic;
    }

    public String type() {
        return type;
    }

    public String topic() {
        return topic;
    }

    public static EventType fromType(String type) {
        for (EventType e : values()) {
            if (e.type.equals(type)) return e;
        }
        throw new IllegalArgumentException("알 수 없는 event_type: " + type);
    }
}
