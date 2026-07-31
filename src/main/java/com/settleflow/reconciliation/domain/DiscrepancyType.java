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
     * <p><b>이 타입이 가리키는 게 W3D5 에서 바뀌었다.</b> 원래는 W3D3 §4 의 증발 구멍(경계 직전에
     * 찍히고 배치 뒤에 커밋된 journal 줄이 어느 정산에도 안 들어감)이 주된 원인이었는데,
     * {@code V10} 의 진행 축이 그 구멍을 닫았다 — 이제 그런 줄은 표식이 남아 다음 배치가
     * <b>제 날짜로 주워간다</b>. 그래서 뜨더라도 <b>일시적</b>이다(다음 배치가 메운다).
     *
     * <p>지금 이게 지속적으로 뜨면 뜻은 <b>"그 날짜의 배치가 아예 안 돌았다"</b> 쪽이다 —
     * 배치 중단, 또는 {@code trg_journal_settlement_pending} 이 죽어 정산 대상이 잡히지 않는 상황.
     * 후자를 잡으려고 {@code ReconciliationRunner} 가 최근 창을 무조건 본다(날짜 출처 ③).
     *
     * <p><b>그래서 "며칠째냐"가 신호다.</b> 한 번 떴다는 사실 자체는 약하고,
     * {@code ReconciliationRecorder.openSince} 가 세는 지속 일수가 판단 근거다.
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
