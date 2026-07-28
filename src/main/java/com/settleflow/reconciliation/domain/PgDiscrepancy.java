package com.settleflow.reconciliation.domain;

import com.settleflow.reconciliation.pg.PgRowType;

import java.time.LocalDate;

/**
 * 외부 축(내부 기록 ↔ PG 리포트) 불일치 1건(W3D4).
 *
 * <h3>왜 {@link Discrepancy} 와 합치지 않았나</h3>
 * 내부 축은 <b>셀러·날짜</b> 단위 합계이고, 외부 축은 <b>거래</b> 단위다. 셀러 개념이 아예 없다 —
 * PG 는 우리가 셀러에게 얼마를 주는지 모른다. 억지로 한 타입에 담으면 절반이 늘 null 인 필드가
 * 생기고, "언제 어느 필드를 읽어야 하나"가 규율로 남는다({@code RefundOutcome} 을 sealed 로 가른
 * 것과 같은 판단 — 규율이 아니라 벽).
 *
 * <p>부호 규약은 내부 축과 같다: {@code difference = internal − report}.
 *
 * @param type        결제/환불
 * @param merchantRef 조인 키. 결제는 {@code merchant_payment_id}, 환불은 {@code refund.id}
 * @param internal    우리 기록의 금액. 없으면 null
 * @param report      PG 리포트의 금액. 없으면 null
 */
public record PgDiscrepancy(PgRowType type,
                            String merchantRef,
                            LocalDate date,
                            Long internal,
                            Long report,
                            long difference,
                            PgDiscrepancyType kind) {

    public static PgDiscrepancy amountMismatch(PgRowType type, String ref, LocalDate date,
                                               long internal, long report) {
        return new PgDiscrepancy(type, ref, date, internal, report,
                internal - report, PgDiscrepancyType.AMOUNT_MISMATCH);
    }

    /** PG 는 돈을 움직였는데 우리에겐 기록이 없다 — <b>웹훅 유실</b>. 외부 축의 상금. */
    public static PgDiscrepancy onlyInReport(PgRowType type, String ref, LocalDate date, long report) {
        return new PgDiscrepancy(type, ref, date, null, report,
                -report, PgDiscrepancyType.ONLY_IN_REPORT);
    }

    /** 우리는 처리했다는데 PG 리포트에 없다. */
    public static PgDiscrepancy onlyInInternal(PgRowType type, String ref, LocalDate date, long internal) {
        return new PgDiscrepancy(type, ref, date, internal, null,
                internal, PgDiscrepancyType.ONLY_IN_INTERNAL);
    }

    @Override
    public String toString() {
        return "%s[%s ref=%s 내부=%s 리포트=%s 차액=%+d]"
                .formatted(kind, type, merchantRef, internal, report, difference);
    }
}
