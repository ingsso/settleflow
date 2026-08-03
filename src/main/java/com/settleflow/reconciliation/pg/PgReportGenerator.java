package com.settleflow.reconciliation.pg;

import com.settleflow.payment.domain.Payment;
import com.settleflow.payment.domain.Refund;
import com.settleflow.payment.repository.PaymentRepository;
import com.settleflow.payment.repository.RefundRepository;
import com.settleflow.settlement.service.SettlementService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Mock PG 정산 리포트 생성기(W3D4). 진짜 PG 가 매일 보내줄 파일을 흉내낸다.
 *
 * <h3>★ 이건 검증 대상이 아니라 픽스처 공장이다 — 정직하게 적어둔다</h3>
 * 이 생성기는 <b>우리 DB 에서</b> 리포트를 만든다. 그러니 정상 경로에서 대사 불일치가 0 건인 건
 * <b>당연하다</b> — 같은 데이터에서 나왔으니까. {@code ReconciliationService} 가 내부 축에서 애써
 * 피한 <b>동어반복이 여기선 피할 수 없다.</b> 진짜 PG 가 없기 때문이다.
 *
 * <p>그래서 <b>외부 축 테스트의 값은 "정상 0건"이 아니라 심은 불일치를 잡느냐에 있다.</b> 계획서의
 * 주말 체크포인트가 <i>"의도적으로 불일치 3건 심고 정확히 3건을 잡아내는지"</i> 인 게 이 뜻이다.
 *
 * <h3>PG 수수료는 지어낸 값이다</h3>
 * 우리는 PG 요율을 모른다 — 장부에 계정조차 없다({@code LedgerAccount} 셋 중 PG 수수료는 없다).
 * {@link #PG_FEE_BPS} 는 리포트를 현실처럼 보이게 하는 상수일 뿐이고, <b>대사는 이 값을 쓰지 않는다</b>
 * (④-나: 읽지만 안 쓴다). 언젠가 이 값을 대사에 쓰려면 그 전에 장부에 계정이 생겨야 한다.
 */
@Component
public class PgReportGenerator {

    /** PG 수수료율(bp). 3% — 리포트를 그럴듯하게 만드는 값이고 대사는 참조하지 않는다. */
    public static final int PG_FEE_BPS = 300;

    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;

    public PgReportGenerator(PaymentRepository paymentRepository, RefundRepository refundRepository) {
        this.paymentRepository = paymentRepository;
        this.refundRepository = refundRepository;
    }

    /**
     * 하루치 리포트를 만든다.
     *
     * <p>시간 기준은 대사와 같아야 한다 — 결제는 승인 분개 시각, 환불은 {@code confirmed_at}.
     * 다르면 리포트와 대사가 서로 다른 날을 보게 되어 <b>모든 건이 불일치로 뜬다</b>.
     */
    @Transactional(readOnly = true)
    public List<PgReportRow> generate(LocalDate date) {
        OffsetDateTime from = date.atStartOfDay(SettlementService.ZONE).toOffsetDateTime();
        OffsetDateTime to = date.plusDays(1).atStartOfDay(SettlementService.ZONE).toOffsetDateTime();

        List<PgReportRow> rows = new ArrayList<>();

        for (Payment p : paymentRepository.findPaidInRange(from, to)) {
            long gross = p.getAmount();
            long fee = (gross * PG_FEE_BPS + 5000) / 10000;
            rows.add(new PgReportRow(
                    PgRowType.PAYMENT,
                    p.getPgTransactionId(),                    // 동기 승인 경로면 null — 그래서 조인 키가 아니다
                    p.getMerchantPaymentId().toString(),       // 조인 키
                    p.getJournal().getCreatedAt(),
                    gross, fee, gross - fee));
        }

        for (Refund r : refundRepository.findCompletedInRange(from, to)) {
            long gross = r.getAmount();
            rows.add(new PgReportRow(
                    PgRowType.REFUND,
                    r.getPgRefundId(),                         // COMPLETED 라 반드시 있다(V7 CHECK)
                    String.valueOf(r.getId()),                 // 조인 키
                    r.getConfirmedAt(),
                    gross, 0L, gross));                        // 환불엔 PG 수수료 환급이 없다
        }
        return rows;
    }

    /** 파일로 받는 상황까지 흉내낸다 — 파싱 실패도 대사의 현실이다({@link PgReportCsv}). */
    @Transactional(readOnly = true)
    public String generateCsv(LocalDate date) {
        return PgReportCsv.write(generate(date));
    }
}
