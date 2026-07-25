package com.settleflow.ledger.domain;

import jakarta.persistence.*;
import lombok.Getter;

@Getter
@Entity
@Table(name = "ledger_entry")
public class LedgerEntry {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "journal_id", nullable = false)
    private Journal journal;

    @Column(name = "account")
    @Enumerated(EnumType.STRING)
    private LedgerAccount account;

    @Column(name = "dr_cr")
    @Enumerated(EnumType.STRING)
    private EntrySide drCr;

    @Column(name = "amount", nullable = false)
    private Long amount;

    /**
     * 분석 차원(nullable). "거래상대방"이 아니라 "이 줄이 누구에게 귀속되나".
     * {@code @ManyToOne} 이 아니라 생 {@code Long} 이다 — 장부는 Seller 로 항해하지 않고
     * Week 3 정산이 seller_id 로 집계할 뿐이다. 무결성은 DB FK(V4)가 지킨다.
     * CASH 줄에선 null(V4 chk_ledger_seller_presence).
     */
    @Column(name = "seller_id")
    private Long sellerId;

    protected LedgerEntry() {}

    /** 패키지 전용 — 줄은 오직 {@link Journal}(애그리거트 루트)만 만든다. */
    LedgerEntry(Journal journal, LedgerAccount account, EntrySide drCr, long amount, Long sellerId) {
        this.journal = journal;
        this.account = account;
        this.drCr = drCr;
        this.amount = amount;
        this.sellerId = sellerId;
    }
}
