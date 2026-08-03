package com.settleflow.payment.event;

import com.settleflow.payment.domain.Refund;

import java.time.OffsetDateTime;

/**
 * refund.requested — 환불이 <b>요청</b>됐다. 아직 아무것도 확정되지 않았다(W3D2).
 *
 * <h3>W3D1 의 fat 스냅샷을 여기서 걷어냈다</h3>
 * W3D1 에선 이 이벤트가 셀러별 안분 내역까지 담았다. 그때는 환불이 동기라 요청 = 장부 반영이었고,
 * 방금 만든 역분개 journal 을 <b>거울처럼 읽어</b> 페이로드를 만들 수 있었기 때문이다.
 *
 * <p>W3D2 에서 역분개를 PG 확인 뒤로 미루자 <b>요청 시점엔 비출 거울이 없어졌다.</b> 그렇다고
 * 안분을 미리 계산해 담으면, 그 결과가 journal 과 별개로 존재하게 되어 "재계산이 아니라 거울처럼
 * 읽는다"는 W3D1 §4 의 방어가 무너진다 — 이벤트와 장부가 갈라질 수 있는 문이 다시 열린다.
 *
 * <p>그래서 <b>thin</b> 으로 간다. 담을 게 없는 게 아니라 <b>아직 사실이 아닌 것을 담지 않는</b> 것이고,
 * 셀러별 내역은 journal 이 실제로 쓰이는 {@link RefundCompletedEvent} 가 싣는다. 이름과 내용이
 * 비로소 일치한다 — W3D1 판 javadoc 이 "requested 인데 이미 장부 반영이 끝났다?"고 스스로 변명하던
 * 자리가 사라졌다.
 *
 * <p><b>소비자는 이걸 받아 PG 환불을 부른다.</b> 그래서 페이로드에 없는 정보(결제 금액, 셀러 구성)가
 * 필요하면 DB 를 재조회한다 — 아웃박스 소비자가 이벤트 순서에 의존하지 않는다는 W3D1 §4 의 전제와 같다.
 */
public record RefundRequestedEvent(
        long paymentId,
        long refundId,
        long amount,
        OffsetDateTime occurredAt) {

    public static RefundRequestedEvent from(Refund refund) {
        return new RefundRequestedEvent(
                refund.getPayment().getId(),
                refund.getId(),
                refund.getAmount(),
                OffsetDateTime.now());
    }
}
