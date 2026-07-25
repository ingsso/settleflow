package com.settleflow.ledger.domain;

import jakarta.persistence.*;
import lombok.Getter;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Getter
@Entity
@Table(name = "journal")
public class Journal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "type")
    @Enumerated(EnumType.STRING)
    private JournalType type;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /**
     * Journal 이 자기 줄을 소유한다(B). journal 을 저장하면 줄도 함께 저장되도록 cascade.
     * {@code mappedBy}: FK(journal_id)는 {@link LedgerEntry} 쪽이 들고 있고, 이쪽은 반대편(읽기 컬렉션).
     */
    @OneToMany(mappedBy = "journal", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<LedgerEntry> entries = new ArrayList<>();

    protected Journal() {}

    private Journal(JournalType type) {
        this.type = type;
    }

    /**
     * 결제 분개 조립(B2). 서비스가 쪼갠 {@link EntrySpec} 목록을 줄로 바꿔 담고, 균형을 자체검증한다.
     *
     * <p>우리 정책(수수료만 반올림·미지급금은 빼서 구함, 현금=Σsubtotal) 하에서 균형은 <b>구조상</b>
     * 늘 성립한다. 그러니 여기서 불균형이 잡히면 그건 서비스가 조립을 틀린 것 = 프로그래밍 오류지
     * 사용자 입력 오류가 아니다 → {@link IllegalStateException}.
     */
    public static Journal forPayment(List<EntrySpec> specs) {
        return assemble(JournalType.PAYMENT, specs);
    }

    /**
     * 환불 역분개 조립(W2D5). {@code SELLER_PAYABLE} 차변(갚을 빚 감소) N 줄 + {@code CASH} 대변 1 줄.
     * {@code FEE_REVENUE} 는 건드리지 않는다 — 환불 시 수수료는 환급하지 않는다(W2D1 정책).
     *
     * <p>여기서도 {@link #assertBalanced} 는 <b>균형을 지키는 장치가 아니다.</b> 서비스가 마지막
     * 셀러 몫을 계산하지 않고 <i>뺄셈으로</i> 구하므로(=Σ배분액이 정의상 환불액) 균형은 반올림
     * 방식과 무관하게 성립한다. 이 검사가 걸린다면 그건 조립을 틀린 것 = 프로그래밍 오류다.
     * {@link #forPayment} 와 같은 지위.
     */
    public static Journal forRefund(List<EntrySpec> specs) {
        return assemble(JournalType.REFUND, specs);
    }

    private static Journal assemble(JournalType type, List<EntrySpec> specs) {
        Journal journal = new Journal(type);
        for (EntrySpec spec : specs) {
            journal.entries.add(new LedgerEntry(
                    journal, spec.account(), spec.side(), spec.amount(), spec.sellerId()));
        }
        journal.assertBalanced();
        return journal;
    }

    private void assertBalanced() {
        long debit = sumOf(EntrySide.DEBIT);
        long credit = sumOf(EntrySide.CREDIT);
        if (debit != credit) {
            throw new IllegalStateException(
                    "분개 불균형: 차변=%d 대변=%d".formatted(debit, credit));
        }
    }

    private long sumOf(EntrySide side) {
        return entries.stream()
                .filter(e -> e.getDrCr() == side)
                .mapToLong(LedgerEntry::getAmount)
                .sum();
    }

    @PrePersist
    void onCreate() {
        this.createdAt = OffsetDateTime.now();
    }

}
