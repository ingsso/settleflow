package com.settleflow.payment.event;

import com.settleflow.ledger.domain.Journal;
import com.settleflow.ledger.domain.LedgerAccount;
import com.settleflow.ledger.domain.LedgerEntry;
import com.settleflow.payment.domain.Payment;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.TreeMap;

/**
 * payment.completed — 결제 확정 시 발행(W3D1). <b>fat 스냅샷</b>: 셀러별 미지급금·수수료 내역까지
 * 담아 W3D4 대사가 외부 PG 리포트와 바로 대조할 수 있게 한다.
 *
 * <p>내역은 <b>방금 만든 PAYMENT journal 의 줄에서 그대로 읽는다</b>(재계산 아님) — 그래서 이벤트가
 * 장부와 갈라질 수 없다. "발행 시점 스냅샷이 진실과 갈라진다"는 fat 이벤트의 통상적 약점을,
 * 같은 tx 안에서 장부를 거울처럼 비추는 것으로 없앤다.
 */
public record PaymentCompletedEvent(
        long paymentId,
        long orderId,
        long amount,
        List<SellerPayable> sellers,
        OffsetDateTime occurredAt) {

    /** 셀러 한 명 몫: 미지급금(payable)과 우리가 뗀 수수료(fee). */
    public record SellerPayable(long sellerId, long payable, long fee) {
    }

    public static PaymentCompletedEvent from(Payment payment, Journal journal) {
        // sellerId → [payable, fee]. TreeMap 으로 sellerId 오름차순 정렬을 계약으로 유지(장부 집계와 동일).
        TreeMap<Long, long[]> bySeller = new TreeMap<>();
        for (LedgerEntry e : journal.getEntries()) {
            if (e.getSellerId() == null) continue;   // CASH 줄
            long[] pf = bySeller.computeIfAbsent(e.getSellerId(), k -> new long[2]);
            if (e.getAccount() == LedgerAccount.SELLER_PAYABLE) pf[0] += e.getAmount();
            else if (e.getAccount() == LedgerAccount.FEE_REVENUE) pf[1] += e.getAmount();
        }

        List<SellerPayable> sellers = bySeller.entrySet().stream()
                .map(en -> new SellerPayable(en.getKey(), en.getValue()[0], en.getValue()[1]))
                .toList();

        return new PaymentCompletedEvent(
                payment.getId(), payment.getOrder().getId(), payment.getAmount(),
                sellers, OffsetDateTime.now());
    }
}
