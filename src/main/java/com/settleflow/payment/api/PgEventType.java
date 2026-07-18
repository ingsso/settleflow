package com.settleflow.payment.api;

/**
 * 웹훅이 실어오는 사건. 동기 타임아웃과 달리 웹훅은 늘 <b>확정</b>(승인/거절)을 나른다 —
 * 타임아웃은 "응답이 안 온 것"이라 애초에 웹훅으로 배달될 수 없다(W1D1 전이표의 들어오는 사건 셋 중,
 * 웹훅 경로로 오는 건 승인·거절 둘뿐).
 */
public enum PgEventType {
    APPROVED,
    DECLINED
}
