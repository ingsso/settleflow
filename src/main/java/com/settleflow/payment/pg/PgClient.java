package com.settleflow.payment.pg;

import java.util.UUID;

/**
 * PG(결제대행) 동기 승인 요청.
 *
 * 우리 측 식별자(merchantPaymentId)를 PG 에 넘긴다 — 웹훅이 이걸 되돌려줘야
 * 어느 결제인지 찾을 수 있다(W1D3). PG 가 발급하는 pg_transaction_id 는
 * 이 동기 응답이 아니라 <b>웹훅</b>으로 온다.
 */
public interface PgClient {

    /**
     * @return 확정 결과(승인/거절)
     * @throws PgTimeoutException 시간 안에 답이 없을 때 — 실패가 아니라 정보의 부재
     */
    PgResult approve(UUID merchantPaymentId, long amount);

    /**
     * 환불 요청(W3D2). {@code refund.requested} 를 소비한 consumer 가 부른다.
     *
     * <p><b>우리 측 {@code refundId} 를 넘긴다</b> — 승인이 {@code merchantPaymentId} 를 넘기는 것과
     * 같은 이유다(W1D3). PG 가 이걸 되돌려줘야 어느 환불 건인지 찾을 수 있고, 재시도가 같은
     * 식별자로 오면 PG 측에서도 중복을 걸러낼 근거가 된다.
     *
     * @param refundId 우리 {@code refund.id}
     * @param amount   환불 금액
     * @return 확정 결과(승인 + PG 환불번호 / 거절)
     * @throws PgTimeoutException 시간 안에 답이 없을 때 — 실패가 아니라 정보의 부재.
     *                            이걸 거절로 오인해 예약을 풀면 이중환불이 열린다.
     */
    PgRefundResult refund(long refundId, long amount);
}
