package com.settleflow.reconciliation.pg;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * PG 정산 리포트 CSV 직렬화(W3D4).
 *
 * <p><b>왜 CSV 를 실제로 거치나.</b> 리포트를 객체로만 주고받아도 대사 로직은 돌아간다. 그런데 실제
 * PG 리포트는 파일로 오고, <b>파싱이 실패하거나 값이 비어 있는 상황이 대사의 현실</b>이다. 텍스트를
 * 한 번 통과시켜야 "빈 칸", "숫자가 아닌 값" 같은 게 테스트 가능해진다.
 *
 * <p>따옴표·이스케이프는 다루지 않는다 — 이 리포트의 모든 칸이 식별자·숫자·ISO 시각이라 쉼표가
 * 들어갈 자리가 없다. 진짜 PG 리포트를 붙이면 그때 파서를 바꾼다.
 */
public final class PgReportCsv {

    public static final String HEADER = "type,transaction_id,merchant_ref,occurred_at,gross_amount,pg_fee,net_amount";

    private PgReportCsv() {
    }

    public static String write(List<PgReportRow> rows) {
        StringBuilder sb = new StringBuilder(HEADER).append('\n');
        for (PgReportRow r : rows) {
            sb.append(r.type()).append(',')
                    .append(nullToEmpty(r.transactionId())).append(',')
                    .append(r.merchantRef()).append(',')
                    .append(r.occurredAt()).append(',')
                    .append(r.grossAmount()).append(',')
                    .append(r.pgFee()).append(',')
                    .append(r.netAmount()).append('\n');
        }
        return sb.toString();
    }

    /**
     * @throws IllegalArgumentException 헤더가 다르거나 칸 수가 안 맞을 때. <b>조용히 넘기지 않는다</b> —
     *                                 리포트를 잘못 읽으면 대사가 "불일치 없음"을 낼 수 있고, 그건
     *                                 침묵하는 실패다(W3D3 §8 ③ 과 같은 계열).
     */
    public static List<PgReportRow> read(String csv) {
        String[] lines = csv.strip().split("\\R");
        if (lines.length == 0 || !lines[0].strip().equals(HEADER)) {
            throw new IllegalArgumentException("PG 리포트 헤더가 다르다: " + (lines.length == 0 ? "(빈 파일)" : lines[0]));
        }

        List<PgReportRow> rows = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isBlank()) continue;
            String[] c = lines[i].split(",", -1);
            if (c.length != 7) {
                throw new IllegalArgumentException(
                        "PG 리포트 %d행의 칸이 7개가 아니다(%d개): %s".formatted(i + 1, c.length, lines[i]));
            }
            rows.add(new PgReportRow(
                    PgRowType.valueOf(c[0].strip()),
                    emptyToNull(c[1].strip()),
                    c[2].strip(),
                    OffsetDateTime.parse(c[3].strip()),
                    Long.parseLong(c[4].strip()),
                    Long.parseLong(c[5].strip()),
                    Long.parseLong(c[6].strip())));
        }
        return rows;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String emptyToNull(String s) {
        return s.isEmpty() ? null : s;
    }
}
