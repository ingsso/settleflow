package com.settleflow.payment.pg;

/**
 * PG 동기 응답의 <b>확정</b> 결과.
 *
 * 타임아웃은 여기 없다 — 타임아웃은 결과가 아니라 <b>정보의 부재</b>라서
 * 값이 아니라 {@link PgTimeoutException} 예외로 표현한다(W1D1 §3).
 * "승인됐는데 응답만 못 받은 것"과 "거절"을 같은 칸에 두면 이중청구가 난다.
 */
public enum PgResult {
    APPROVED,
    DECLINED
}
