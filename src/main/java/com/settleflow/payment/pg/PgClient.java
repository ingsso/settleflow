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
}
