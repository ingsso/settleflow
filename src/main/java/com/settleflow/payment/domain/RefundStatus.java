package com.settleflow.payment.domain;

/**
 * 환불 진행 라벨(W2D4). <b>저장하지 않는다</b> — {@code refunded_amount} 와 {@code amount} 에서
 * 매번 파생한다({@link Payment#refundStatus()}).
 *
 * <p><b>이건 상태머신의 노드가 아니다.</b> 상태머신은 <i>(현재 상태, 사건) → 다음 상태 또는 거부</i>이고,
 * 그 값어치는 바꿔주는 게 아니라 <b>거부하는</b> 데 있다({@link Payment#onApproved()} 의 CONFLICT 가지).
 * 이 enum 은 사건을 받지도 않고 자기가 어디서 왔는지도 모른다 — 금액 한 쌍에 이름을 붙일 뿐이라
 * 아무것도 못 막는다. 막는 일은 {@code RefundService} 의 가드와
 * {@code PaymentRepository.addRefundedAmount} 의 조건부 UPDATE 가 나눠 맡는다.
 *
 * <p><b>왜 저장하지 않나.</b> 저장하면 {@code refunded_amount} 와 라벨이라는 진실이 둘로 갈라진다 —
 * W1D3 에서 "재고의 진실이 Redis 와 DB 로 갈라진다"며 Redis 락을 폐기한 것과 같은 모양.
 * 파생이면 {@code refunded_amount} 가 환불의 유일한 진실원본이고, 그래서 그 컬럼의 원자적 갱신이
 * 금액뿐 아니라 <b>라벨의 정확성까지</b> 책임진다.
 *
 * <p><b>덤:</b> 파생이라 "REFUNDED 에서 추가 환불 금지"를 위한 별도 가드가 필요 없다.
 * {@code refunded_amount == amount} 면 조건부 UPDATE 의 {@code + x <= amount} 가
 * 어떤 양수 x 에도 거짓이라 자동으로 막힌다.
 *
 * <p><b>이월:</b> 지금은 앱에서만 계산되므로 SQL 로 못 묻는다(A1). W3D3 정산 배치나 W3D4 대사가
 * 이 라벨로 SQL 필터를 걸어야 해지면, Postgres {@code GENERATED ALWAYS AS ... STORED} 컬럼(A2)으로
 * 승격한다 — 그래도 DB 가 계산을 강제하므로 진실은 여전히 하나다.
 */
public enum RefundStatus {

    /** 환불 이력 없음. {@code refunded_amount == 0} */
    NOT_REFUNDED,

    /** 일부 환불됨. {@code 0 < refunded_amount < amount} */
    PARTIAL_REFUNDED,

    /** 전액 환불됨. {@code refunded_amount == amount} — 더 이상 환불 못 받는다 */
    REFUNDED
}
