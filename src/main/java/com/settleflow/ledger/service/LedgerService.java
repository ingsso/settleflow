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
 * 자동 분개(W2D2). 결제 확정 시 셀러별 이중장부 분개를 만든다(B2).
 *
 * <p>돈 쪼개기(요율·반올림)는 여기서 끝내고, 조립·균형검증은 {@link Journal#forPayment}에 맡긴다.
 * 한 주문에 셀러가 여럿이면 2N+1 줄이 된다: 현금 1줄 + 셀러별 (미지급금·수수료) 2줄.
 *
 * <p><b>호출 자리</b>: {@code PaymentTransitionService} 의 TRANSITIONED 부수효과(tx2 안).
 * 승인 전이가 실제로 일어난 1회에만 불리므로 중복 웹훅이 와도 journal 은 하나만 생긴다
 * (W2D1 §4 — W1D4 멱등 backbone 재사용). LAZY walk(order→items→product→seller)도 tx2 안이라 안전.
 */
@Service
public class LedgerService {

    private final JournalRepository journalRepository;

    public LedgerService(JournalRepository journalRepository) {
        this.journalRepository = journalRepository;
    }

    public Journal recordPayment(Payment payment) {
        // 셀러별 subtotal 을 묶는다. 맵 키는 Seller 인스턴스가 아니라 id — 안전하게.
        Map<Long, Seller> sellerById = new HashMap<>();
        Map<Long, Long> subtotalBySellerId = new HashMap<>();
        for (OrderItem item : payment.getOrder().getItems()) {
            Seller seller = item.getProduct().getSeller();
            sellerById.putIfAbsent(seller.getId(), seller);
            subtotalBySellerId.merge(seller.getId(), item.subtotal(), Long::sum);
        }

        List<EntrySpec> specs = new ArrayList<>();
        // 현금 차변 = 받은 금액 전체(= Σsubtotal). 셀러 없음.
        specs.add(new EntrySpec(LedgerAccount.CASH, EntrySide.DEBIT, payment.getAmount(), null));

        for (Map.Entry<Long, Long> e : subtotalBySellerId.entrySet()) {
            Long sellerId = e.getKey();
            long subtotal = e.getValue();
            int bp = sellerById.get(sellerId).getFeeRate();

            // 수수료만 반올림한다(정수 나눗셈은 버림이라 절반을 더해 반올림). 미지급금은 빼서 구한다
            // → 두 줄의 합이 정의상 subtotal 이라 균형이 구조적으로 성립한다.
            long fee = (subtotal * bp + 5000) / 10000;
            long payable = subtotal - fee;

            specs.add(new EntrySpec(LedgerAccount.SELLER_PAYABLE, EntrySide.CREDIT, payable, sellerId));
            specs.add(new EntrySpec(LedgerAccount.FEE_REVENUE, EntrySide.CREDIT, fee, sellerId));
        }

        Journal journal = Journal.forPayment(specs);   // 조립 + Σ차변==Σ대변 자체검증
        return journalRepository.save(journal);
    }
}
