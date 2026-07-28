package com.settleflow.reconciliation.domain;

/**
 * 불일치의 종류(W3D4). <b>원인이 서로 달라서 가른다</b> — 같은 "안 맞음"이 아니다.
 *
 * <p>이 셋은 <b>내부 축(장부 ↔ 정산)</b>의 것이다. 계획서의 외부 축(내부 장부 ↔ PG 리포트)은
 * 아직 없고, 거기엔 "리포트엔 있는데 우리에게 없음" 같은 다른 갈래가 붙는다.
 */
public enum DiscrepancyType {

    /**
     * 양쪽에 다 있는데 금액이 다르다. <b>집계 규칙이 틀렸다는 신호.</b>
     *
     * <p>W3D3 §8 의 부호 버그({@code ELSE -le.amount} → {@code ELSE le.amount})가 여기로 나온다.
     * 그게 이 대사를 <b>독립적으로 계산하게 만든 이유</b>다 — 배치와 같은 SQL 을 재실행했다면
     * 둘 다 똑같이 틀려서 영원히 일치했을 것이다(가계부를 두 번 읽는 것과 같다).
     */
    AMOUNT_MISMATCH,

    /**
     * 재구성엔 있는데 {@code settlement} 행이 없다.
     *
     * <p><b>W3D3 §4 에서 알고 뚫은 구멍이 여기로 나타난다.</b> 경계 직전에 찍히고 배치 뒤에 커밋된
     * journal 줄은 어느 정산에도 안 들어가 증발하는데, 대사는 나중에 돌아서 그 결제를 본다.
     * "배치가 아예 안 돈 날"도 같은 모양이다.
     */
    MISSING_IN_SETTLEMENT,

    /**
     * {@code settlement} 엔 있는데 재구성 결과가 없다.
     *
     * <p>정상 경로로는 나오기 어렵다 — 장부에 줄이 있었으니 그 결제/환불도 있어야 한다. 나온다면
     * 누가 {@code settlement} 를 손으로 고쳤거나, 대사의 날짜 기준이 배치와 어긋난 것이다
     * (자정 경계의 밀리초 차이 — {@code RefundRepository.findCompletedInRange} 참고).
     */
    MISSING_IN_RECONSTRUCTION
}
