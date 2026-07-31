package com.settleflow.ledger.service;

import com.settleflow.ledger.domain.EntrySide;
import com.settleflow.ledger.domain.EntrySpec;
import com.settleflow.ledger.domain.Journal;
import com.settleflow.ledger.domain.LedgerAccount;
import com.settleflow.ledger.repository.JournalRepository;
import com.settleflow.order.domain.OrderItem;
import com.settleflow.payment.domain.Payment;
import com.settleflow.seller.domain.Seller;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 자동 분개. 결제 확정 시 셀러별 이중장부 분개를 만들고(W2D2), 환불 시 역분개를 만든다(W2D5).
 *
 * <p>돈 쪼개기(요율·안분·반올림)는 여기서 끝내고, 조립·균형검증은 {@link Journal} 에 맡긴다.
 *
 * <p><b>호출 자리가 결제와 환불이 다르다.</b> 결제 분개는 {@code PaymentTransitionService} 의
 * TRANSITIONED 부수효과(tx2 안)에 얹힌다 — 승인 전이가 실제로 일어난 1회에만 불리므로 중복 웹훅이
 * 와도 journal 은 하나다(W2D1 §4 — W1D4 멱등 backbone 재사용). 환불엔 그 대칭이 없다: 같은 금액
 * 환불 요청 두 번은 중복이 아니라 <b>진짜 환불 2건</b>이라 journal 도 2개가 정답이다(W2D4 §1).
 * 그래서 역분개는 멱등 지점이 아니라 "이 환불이 실제로 일어났다"를 판정하는 자리 — 조건부 UPDATE 의
 * affected rows 직후 — 에 붙는다. LAZY walk(order→items→product→seller)는 양쪽 다 tx 안이라 안전.
 */
@Service
public class LedgerService {

    private final JournalRepository journalRepository;

    public LedgerService(JournalRepository journalRepository) {
        this.journalRepository = journalRepository;
    }

    /**
     * 결제 분개(B2). 한 주문에 셀러가 여럿이면 2N+1 줄이 된다:
     * 현금 1줄 + 셀러별 (미지급금·수수료) 2줄.
     */
    public Journal recordPayment(Payment payment) {
        List<SellerShare> shares = sharesOf(payment);

        List<EntrySpec> specs = new ArrayList<>();
        // 현금 차변 = 받은 금액 전체(= Σsubtotal). 셀러 없음.
        specs.add(new EntrySpec(LedgerAccount.CASH, EntrySide.DEBIT, payment.getAmount(), null));

        for (SellerShare share : shares) {
            long subtotal = share.subtotal();
            int bp = share.seller().getFeeRate();

            // 수수료만 반올림한다(정수 나눗셈은 버림이라 절반을 더해 반올림). 미지급금은 빼서 구한다
            // → 두 줄의 합이 정의상 subtotal 이라 균형이 구조적으로 성립한다.
            long fee = (subtotal * bp + 5000) / 10000;
            long payable = subtotal - fee;

            Long sellerId = share.seller().getId();
            specs.add(new EntrySpec(LedgerAccount.SELLER_PAYABLE, EntrySide.CREDIT, payable, sellerId));
            specs.add(new EntrySpec(LedgerAccount.FEE_REVENUE, EntrySide.CREDIT, fee, sellerId));
        }

        Journal journal = Journal.forPayment(specs);   // 조립 + Σ차변==Σ대변 자체검증
        return journalRepository.save(journal);
    }

    /**
     * 환불 역분개(W2D5). 고객에게 나간 {@code amount} 를 <b>셀러별 미지급금에서</b> 깎는다.
     *
     * <pre>
     *   SELLER_PAYABLE 차변 (셀러별 안분)   ← 갚을 빚이 줄어든다
     *   CASH           대변 amount        ← 실제로 나간 돈 1줄
     * </pre>
     *
     * <p><b>돈은 고객 한 명에게 한 번 나간다.</b> 여러 줄로 갈리는 건 송금이 아니라 "누구 몫에서
     * 깎느냐"이고, {@code SELLER_PAYABLE} 줄엔 {@code seller_id} 가 반드시 있어야 하므로
     * (V4 {@code chk_ledger_seller_presence}) 안분을 피할 길이 스키마 차원에서 없다.
     *
     * <p><b>수수료는 환급하지 않는다</b>(W2D1 정책 — 환불분의 수수료는 셀러 부담). 그래서 전액환불
     * 시 미지급금 순액이 <b>음수가 된다</b>(20,000 결제 → 미지급금 19,000 인데 차변 20,000).
     * 버그가 아니라 정책의 귀결이고, 잔액은 컬럼이 아니라 {@code ledger_entry} 합계에서 나오는
     * 파생값이라 막을 자리도 없다. W3 정산에서 다음 회차와 상계된다.
     *
     * <h3>안분과 잔여</h3>
     * 배분은 결제 시점 subtotal 비례다. 다만 이건 <b>추정</b>이다 — 요청이 금액 단위라
     * ({@code paymentId}, {@code amount}) 어느 셀러 물건이 반품됐는지가 요청에 없다. 셀러 A 물건만
     * 반품돼도 B 미지급금이 비례만큼 깎인다.
     *
     * <p><b>승격 트리거는 대사가 아니다(W3D4 §4-3 에서 정정).</b> 원래 여기 <i>"W3D4 대사에서 셀러별
     * 불일치가 잡히면 품목 단위 환불로 올린다"</i> 고 적혀 있었는데 그 조건은 <b>영원히 성립하지
     * 않는다</b> — {@code ReconciliationService.allocateRefund} 가 이 안분식을 <b>의도적으로 복사</b>했기
     * 때문이다(다르게 쓰면 반올림이 어긋나 정상 상태에서도 매일 1 원씩 거짓 경보가 뜬다).
     * 장부와 재구성이 같은 식을 쓰므로 둘은 <b>구조적으로 항상 일치한다</b>.
     *
     * <p>이 추정이 겨냥하는 건 <i>두 기록이 어긋나는</i> 문제가 아니라 <b>둘 다 똑같이 현실과 다른</b>
     * 문제다. 그래서 대사로는 못 잡고 <b>셀러 문의로만 발견된다</b> — 승격 트리거는 그쪽이다.
     *
     * <p>마지막 셀러 몫은 <b>계산하지 않고 뺄셈으로</b> 구한다 — Σ배분액이 정의상 {@code amount} 라
     * 반올림을 어떻게 하든 균형이 깨질 수 없다(W2D2 의 "미지급금은 빼서 구한다"와 같은 트릭).
     * 균형이 검사의 결과가 아니라 <b>구조</b>가 된다.
     *
     * <p>그 대가로 <b>마지막 자리가 반올림 오차를 흡수한다</b>. 앞 셀러들이 올림된 만큼 마지막이 덜
     * 받으므로, 같은 결제에 부분환불이 반복되면 편향이 한 셀러에 쌓인다(환불 N 건 → 최대 N 원 미만).
     * 감수하되 규칙을 결정적으로 못박는다 — <b>{@code sellerId} 오름차순의 마지막</b>. 정렬이 없으면
     * 같은 입력에도 1 원이 다른 셀러에게 갈 수 있고, 그러면 "왜 B 가 1 원 덜 받았나"에 답할 문장이
     * 없어진다(장부는 W3 대사의 진실원본이다).
     */
    public Journal recordRefund(Payment payment, long amount) {
        List<SellerShare> shares = sharesOf(payment);
        long total = shares.stream().mapToLong(SellerShare::subtotal).sum();

        List<EntrySpec> specs = new ArrayList<>();
        specs.add(new EntrySpec(LedgerAccount.CASH, EntrySide.CREDIT, amount, null));

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

            // 안분 결과가 0 인 셀러는 줄을 만들지 않는다 — chk_ledger_amount 가 amount > 0 을 요구하고,
            // 그 셀러 몫에선 실제로 아무 일도 안 일어났으므로 빈 줄이 맞다. Σ는 그대로라 균형 무관.
            if (portion == 0) continue;
            if (portion < 0) {
                // 앞 셀러들의 올림 합이 마지막 몫을 넘어선 경우. 뺄셈이 균형은 지켜주지만 부호까지
                // 지켜주진 않는다 — 여기 걸리면 안분식을 고쳐야 한다(프로그래밍 오류).
                throw new IllegalStateException(
                        "안분 오류: 마지막 셀러 몫이 음수다. amount=%d allocated=%d"
                                .formatted(amount, allocated));
            }

            specs.add(new EntrySpec(
                    LedgerAccount.SELLER_PAYABLE, EntrySide.DEBIT, portion, share.seller().getId()));
        }

        Journal journal = Journal.forRefund(specs);
        return journalRepository.save(journal);
    }

    /**
     * 셀러별 결제 subtotal — <b>{@code sellerId} 오름차순</b>. 결제(수수료율)와 환불(안분 비율)이
     * 같은 집계를 필요로 해서 한 자리에 둔다. 맵 키는 {@link Seller} 인스턴스가 아니라 id — 안전하게.
     *
     * <p>정렬이 계약의 일부다: {@link #recordRefund} 의 잔여 흡수 셀러가 "마지막"으로 정의되므로
     * 순회 순서가 바뀌면 1 원이 다른 셀러에게 간다. {@code HashMap} 순회 순서는 명세되지 않는다.
     */
    private List<SellerShare> sharesOf(Payment payment) {
        Map<Long, Seller> sellerById = new HashMap<>();
        Map<Long, Long> subtotalBySellerId = new HashMap<>();
        for (OrderItem item : payment.getOrder().getItems()) {
            Seller seller = item.getProduct().getSeller();
            sellerById.putIfAbsent(seller.getId(), seller);
            subtotalBySellerId.merge(seller.getId(), item.subtotal(), Long::sum);
        }

        return subtotalBySellerId.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> new SellerShare(sellerById.get(e.getKey()), e.getValue()))
                .toList();
    }

    private record SellerShare(Seller seller, long subtotal) {
    }
}
