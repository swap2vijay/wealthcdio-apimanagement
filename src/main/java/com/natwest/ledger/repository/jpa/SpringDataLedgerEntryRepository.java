package com.natwest.ledger.repository.jpa;

import com.natwest.ledger.model.AccountId;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** Spring Data access to the ledger table. */
public interface SpringDataLedgerEntryRepository extends JpaRepository<LedgerEntryEntity, Long> {

    /**
     * An account's entries in insertion order.
     *
     * <p>Ordered by {@code sequence}, not {@code occurredAt}. Timestamps tie - both legs of a
     * transfer share one instant by design - and a tie leaves the order up to the database, which
     * means two reads of the same statement can disagree.
     */
    List<LedgerEntryEntity> findByAccountIdOrderBySequenceAsc(String accountId);

    /** The same ordering, one page at a time. */
    List<LedgerEntryEntity> findByAccountIdOrderBySequenceAsc(String accountId, Pageable pageable);

    long countByAccountId(String accountId);
}

