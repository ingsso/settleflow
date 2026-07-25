package com.settleflow.payment.event;

import java.time.OffsetDateTime;

/**
 * refund.completed — <b>스키마만 설계됨</b>(W3D1). 발행 지점(producer)은 아직 없다.
 *
 * <p>현재 환불은 동기라 "완료"를 발행할 자리가 없다. 실제 PG 환불이 확인되는 순간을 표현하는
 * 이벤트이고, 그 자리는 W3D2 의 consumer/보상 트랜잭션 흐름에서 생긴다({@code refund.requested} 를
 * 소비해 PG 를 호출하고, 성공하면 이 이벤트를 발행). {@code pgRefundId} 는 그때 PG 가 되돌려주는 번호.
 */
public record RefundCompletedEvent(
        long paymentId,
        long refundId,
        String pgRefundId,
        long amount,
        OffsetDateTime occurredAt) {
}
