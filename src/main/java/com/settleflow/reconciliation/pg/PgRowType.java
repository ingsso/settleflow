package com.settleflow.reconciliation.pg;

/**
 * PG 리포트 행의 종류(W3D4). 승인·환불을 <b>한 파일</b>에 섞고 이 칸으로 가른다.
 *
 * <p>두 파일로 나누는 길도 있었다. 한 파일로 둔 이유는 <b>정산 리포트가 "그날 PG 와 우리 사이에
 * 움직인 돈의 목록"</b>이고, 방향이 다르다고 목록이 둘이 되지는 않기 때문이다. 파일이 둘이면
 * "이 날짜 리포트"라는 단위가 둘로 갈라진다.
 */
public enum PgRowType {

    /** 고객 → 우리(PG 경유). {@code grossAmount} = 승인금액 */
    PAYMENT,

    /** 우리 → 고객(PG 경유). {@code grossAmount} = 환불금액, {@code pgFee} = 0 */
    REFUND
}
