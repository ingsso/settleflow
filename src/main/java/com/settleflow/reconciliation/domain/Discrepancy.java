package com.settleflow.reconciliation.domain;

import java.time.LocalDate;

/**
 * 대사 불일치 1건(W3D4). "이 셀러의 이 날짜에 두 기록이 다르다"는 사실.
 *
 * <p><b>이건 오류 보고가 아니라 조사 대상이다.</b> W3D3 §6 이 {@code settlement == SUM(ledger)} 에
 * 제약(트리거)을 안 건 이유가 여기 그대로 온다 — 이 관계는 <i>어긋날 수 있는</i> 관계라 막으면
 * 방어가 아니라 고장이다. 그래서 대사는 커밋을 막지 않고 <b>목록을 돌려준다</b>.
 *
 * <h3>부호 규약</h3>
 * {@code difference = settled − reconstructed}. <b>양수면 정산이 더 크다 = 과지급 방향</b>이다.
 * W3D3 §8 ③ 이 관찰한 것 — 과지급은 아무도 신고하지 않는다 — 이 이 부호로 나타난다.
 *
 * @param sellerId      셀러
 * @param date          정산 날짜(KST)
 * @param settled       {@code settlement.net_amount}. 배치가 <b>장부에서</b> 굳힌 값. 행이 없으면 null
 * @param reconstructed {@code payment}+{@code refund} 에서 <b>독립적으로</b> 다시 만든 값. 없으면 null
 * @param difference    위 규약. 한쪽이 null 이면 있는 쪽 값이 그대로(없는 쪽을 0 으로 본다)
 */
public record Discrepancy(long sellerId,
                          LocalDate date,
                          Long settled,
                          Long reconstructed,
                          long difference,
                          DiscrepancyType type) {

    public static Discrepancy amountMismatch(long sellerId, LocalDate date, long settled, long reconstructed) {
        return new Discrepancy(sellerId, date, settled, reconstructed,
                settled - reconstructed, DiscrepancyType.AMOUNT_MISMATCH);
    }

    public static Discrepancy missingInSettlement(long sellerId, LocalDate date, long reconstructed) {
        return new Discrepancy(sellerId, date, null, reconstructed,
                -reconstructed, DiscrepancyType.MISSING_IN_SETTLEMENT);
    }

    public static Discrepancy missingInReconstruction(long sellerId, LocalDate date, long settled) {
        return new Discrepancy(sellerId, date, settled, null,
                settled, DiscrepancyType.MISSING_IN_RECONSTRUCTION);
    }

    @Override
    public String toString() {
        return "%s[seller=%d %s 정산=%s 재구성=%s 차액=%+d]"
                .formatted(type, sellerId, date, settled, reconstructed, difference);
    }
}
