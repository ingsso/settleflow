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

    protected LedgerEntry() {}

    public LedgerEntry(Journal journal) {
        this.journal = journal;
    }
}
