package com.settleflow.ledger.repository;

import com.settleflow.ledger.domain.Journal;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JournalRepository extends JpaRepository<Journal, Long> {
}
