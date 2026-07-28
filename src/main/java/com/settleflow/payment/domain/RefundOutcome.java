package com.settleflow.payment.domain;

/**
 * 환불 요청의 결과(W2 점검, A). {@link RefundService#requestRefund} 의 반환형.
 *
 * <p><b>왜 enum 하나가 아니라 sealed 인가.</b> 성공과 거부는 비대칭이다 — 성공은 <b>생성물</b>
 * (저장된 {@link Refund})을 딸고 오고, 거부는 <b>사유</b>만 있으면 된다. 이걸 {@code (RefundResult, Refund)}
 * 한 record 로 담으면 {@code refund} 가 거부 케이스에서 null 이 되고, "ACCEPTED 일 때만 refund 를 읽어라"가
 * <i>규율</i>로 남는다(NPE 함정). sealed 로 가르면 {@link Rejected} 에는 refund 필드가 <b>존재하지 않아</b>
 * 실수로 읽을 방법 자체가 없다 — 규율이 아니라 벽이다.
 *
 * <p>이 프로젝트가 반복해온 판단과 같은 선이다: W2D5 에서 {@link Refund} 의 journal 을 세터가 아니라
 * 생성자로 받아 "고칠 수 있는 문"을 아예 없앤 것 — <i>규율을 지키는 게 아니라 어길 수 없게 만든다</i>.
 *
 * <p>거부 사유 {@link RefundResult} 는 이제 <b>거부 4갈래만</b> 담는다. ACCEPTED 는 여기 {@link Accepted}
 * 타입이 대신하므로 enum 에서 빠졌다 — 그래서 {@code new Rejected(...)} 에 성공을 넣는 것 자체가 불가능하다.
 */
public sealed interface RefundOutcome permits RefundOutcome.Accepted, RefundOutcome.Rejected {

    /**
     * 환불 요청 <b>접수됨</b>. 저장된 {@link Refund}(id 채워짐) 와, 컨트롤러가 201 바디에 실을 두 값을 딸고 온다.
     *
     * <h3>왜 두 값을 서비스가 계산해서 넘기나(W3D4)</h3>
     * 컨트롤러가 직접 구하려면 리포지토리를 만져야 하고, 그러면 트랜잭션 밖에서 조회가 일어난다.
     * 서비스가 자기 tx 안에서 확정합을 세고 라벨까지 만들어 넘긴다.
     *
     * <h3>왜 두 값인가 — 하나로는 설명이 안 된다</h3>
     * 둘은 <b>다른 축</b>이다. 전액 환불을 요청만 해둔 순간을 보면 분명해진다:
     * <pre>
     *   refundStatus        = NOT_REFUNDED   (확정 기준 — PG 확인 전이라 아직 환불이 아니다)
     *   remainingRefundable = 0              (예약 기준 — 이미 다 잡혀서 더는 못 받는다)
     * </pre>
     * 모순처럼 보이지만 둘 다 참이고, <b>둘을 같이 줘야 다음 요청이 왜 409 인지가 설명된다.</b>
     * 라벨만 주면(W3D4 결정 3-가) 호출자는 "환불 없음"을 보고도 거부당한다.
     *
     * <p>이건 {@code payment.reserved_refund_amount} 한 컬럼이 <i>가드</i>와 <i>라벨</i>을 겸하다
     * 갈라선 결과가 응답 표면에 드러난 것이다({@link RefundStatus} 참고).
     *
     * @param refundStatus        <b>확정</b> 환불액 기준 라벨. 방금 접수한 이 건은 포함되지 않는다
     * @param remainingRefundable <b>예약</b> 기준 잔여 환불 가능액. 방금 접수한 이 건이 이미 빠져 있다
     */
    record Accepted(Refund refund, RefundStatus refundStatus, long remainingRefundable)
            implements RefundOutcome {}

    /** 환불 거부됨. 사유만 담는다 — {@link RefundResult} 는 거부 4갈래뿐이라 성공을 표현할 수 없다. */
    record Rejected(RefundResult reason) implements RefundOutcome {}
}
