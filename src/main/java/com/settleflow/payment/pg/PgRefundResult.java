package com.settleflow.payment.pg;

/**
 * PG 환불 요청의 <b>확정</b> 결과(W3D2).
 *
 * <p>{@link PgResult} 와 달리 enum 이 아니라 sealed 다 — 승인과 거절이 <b>비대칭</b>이기 때문이다.
 * 환불 승인은 PG 가 발급한 {@code pgRefundId} 라는 <b>생성물</b>을 딸고 오고(대사가 외부 리포트와
 * 맞출 열쇠, W3D4), 거절은 사유조차 우리가 쓸 데가 없다. 한 record 에 담으면 {@code pgRefundId} 가
 * 거절 케이스에서 null 이 되고 "Approved 일 때만 읽어라"가 규율로 남는다. {@link RefundOutcome} 을
 * sealed 로 가른 것과 같은 판단 — <b>규율이 아니라 벽</b>.
 *
 * <p>타임아웃은 여기 없다. 결과가 아니라 <b>정보의 부재</b>라서 값이 아니라
 * {@link PgTimeoutException} 으로 표현한다(W1D1 §3). 이 구분이 없으면 "환불됐는데 응답만 못
 * 받은 것"과 "환불 거절"이 같은 칸에 들어가고, 후자로 오인해 예약을 풀면 이중환불이 난다.
 *
 * @see com.settleflow.payment.domain.RefundOutcome
 */
public sealed interface PgRefundResult permits PgRefundResult.Approved, PgRefundResult.Declined {

    /** 환불 확정. PG 가 발급한 환불 번호를 딸고 온다 — {@code refund.pg_refund_id} 가 된다. */
    record Approved(String pgRefundId) implements PgRefundResult {}

    /** 환불 거절. 돈은 나가지 않았다 — 예약을 풀고 장부엔 아무것도 안 적는다. */
    record Declined() implements PgRefundResult {}
}
