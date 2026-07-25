package com.settleflow.payment.event;

import com.settleflow.ledger.domain.Journal;
import com.settleflow.ledger.domain.LedgerAccount;
import com.settleflow.payment.domain.Payment;
import com.settleflow.payment.domain.Refund;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;

/**
 * refund.requested — 환불이 장부에 반영된 직후 발행(W3D1). fat 스냅샷: 셀러별로 얼마를 미지급금에서
 * 깎았는지(안분 결과)를 담는다.
 *
 * <p>내역은 방금 만든 REFUND journal 의 {@code SELLER_PAYABLE} 차변 줄에서 그대로 읽는다 —
 * W2D5 의 안분·뺄셈 결과가 그대로 이벤트가 된다. {@code refundStatus} 는 이 환불 반영 후의 파생 라벨.
 *
 * <p><b>"requested" 인데 이미 장부 반영이 끝났다?</b> 현재 환불은 동기라 요청=반영이다. Saga 로
 * 실제 PG 환불이 갈라지는 W3D2 부터 refund.requested(반영) 와 refund.completed(PG 확인)가 분리된다.
 */
public record RefundRequestedEvent(
        long paymentId,
        long refundId,
        long amount,
        List<SellerDebit> sellers,
        String refundStatus,
        OffsetDateTime occurredAt) {

    /** 셀러 한 명 몫: 이 환불로 줄어든 미지급금(안분·뺄셈 결과). */
    public record SellerDebit(long sellerId, long amount) {
    }

    public static RefundRequestedEvent from(Payment payment, Refund refund, Journal journal) {
        List<SellerDebit> sellers = journal.getEntries().stream()
                .filter(e -> e.getAccount() == LedgerAccount.SELLER_PAYABLE)   // CASH 대변 줄 제외
                .map(e -> new SellerDebit(e.getSellerId(), e.getAmount()))
                .sorted(Comparator.comparingLong(SellerDebit::sellerId))
                .toList();

        return new RefundRequestedEvent(
                payment.getId(), refund.getId(), refund.getAmount(),
                sellers, payment.refundStatus().name(), OffsetDateTime.now());
    }
}
