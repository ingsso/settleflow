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

    /** 환불 반영됨. 저장된 {@link Refund}(id 채워짐) 를 딸고 온다 — 컨트롤러가 201 바디로 요약한다. */
    record Accepted(Refund refund) implements RefundOutcome {}

    /** 환불 거부됨. 사유만 담는다 — {@link RefundResult} 는 거부 4갈래뿐이라 성공을 표현할 수 없다. */
    record Rejected(RefundResult reason) implements RefundOutcome {}
}
