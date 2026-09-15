package com.natwest.ledger.application;

import com.natwest.ledger.domain.AccountId;
import com.natwest.ledger.domain.LedgerEntry;

import java.util.List;

/**
 * The append-only store of ledger entries.
 *
 * <p>Offers no update or delete. That is the point: a ledger is a record of what happened, and the
 * absence of those operations means a mistake can only ever be corrected by appending a
 * compensating entry, never by rewriting history. Making the interface incapable of the wrong
 * thing is more reliable than a policy asking people not to do it.
 */
public interface LedgerRepository {

    /** Appends a single entry. */
    LedgerEntry append(LedgerEntry entry);

    /**
     * Appends several entries as one unit.
     *
     * <p>Exists for transfers, which produce a debit and a credit that must both be recorded or
     * neither: writing them one at a time leaves a window in which the ledger shows money leaving
     * one account and never arriving at the other.
     */
    List<LedgerEntry> appendAll(List<LedgerEntry> entries);

    /**
     * All entries for an account, oldest first.
     *
     * <p>Chronological because that is how a statement reads. Phase 3 adds paging for accounts
     * whose history outgrows a single response.
     */
    List<LedgerEntry> findByAccountId(AccountId accountId);
}
