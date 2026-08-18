package com.settleflow.subscription.domain;

/**
 * 청구 주기 1회의 상태. 상태머신의 실체가 여기 있다.
 *
 * <pre>
 *   PENDING ──성공──▶ PAID        (종착지)
 *      │
 *      └──실패──▶ FAILED ──재시도 성공──▶ PAID
 *                   │  ▲
 *                   │  └─ 실패(시도가 더 남음) ── 자기 자신으로 (next_retry_at 만 밀린다)
 *                   │
 *                   └──상한 초과──▶ ABANDONED  (종착지)
 * </pre>
 *
 * <p><b>순환이 없다.</b> {@code FAILED → PAID} 는 A→B→C 꼴이라 DAG 다. 되돌아오는 화살표
 * ({@code PAST_DUE → ACTIVE})를 구독 층에서 걷어내 여기로 내린 결과가 이것이다.
 *
 * <h3>왜 FAILED 와 ABANDONED 를 가르나</h3>
 * 한 칸으로 두면 "재시도 대기중"과 "포기함"을 {@code next_retry_at IS NULL} 로 갈라야 하고,
 * 그건 이 파일이 존재하는 이유(칸 하나가 둘을 겸함)와 같은 모양이다. W4 회고가 W5 진입 전
 * 질문으로 적어둔 <i>"포기를 무엇으로 표현할 것인가"</i> 의 답이 {@code ABANDONED} 다.
 *
 * <h3>FAILED 의 자기 루프에 관하여</h3>
 * 시도 2회가 연달아 실패하면 상태는 {@code FAILED} 로 같지만 <b>서로 다른 사건</b>이다
 * ({@code next_retry_at} 이 밀려야 한다). 그래서 {@code BillingCycle.onPaymentFailed} 는
 * 중복 실패를 {@code IGNORED} 로 걸러낼 수 없다 — 상태만으로는 "새 실패"와 "같은 실패의
 * 재통보"를 못 가르기 때문이다.
 *
 * <p><b>그 걸러내기는 한 층 아래에서 일어난다.</b> 시도 하나 = {@code payment} 행 하나이고,
 * {@code payment} 는 종착지 있는 전이표를 갖고 있어 같은 시도의 두 번째 통보를
 * {@code IGNORED} 로 흡수한다. 즉 이 자기 루프가 안전한 것은 <b>시도 층이 별도 행이기
 * 때문</b>이지 그 자체로 안전해서가 아니다 — 시도를 카운터 한 칸으로 뒀다면 여기서
 * 이중 청구가 났다. (연결 방식은 W5D3 미결, V12 주석 참고.)
 */
public enum BillingCycleStatus {
    PENDING, PAID, FAILED, ABANDONED
}
