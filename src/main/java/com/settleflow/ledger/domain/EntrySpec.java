package com.settleflow.ledger.domain;

public record EntrySpec(LedgerAccount account, EntrySide side, long amount, Long sellerId) {
}
