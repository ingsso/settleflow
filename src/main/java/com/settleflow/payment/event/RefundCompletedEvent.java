package com.settleflow.payment.event;

import com.settleflow.ledger.domain.Journal;
import com.settleflow.ledger.domain.LedgerAccount;
import com.settleflow.payment.domain.Payment;
import com.settleflow.payment.domain.Refund;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;

/**
 * refund.completed — PG 가 환불을 확정했고 역분개가 장부에 적혔다(W3D2).
 *
 * <p>W3D1 에선 스키마만 있고 발행 지점이 없었다. 환불이 동기라 "완료"를 발행할 자리가 없었기
 * 때문이다. 이제 consumer 가 PG 확인을 받는 순간이 그 자리다.
 *
 * <h3>fat 스냅샷이 여기로 왔다</h3>
 * 셀러별 안분 내역은 {@code refund.requested} 가 아니라 이 이벤트가 싣는다. <b>역분개 journal 이
 * 실제로 만들어지는 게 여기</b>라서, W3D1 §4 의 규칙 — <i>재계산이 아니라 방금 만든 journal 의
 * 줄을 거울처럼 읽는다</i> — 을 지킬 수 있는 유일한 지점이기 때문이다. 같은 트랜잭션 안에서 장부를
 * 그대로 비추므로 이벤트가 장부와 갈라질 수 없다.
 *
 * <p>{@code pgRefundId} 는 PG 가 발급한 환불번호다. W3D4 대사가 외부 PG 리포트의 환불 행과
 * 우리 장부를 맞출 때 이게 열쇠가 된다 — {@code payment.completed} 에 없던 축이다.
 */
public record RefundCompletedEvent(
        long paymentId,
        long refundId,
        String pgRefundId,
        long amount,
        List<SellerDebit> sellers,
        String refundStatus,
        OffsetDateTime occurredAt) {

    /** 셀러 한 명 몫: 이 환불로 줄어든 미지급금(안분·뺄셈 결과, W2D5). */
    public record SellerDebit(long sellerId, long amount) {
    }

    public static RefundCompletedEvent from(Payment payment, Refund refund,
                                            Journal journal, String pgRefundId) {
        List<SellerDebit> sellers = journal.getEntries().stream()
                .filter(e -> e.getAccount() == LedgerAccount.SELLER_PAYABLE)   // CASH 대변 줄 제외
                .map(e -> new SellerDebit(e.getSellerId(), e.getAmount()))
                .sorted(Comparator.comparingLong(SellerDebit::sellerId))
                .toList();

        return new RefundCompletedEvent(
                payment.getId(), refund.getId(), pgRefundId, refund.getAmount(),
                sellers, payment.refundStatus().name(), OffsetDateTime.now());
    }
}
