package com.settleflow.payment.domain;

/**
 * 환불 <b>요청</b>의 판정 결과(W2D4). {@link TransitionResult} 를 재사용하지 않는다 — 두 가지가 다르다.
 *
 * <p><b>1. IGNORED 가 없다.</b> 결제 승인은 같은 사건이 또 오면 중복이라 {@code IGNORED} 가 정답이었다
 * (W1D4 멱등). 환불은 아니다 — 5,000원 환불 요청이 두 번 오면 그건 중복이 아니라 <b>진짜 환불 2건</b>일
 * 수 있다(합쳐 10,000원). 금액만 보고 중복이라 단정할 근거가 없다.
 *
 * <p><b>2. 거부 사유가 여러 갈래다.</b> 호출부가 사유별로 다르게 움직여야 한다 — 특히
 * {@link #NOT_REFUNDABLE}(영영 안 됨)과 {@link #PAYMENT_UNKNOWN}(조회 후 될 수도 있음)은
 * 재시도 가능성이 정반대다.
 *
 * <p><b>왜 예외가 아니라 반환값인가.</b> PENDING 결제로 환불 요청이 오는 건 버그가 아니라
 * <b>정상적으로 예상되는 외부 입력</b>이다. 예상되는 입력을 예외로 처리하지 않는다 —
 * W1D4 에서 중복 웹훅에 예외 대신 {@code TransitionResult} 를 돌려주기로 한 판단과 같은 선.
 * (반면 {@link Payment#refundStatus()} 는 <i>조회</i>라 정의역 밖 호출이 곧 프로그래밍 오류 → 예외.)
 */
public enum RefundResult {

    /** 환불 반영됨. {@code refunded_amount} 누적 + {@code refund} 행 1건 생성. */
    ACCEPTED,

    /**
     * 환불 자격 없음 — {@code PENDING}(아직 돈을 안 받음) 또는 {@code FAILED}(받은 적이 없음).
     * 재시도해도 결과가 안 바뀐다.
     */
    NOT_REFUNDABLE,

    /**
     * 결제 상태가 {@code UNKNOWN} — <b>실패가 아니라 정보의 부재</b>(W1D1 §3).
     * 승인됐는지 모르는 돈은 돌려줄 수 없다. 다만 PG 에 조회해서 상태가 확정되면 환불 가능해질 수 있어
     * {@link #NOT_REFUNDABLE} 과 구분한다 — 호출부가 "조회 후 재시도"를 고를 수 있게.
     */
    PAYMENT_UNKNOWN,

    /**
     * 잔액 초과 — 조건부 UPDATE 가 0행을 반환했다.
     * 전액 환불된 건에 추가 환불을 요청한 경우도 여기로 떨어진다(별도 사유로 나누지 않는다).
     */
    EXCEEDS_REMAINING,

    /** 0원·음수 환불. {@code chk_refund_amount CHECK (amount > 0)} 의 앱 계층 짝. */
    INVALID_AMOUNT
}
