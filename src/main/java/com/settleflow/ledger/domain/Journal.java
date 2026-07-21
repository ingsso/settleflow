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
        Journal journal = new Journal(JournalType.PAYMENT);
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
