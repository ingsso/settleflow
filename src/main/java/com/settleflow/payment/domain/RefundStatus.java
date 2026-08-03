package com.settleflow.payment.domain;

/**
 * 환불 진행 라벨(W2D4, 원천 교체 W3D4). <b>저장하지 않는다</b> — 매번 파생한다.
 *
 * <p><b>이건 상태머신의 노드가 아니다.</b> 상태머신은 <i>(현재 상태, 사건) → 다음 상태 또는 거부</i>이고,
 * 그 값어치는 바꿔주는 게 아니라 <b>거부하는</b> 데 있다({@link Payment#onApproved()} 의 CONFLICT 가지).
 * 이 enum 은 사건을 받지도 않고 자기가 어디서 왔는지도 모른다 — 금액 한 쌍에 이름을 붙일 뿐이라
 * 아무것도 못 막는다. 막는 일은 {@code RefundService} 의 가드와
 * {@code PaymentRepository.reserveRefund} 의 조건부 UPDATE 가 나눠 맡는다.
 *
 * <h3>W3D4 — 원천이 바뀌었다</h3>
 * W2D4 에선 {@code payment.refunded_amount} 에서 파생했다. 그때는 그게 곧 환불액이었다.
 * W3D2 에서 환불이 Saga 로 갈라지면서 그 컬럼이 <b>예약</b>이 됐고(COMPLETED + REQUESTED + UNKNOWN),
 * 그 위에서 계산된 라벨은 <b>진행중인 환불을 환불된 것으로</b> 셌다. {@code UNKNOWN} 은 쓸이 배치가
 * 없어 영영 안 풀리므로(W3D2 이월) 그 오차는 일시적이지도 않았다.
 *
 * <p>그래서 원천을 옮겼다 — 이제 <b>확정된 환불액</b>({@code RefundRepository.sumCompletedAmount},
 * 즉 {@code pg_status = 'COMPLETED'} 인 {@code refund} 행의 합)에서 파생한다. 컬럼은 개명돼
 * {@code reserved_refund_amount} 가 됐고(V9), 그건 가드에게 남았다.
 *
 * <p><b>왜 컬럼을 라벨에 맞추지 않고 라벨을 내보냈나.</b> 가드({@code reserveRefund} 의
 * {@code + :amount <= amount})는 예약을 <b>포함해야</b> 정확하다 — 빼면 동시 환불이 같은 잔액을 읽어
 * 둘 다 통과한다(W1D2 실측: 8,000 이어야 할 값이 11,000). 라벨은 예약이 <b>빠져야</b> 정확하다.
 * 한 컬럼이 서로 다른 값을 원하는 둘을 겸할 수 없고, 둘 중 <b>양보할 수 있는 쪽이 라벨</b>이었다.
 *
 * <h3>왜 여전히 저장하지 않나</h3>
 * {@code payment} 에 확정분 컬럼을 하나 더 두는 길이 있었다. W3D3 §1 이 정산 스냅샷을 저장하며 세운
 * 논리(<i>복제가 아니라 별개의 사실이면 저장</i>)를 쓰면 정당화도 된다. 파생을 골랐다 —
 * 확정 환불액은 {@code refund} 행에서 <b>언제 다시 세도 같은 값</b>이라 W3D3 이 말한 "별개의 사실"이
 * 아니라 그냥 파생이다(정산액이 저장돼야 했던 건 <i>어긋날 수 있어서</i>였다). 파생이면 진실은
 * 여전히 하나({@code refund} 행)고, 그 행은 append-only + 조건부 UPDATE 로만 바뀌어 드리프트할 경로가 없다.
 *
 * <p><b>덤:</b> "REFUNDED 에서 추가 환불 금지"를 위한 별도 가드는 여전히 필요 없다. 다만 그건 이 라벨의
 * 성질이 아니라 <b>가드 컬럼의 성질</b>이다 — {@code reserved_refund_amount == amount} 면
 * {@code + x <= amount} 가 어떤 양수 x 에도 거짓이라 자동으로 막힌다. 라벨이 원천을 옮긴 뒤에도
 * 그 보호가 그대로인 건, 애초에 막는 일을 라벨이 한 적이 없기 때문이다.
 *
 * <p><b>이월 해소:</b> W2D4 는 <i>"지금은 앱에서만 계산되므로 SQL 로 못 묻는다"</i> 를 이월로 적고
 * {@code GENERATED ALWAYS AS ... STORED} 승격을 대비책으로 뒀다. 그 길은 이제 <b>불가능하다</b> —
 * 생성 컬럼은 같은 행만 볼 수 있는데 원천이 다른 테이블({@code refund})로 갔다. 대신 이월 자체가
 * 사라졌다: 원천이 테이블이라 {@code GROUP BY payment_id} 로 SQL 에서 바로 물을 수 있다.
 */
public enum RefundStatus {

    /** 환불 이력 없음. 확정 환불액 == 0. <b>요청만 뜬 건은 여기다</b> — 아직 환불이 아니다. */
    NOT_REFUNDED,

    /** 일부 환불됨. {@code 0 < 확정 환불액 < amount} */
    PARTIAL_REFUNDED,

    /** 전액 환불됨. 확정 환불액 == {@code amount} */
    REFUNDED;

    /**
     * 금액 한 쌍에 이름을 붙인다. <b>순수 함수</b> — 결제의 자격({@code PAID} 인지)은 보지 않는다.
     * 그 정의역 판정은 {@link Payment#refundStatus(long)} 이 갖는다(거기 두는 이유도 그 javadoc 에).
     *
     * @param amount          결제 금액
     * @param completedAmount <b>확정된</b> 환불액 합(예약분 제외)
     */
    public static RefundStatus of(long amount, long completedAmount) {
        if (completedAmount == 0L) return NOT_REFUNDED;
        if (completedAmount == amount) return REFUNDED;
        return PARTIAL_REFUNDED;
    }
}
