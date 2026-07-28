package com.settleflow.reconciliation.service;

import com.settleflow.order.domain.OrderItem;
import com.settleflow.payment.domain.Payment;
import com.settleflow.payment.domain.Refund;
import com.settleflow.payment.repository.PaymentRepository;
import com.settleflow.payment.repository.RefundRepository;
import com.settleflow.reconciliation.domain.Discrepancy;
import com.settleflow.reconciliation.domain.PgDiscrepancy;
import com.settleflow.reconciliation.domain.PgDiscrepancyType;
import com.settleflow.reconciliation.pg.PgReportRow;
import com.settleflow.reconciliation.pg.PgRowType;
import com.settleflow.seller.domain.Seller;
import com.settleflow.settlement.domain.Settlement;
import com.settleflow.settlement.repository.SettlementRepository;
import com.settleflow.settlement.service.SettlementService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * 대사(reconciliation) — 같은 사실이 두 곳에 따로 적혀 있을 때 둘을 맞춰보고 어긋난 걸 찾는다.
 *
 * <h3>왜 제약이 아니라 대사인가</h3>
 * W3D3 §6 이 {@code settlement == SUM(ledger)} 에 지연 트리거를 <b>쓸 수 있는데도 안 쓴</b> 이유가
 * 여기 그대로 온다. 이 관계는 <i>어긋날 수 있는</i> 관계다(W3D3 §4 의 커밋 가시성 구멍). 막으면
 * 방어가 아니라 고장이다 — 정산이 아예 안 써진다. <b>제약은 절대 어긋나면 안 되는 것에, 대사는
 * 어긋날 수 있는 것에.</b>
 *
 * <h3>오늘 보는 축 — 내부(장부 ↔ 정산)</h3>
 * 계획서의 축은 "내부 장부 ↔ 외부 PG 리포트"인데, W3D3 §1 이 정산 스냅샷을 저장하면서 내부 축이
 * 하나 생겼다. 외부 축은 아직 없다.
 *
 * <h3>핵심 설계 — 같은 SQL 을 재실행하지 않는다</h3>
 * 배치가 쓴 집계 SQL({@code SettlementRepository.upsertDailyNetBySeller})을 대사가 다시 돌리면
 * <b>동어반복</b>이다. 배치가 틀리게 계산했으면 대사도 똑같이 틀리게 계산해서 항상 일치한다 —
 * 통장을 안 보고 가계부를 두 번 읽는 것과 같다.
 *
 * <p>그래서 <b>원천을 바꿨다</b>. 장부({@code ledger_entry})가 아니라 {@code payment}+{@code refund}
 * 에서 셀러별 net 을 다시 만든다. W3D3 §2 가 <i>"payment+refund 를 읽을 수도 있었다"</i> 며 안 고른
 * 그 길이, 대사에선 <b>독립이라는 이유로 장점</b>이 된다.
 *
 * <h3>무엇이 독립이고 무엇이 아닌가 — 정직하게</h3>
 * <table border="1">
 *   <tr><th>항목</th><th>독립?</th><th>그래서</th></tr>
 *   <tr><td>부호(환불 차감)</td><td>✅</td>
 *       <td>배치는 SQL {@code CASE}, 대사는 Java 뺄셈. W3D3 §8 의 버그를 <b>잡는다</b></td></tr>
 *   <tr><td>수수료·셀러 배정</td><td>✅</td><td>대사가 order_item 을 다시 walk 한다</td></tr>
 *   <tr><td>증발한 줄·미실행 배치</td><td>✅</td><td>나중에 돌아서 보므로 {@code MISSING_IN_SETTLEMENT}</td></tr>
 *   <tr><td><b>날짜 경계</b></td><td>❌</td>
 *       <td>{@code payment} 에 승인 시각이 없어 journal 에서 <b>시각만 빌린다</b>
 *           ({@code PaymentRepository.findPaidInRange}). 경계 로직이 틀리면 같이 틀린다</td></tr>
 *   <tr><td><b>환불 안분 규칙</b></td><td>❌</td>
 *       <td>{@link #allocateRefund} 가 {@code LedgerService.recordRefund} 의 <b>의도적 복사</b>다.
 *           다르게 쓰면 반올림이 어긋나 정상 상태에서도 1 원씩 거짓 경보가 뜬다</td></tr>
 * </table>
 *
 * <p><b>그래서 W2D5 의 승격 트리거는 이 대사로 안 울린다.</b> {@code LedgerService.recordRefund}
 * javadoc 에 <i>"W3D4 대사에서 셀러별 불일치가 실제로 잡히면 품목 단위 환불로 올린다"</i> 고 적혀
 * 있는데, 장부와 재구성이 같은 안분식을 쓰므로 둘은 <b>구조적으로 항상 일치한다</b>. 그 트리거가
 * 겨냥한 건 <i>"A 물건만 반품됐는데 B 미지급금도 비례로 깎인다"</i> 이고, 그건 두 기록이 어긋나는
 * 문제가 아니라 <b>둘 다 똑같이 현실과 다른</b> 문제다 — 셀러 문의로만 발견된다.
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;
    private final SettlementRepository settlementRepository;

    public ReconciliationService(PaymentRepository paymentRepository,
                                 RefundRepository refundRepository,
                                 SettlementRepository settlementRepository) {
        this.paymentRepository = paymentRepository;
        this.refundRepository = refundRepository;
        this.settlementRepository = settlementRepository;
    }

    /**
     * 하루치를 대사한다.
     *
     * <p><b>고치지 않는다. 저장하지도 않는다.</b> 계획서 W3D5 가 "불일치 건 자동 플래그"라 저장은
     * 그쪽 일이고, 오늘은 비교까지다. 그리고 애초에 대사가 자동으로 고치면 안 된다 — 어긋남은
     * 조사 대상이지 정정 대상이 아니다(무엇이 맞는지는 대사가 알 수 없다).
     *
     * <p>{@code readOnly}: LAZY walk(order → items → product → seller)를 위해 트랜잭션이 필요하다.
     *
     * @return 어긋난 것만. <b>비어 있으면 그날은 맞았다는 뜻</b>이고, 그게 정상이다.
     */
    @Transactional(readOnly = true)
    public List<Discrepancy> reconcileSettlement(LocalDate date) {
        OffsetDateTime from = date.atStartOfDay(SettlementService.ZONE).toOffsetDateTime();
        OffsetDateTime to = date.plusDays(1).atStartOfDay(SettlementService.ZONE).toOffsetDateTime();

        Map<Long, Long> reconstructed = reconstructNetBySeller(from, to);
        Map<Long, Long> settled = new HashMap<>();
        for (Settlement s : settlementRepository.findBySettlementDateOrderBySellerId(date)) {
            settled.put(s.getSellerId(), s.getNetAmount());
        }

        // 한쪽에만 있는 셀러도 봐야 하므로 합집합. TreeSet 은 결과 순서를 결정적으로 만든다 —
        // 불일치 목록이 실행마다 순서가 바뀌면 테스트도 사람도 비교할 수 없다.
        List<Discrepancy> found = new ArrayList<>();
        for (Long sellerId : new TreeSet<>(union(settled.keySet(), reconstructed.keySet()))) {
            Long a = settled.get(sellerId);
            Long b = reconstructed.get(sellerId);

            if (a == null) {
                found.add(Discrepancy.missingInSettlement(sellerId, date, b));
            } else if (b == null) {
                found.add(Discrepancy.missingInReconstruction(sellerId, date, a));
            } else if (!a.equals(b)) {
                found.add(Discrepancy.amountMismatch(sellerId, date, a, b));
            }
        }

        log.info("대사 date={} [{} ~ {}) 정산={}셀러 재구성={}셀러 불일치={}건",
                date, from, to, settled.size(), reconstructed.size(), found.size());
        if (!found.isEmpty()) {
            found.forEach(d -> log.warn("[대사 불일치] {}", d));
        }
        return found;
    }

    /**
     * 외부 축 — 우리 기록 ↔ PG 정산 리포트를 <b>건별</b>로 맞춘다(W3D4 ①-A, ②).
     *
     * <h3>왜 건별인가</h3>
     * 내부 축은 셀러·날짜 합계인데 여기는 거래 단위다. 합계만 맞추면 <b>상쇄가 숨는다</b>
     * (한 건이 +5,000 크고 다른 건이 −5,000 작으면 총합은 맞다). 그리고 외부 축이 잡으려는 게
     * 애초에 금액이 아니라 <b>존재</b>라 건별이 아니면 의미가 없다 —
     * {@link PgDiscrepancyType#ONLY_IN_REPORT}(웹훅 유실)는 합계로는 보이지도 않는다.
     *
     * <h3>조인 키</h3>
     * {@code (type, merchantRef)}. PG 발급 번호를 안 쓰는 이유는 {@code PgReportRow} javadoc 에 —
     * 결제의 {@code pg_transaction_id} 는 웹훅으로만 채워져 동기 승인 경로는 비어 있다.
     *
     * <h3>비교하는 금액은 gross 하나</h3>
     * 리포트가 실어오는 {@code pgFee}·{@code netAmount} 는 <b>읽지만 안 쓴다</b>(④-나).
     * 우리 장부에 PG 수수료 계정이 없어서({@code LedgerAccount} 셋 중 없다) 비교하면 매 건 차이가
     * 나는데, 그건 버그가 아니라 모델에 계정이 없어서 생기는 구조적 차이다. 쓰려면 계정 추가가
     * 먼저다(V10 + W2D1 분개 정책 변경 — 오늘 스코프 밖).
     *
     * @param report 그날의 PG 리포트 행들. 운영에선 PG 가 주고, 테스트에선 {@code PgReportGenerator}
     *               가 만든다 — <b>후자는 우리 DB 에서 나오므로 정상 케이스가 0 건인 건 당연하다</b>.
     *               값은 심은 불일치를 잡느냐에 있다(그 javadoc 참고)
     */
    @Transactional(readOnly = true)
    public List<PgDiscrepancy> reconcileWithPgReport(LocalDate date, List<PgReportRow> report) {
        OffsetDateTime from = date.atStartOfDay(SettlementService.ZONE).toOffsetDateTime();
        OffsetDateTime to = date.plusDays(1).atStartOfDay(SettlementService.ZONE).toOffsetDateTime();

        Map<PgReportRow.Key, Long> internal = new HashMap<>();
        for (Payment p : paymentRepository.findPaidInRange(from, to)) {
            internal.put(new PgReportRow.Key(PgRowType.PAYMENT, p.getMerchantPaymentId().toString()),
                    p.getAmount());
        }
        for (Refund r : refundRepository.findCompletedInRange(from, to)) {
            internal.put(new PgReportRow.Key(PgRowType.REFUND, String.valueOf(r.getId())),
                    r.getAmount());
        }

        Map<PgReportRow.Key, Long> external = new HashMap<>();
        for (PgReportRow row : report) {
            external.put(row.key(), row.grossAmount());
        }

        // 키를 문자열로 정렬해 결과 순서를 결정적으로 만든다(내부 축의 TreeSet 과 같은 이유).
        List<PgDiscrepancy> found = new ArrayList<>();
        List<PgReportRow.Key> keys = new ArrayList<>(union(internal.keySet(), external.keySet()));
        keys.sort(Comparator.comparing(PgReportRow.Key::toString));

        for (PgReportRow.Key key : keys) {
            Long mine = internal.get(key);
            Long theirs = external.get(key);

            if (mine == null) {
                found.add(PgDiscrepancy.onlyInReport(key.type(), key.merchantRef(), date, theirs));
            } else if (theirs == null) {
                found.add(PgDiscrepancy.onlyInInternal(key.type(), key.merchantRef(), date, mine));
            } else if (!mine.equals(theirs)) {
                found.add(PgDiscrepancy.amountMismatch(key.type(), key.merchantRef(), date, mine, theirs));
            }
        }

        log.info("PG 대사 date={} 내부={}건 리포트={}건 불일치={}건",
                date, internal.size(), external.size(), found.size());
        if (!found.isEmpty()) {
            found.forEach(d -> log.warn("[PG 대사 불일치] {}", d));
        }
        return found;
    }

    /**
     * {@code payment}+{@code refund} 에서 셀러별 net 을 다시 만든다 — <b>장부를 보지 않는다</b>.
     *
     * <p>net 의 정의는 배치와 같아야 한다: 셀러에게 줄 돈 = 결제분 미지급금 − 환불분 차감.
     * 다만 <b>구하는 방법이 완전히 다르다</b>. 배치는 이미 쪼개져 저장된 {@code ledger_entry} 를
     * {@code CASE WHEN CREDIT THEN +amount ELSE -amount END} 로 합치고, 여기서는 원본 주문을
     * 다시 쪼개 더하고 뺀다. 그 차이가 이 대사의 존재 이유다.
     */
    private Map<Long, Long> reconstructNetBySeller(OffsetDateTime from, OffsetDateTime to) {
        Map<Long, Long> net = new HashMap<>();

        // --- 결제분: subtotal → 수수료 → 미지급금 ---
        for (Payment payment : paymentRepository.findPaidInRange(from, to)) {
            for (SellerShare share : sharesOf(payment)) {
                // LedgerService.recordPayment 와 같은 식. 정수 나눗셈이 버림이라 절반을 더해 반올림하고,
                // 미지급금은 빼서 구한다.
                long fee = (share.subtotal() * share.feeRate() + 5000) / 10000;
                net.merge(share.sellerId(), share.subtotal() - fee, Long::sum);
            }
        }

        // --- 환불분: 확정된 것만, 셀러별로 안분해 뺀다 ---
        for (Refund refund : refundRepository.findCompletedInRange(from, to)) {
            allocateRefund(refund).forEach((sellerId, portion) -> net.merge(sellerId, -portion, Long::sum));
        }
        return net;
    }

    /**
     * 환불 1건을 셀러별로 안분한다. <b>{@code LedgerService.recordRefund} 의 의도적 복사다.</b>
     *
     * <p>여기만 독립이 아니다. 다르게 쓰면 반올림 결과가 달라져 <b>정상 상태에서도 매일 1 원씩
     * 어긋난다</b> — 허용 오차를 두면 진짜 1 원 오류가 같이 묻힌다. 그래서 복사하고, 대신
     * <b>안분 규칙 자체는 이 대사의 범위 밖</b>이라고 못박는다.
     *
     * <p>복사가 성립하려면 규칙이 결정적이어야 한다. 두 가지가 계약이다 —
     * <b>{@code sellerId} 오름차순</b>과 <b>마지막 몫은 계산이 아니라 뺄셈</b>(W2D5). 둘 중 하나라도
     * 어긋나면 1 원이 다른 셀러에게 가고, 그게 곧 거짓 불일치가 된다.
     */
    private Map<Long, Long> allocateRefund(Refund refund) {
        List<SellerShare> shares = sharesOf(refund.getPayment());
        long total = shares.stream().mapToLong(SellerShare::subtotal).sum();
        long amount = refund.getAmount();

        Map<Long, Long> portions = new HashMap<>();
        long allocated = 0;
        for (int i = 0; i < shares.size(); i++) {
            SellerShare share = shares.get(i);
            boolean last = (i == shares.size() - 1);

            long portion;
            if (last) {
                portion = amount - allocated;                                 // 계산이 아니라 뺄셈
            } else {
                portion = (amount * share.subtotal() + total / 2) / total;    // 비례 + 반올림
                allocated += portion;
            }

            // 0 인 셀러는 장부에도 줄이 없다(chk_ledger_amount 가 amount > 0 을 요구). 합은 그대로다.
            if (portion != 0) {
                portions.merge(share.sellerId(), portion, Long::sum);
            }
        }
        return portions;
    }

    /**
     * 셀러별 결제 subtotal + 수수료율 — <b>{@code sellerId} 오름차순</b>.
     *
     * <p>{@code LedgerService.sharesOf} 와 같은 walk 지만 {@link Seller} 엔티티가 아니라 값만 꺼낸다.
     * 정렬이 계약의 일부인 이유는 {@link #allocateRefund} 의 "마지막"이 이 순서로 정의되기 때문이다.
     */
    private List<SellerShare> sharesOf(Payment payment) {
        Map<Long, Long> subtotalBySeller = new HashMap<>();
        Map<Long, Integer> feeRateBySeller = new HashMap<>();
        for (OrderItem item : payment.getOrder().getItems()) {
            Seller seller = item.getProduct().getSeller();
            subtotalBySeller.merge(seller.getId(), item.subtotal(), Long::sum);
            feeRateBySeller.putIfAbsent(seller.getId(), seller.getFeeRate());
        }
        return subtotalBySeller.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> new SellerShare(e.getKey(), e.getValue(), feeRateBySeller.get(e.getKey())))
                .toList();
    }

    private static <T> java.util.Set<T> union(java.util.Set<T> a, java.util.Set<T> b) {
        java.util.Set<T> all = new java.util.HashSet<>(a);
        all.addAll(b);
        return all;
    }

    private record SellerShare(long sellerId, long subtotal, int feeRate) {
    }
}
