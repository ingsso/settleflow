package com.settleflow.reconciliation.pg;

import java.time.OffsetDateTime;

/**
 * PG 정산 리포트의 한 행(W3D4). 승인과 환불을 <b>한 파일</b>에 {@link PgRowType} 으로 섞는다.
 *
 * <h3>왜 상태 칸이 없나</h3>
 * <b>정산 리포트에는 실패한 거래가 없다.</b> 거절된 결제는 정산될 돈이 없어 애초에 파일에 안 나온다.
 * 상태 칸을 둬도 값이 항상 "승인"이라 정보가 0 이다.
 *
 * <p>대신 <b>존재 자체가 상태다</b>. 그래서 ⑤의 "상태 다름"이 "있음/없음"으로 흡수된다 —
 * {@code UNKNOWN} 결제가 리포트에 있으면 승인된 것이고, <b>없으면 거절이 아니라 여전히 미확정</b>이다
 * (아직 정산 안 됐을 수도 있다). W1D1 §3 의 <i>"없음은 실패가 아니라 정보의 부재"</i> 가 그대로 산다.
 *
 * <h3>조인 키가 {@code merchantRef} 인 이유</h3>
 * {@code transactionId}(PG 발급 번호)를 쓸 수 없다. 결제의 {@code pg_transaction_id} 는
 * <b>웹훅으로만</b> 채워지고 동기 승인 경로는 null 이다({@code PaymentTransitionService} javadoc).
 * {@code merchant_payment_id} 는 생성자에서 항상 채워지고({@code NOT NULL UNIQUE}) 애초에 우리가
 * PG 에 보내는 값이라({@code PgClient.approve}) 양쪽에 반드시 있다. 실무의 <b>가맹점 주문번호 대사</b>가
 * 이 형태다.
 *
 * @param type          {@code PAYMENT} / {@code REFUND}
 * @param transactionId PG 발급 번호. <b>참고용</b> — 결제는 비어 있을 수 있다(동기 경로)
 * @param merchantRef   <b>조인 키.</b> 결제는 {@code merchant_payment_id}, 환불은 {@code refund.id}
 * @param occurredAt    PG 기준 발생 시각
 * @param grossAmount   <b>대사가 비교하는 유일한 금액</b> — 승인금액 / 환불금액
 * @param pgFee         PG 가 뗀 수수료. <b>읽지만 안 쓴다</b>(④-나) — 우리 장부에 대응 계정이 없다
 *                      ({@code LedgerAccount} 는 {@code CASH}/{@code SELLER_PAYABLE}/{@code FEE_REVENUE} 뿐).
 *                      환불 행은 <b>0</b> 이다: PG 는 환불 시 수수료를 돌려주지 않는다 — 우리가 셀러에게
 *                      하는 것과 같은 정책(W2D1 <i>"환불분 수수료는 셀러 부담"</i>)을 PG 가 우리에게 한다
 * @param netAmount     실제 이동액({@code gross − pgFee}). 역시 안 쓴다
 */
public record PgReportRow(PgRowType type,
                          String transactionId,
                          String merchantRef,
                          OffsetDateTime occurredAt,
                          long grossAmount,
                          long pgFee,
                          long netAmount) {

    /** 대사 대조표의 키. 결제와 환불의 식별자가 우연히 같아도 섞이지 않는다. */
    public Key key() {
        return new Key(type, merchantRef);
    }

    public record Key(PgRowType type, String merchantRef) {
        @Override
        public String toString() {
            return type + ":" + merchantRef;
        }
    }
}
