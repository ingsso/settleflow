package com.settleflow.subscription.domain;

/**
 * 구독 <b>계약</b>의 상태. 두 값뿐이고 단방향이다 — {@code ACTIVE → CANCELED}.
 *
 * <p><b>PAST_DUE 가 여기 없다.</b> 계획서는 {@code ACTIVE → PAST_DUE → CANCELED} 를 요구했지만
 * 그 enum 은 축이 둘 섞여 있다: {@code CANCELED} 는 계약 사실(사람이 해지했다)이고
 * {@code PAST_DUE} 는 청구 건강도(미해결 실패 주기가 있다)다. {@code ACTIVE} 한 칸이
 * "계약이 살아있다"와 "연체가 없다"를 겸한다 — W3D4({@code refunded_amount}) ·
 * W3D5({@code created_at}) · W4D5({@code Sort} 단언)에서 세 번 갈라진 그 모양이라
 * <b>갈라지기 전에</b> 갈랐다. 연체는 {@link SubscriptionHealth} 가 주기 행들에서 파생한다.
 *
 * <p>선례는 payment 안에 있다: {@code Payment.status}(사실, 저장) 와
 * {@code Payment.refundStatus()}(라벨, 파생)가 다른 축이라고 명시돼 있다(W3D4).
 *
 * <p><b>이 축이 되돌아오지 않는 게 중요하다.</b> 단방향 DAG 라 "이미 종착지면 IGNORED" 라는
 * W1D4 멱등 backbone 이 여기선 그대로 산다. 되돌아오는 축(연체)을 뽑아냈기 때문에 그렇다.
 */
public enum SubscriptionStatus {
    ACTIVE, CANCELED
}
