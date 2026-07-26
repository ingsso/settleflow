package com.settleflow.payment.domain;

/**
 * 환불 Saga 의 진행 상태(W3D2). "이 환불 시도가 어디까지 갔나"를 나타낸다.
 *
 * <p><b>{@link RefundStatus} 와 다른 것이다.</b> 저쪽은 결제 하나의 <i>환불 정도</i>(미환불/부분/전액)를
 * 금액에서 파생한 라벨이고(W2D4), 이쪽은 <i>개별 환불 건</i>이 PG 확인을 통과했는지다.
 *
 * <p><b>왜 4 상태인가.</b> {@link PaymentStatus} 가 4 상태인 이유와 같다(W1D1 §3) — 타임아웃은
 * 실패가 아니라 <b>정보의 부재</b>다. {@link #UNKNOWN} 을 {@link #FAILED} 와 같은 칸에 두면
 * "PG 가 환불을 거절했다"와 "PG 가 환불했는지 모르겠다"가 구분되지 않고, 후자에서 예약
 * ({@code payment.refunded_amount})을 풀어버리면 잔액이 되살아나 <b>이중환불</b>이 열린다.
 * W1D5 에서 재고를 성급히 복원해 이중판매 구멍이 열렸던 것과 같은 모양이고, 거기서 얻은 결론
 * ("복원은 시간의 문제가 아니라 사실 확인의 문제")이 여기에 그대로 온다.
 *
 * <pre>
 *   REQUESTED ──PG 성공──→ COMPLETED   역분개 journal 을 쓰고 refund.completed 발행
 *             ──PG 거절──→ FAILED      예약 해제(refunded_amount 감액). 장부는 건드리지 않는다
 *             ──무응답──→ UNKNOWN      아무것도 안 건드린다. 예약도 그대로 잡아둔다
 * </pre>
 *
 * <p>{@link #UNKNOWN} 은 종착지가 아니라 <b>보류</b>다. 진실을 채우려면 PG 에 재조회하는 쓸이
 * 배치가 필요하고, 그건 아직 없다(W3D2 이월 — W1D3 의 미결제 쓸이 배치와 같은 자리).
 */
public enum RefundPgStatus {

    /** 요청됐고 아직 PG 확인 전. 예약은 잡혀 있고 장부엔 아무것도 없다. */
    REQUESTED,

    /** PG 가 환불을 확정했다. 이 상태에서만 {@code journal_id} 가 채워진다(V7 CHECK). */
    COMPLETED,

    /** PG 가 환불을 거절했다. 예약이 풀렸고 장부엔 끝내 아무것도 안 적힌다. */
    FAILED,

    /** PG 가 답하지 않았다. 예약도 장부도 그대로 — 사실이 확인될 때까지 아무것도 하지 않는다. */
    UNKNOWN
}
