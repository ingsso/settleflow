package com.settleflow.ledger.domain;

public enum LedgerAccount {
    CASH,
    SELLER_PAYABLE,

    /** 판매에서 뗀 수수료. 돈은 고객에게서 왔고 셀러 몫에서 깎였다. */
    FEE_REVENUE,

    /**
     * 셀러가 낸 구독 이용료(W5D1). {@link #FEE_REVENUE} 와 섞지 않는다 —
     * 둘 다 플랫폼 수익이지만 <b>돈의 출처가 반대</b>다(판매 대금에서 뗀 것 vs 셀러가 직접 낸 것).
     * 한 계정에 넣으면 가를 방법이 사라진다.
     *
     * <p>{@code seller_id} 는 여기서도 "그 수익이 누구에게서 났나"를 뜻한다(V4 의 정의 그대로).
     */
    SUBSCRIPTION_REVENUE
}
